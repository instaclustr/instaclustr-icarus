package com.instaclustr.cassandra.sidecar.coordination;

import static com.instaclustr.cassandra.backup.impl.restore.RestorationPhase.RestorationPhaseType.CLEANUP;
import static com.instaclustr.cassandra.backup.impl.restore.RestorationPhase.RestorationPhaseType.DOWNLOAD;
import static com.instaclustr.cassandra.backup.impl.restore.RestorationPhase.RestorationPhaseType.IMPORT;
import static com.instaclustr.cassandra.backup.impl.restore.RestorationPhase.RestorationPhaseType.INIT;
import static com.instaclustr.cassandra.backup.impl.restore.RestorationPhase.RestorationPhaseType.TRUNCATE;
import static com.instaclustr.cassandra.sidecar.coordination.CoordinationUtils.constructSidecars;
import static java.lang.String.format;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.io.Closeable;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.instaclustr.cassandra.backup.guice.BucketServiceFactory;
import com.instaclustr.cassandra.backup.guice.RestorerFactory;
import com.instaclustr.cassandra.backup.impl.BucketService;
import com.instaclustr.cassandra.backup.impl.StorageLocation;
import com.instaclustr.cassandra.backup.impl.restore.RestorationPhase.RestorationPhaseType;
import com.instaclustr.cassandra.backup.impl.restore.RestorationPhaseResultGatherer;
import com.instaclustr.cassandra.backup.impl.restore.RestorationStrategyResolver;
import com.instaclustr.cassandra.backup.impl.restore.RestoreOperation;
import com.instaclustr.cassandra.backup.impl.restore.RestoreOperationRequest;
import com.instaclustr.cassandra.backup.impl.restore.coordination.BaseRestoreOperationCoordinator;
import com.instaclustr.cassandra.sidecar.rest.SidecarClient;
import com.instaclustr.cassandra.topology.CassandraClusterTopology;
import com.instaclustr.cassandra.topology.CassandraClusterTopology.ClusterTopology;
import com.instaclustr.operations.GlobalOperationProgressTracker;
import com.instaclustr.operations.Operation;
import com.instaclustr.operations.OperationsService;
import com.instaclustr.operations.ResultGatherer;
import com.instaclustr.sidecar.picocli.SidecarSpec;
import com.instaclustr.threading.Executors.ExecutorServiceSupplier;
import jmx.org.apache.cassandra.service.CassandraJMXService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SidecarRestoreOperationCoordinator extends BaseRestoreOperationCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(SidecarBackupOperationCoordinator.class);

    private final CassandraJMXService cassandraJMXService;
    private final SidecarSpec sidecarSpec;

    private final ExecutorServiceSupplier executorServiceSupplier;
    private final OperationsService operationsService;
    private final ObjectMapper objectMapper;
    private final Map<String, BucketServiceFactory> bucketServiceFactoryMap;

    @Inject
    public SidecarRestoreOperationCoordinator(final Map<String, RestorerFactory> restorerFactoryMap,
                                              final RestorationStrategyResolver restorationStrategyResolver,
                                              final CassandraJMXService cassandraJMXService,
                                              final SidecarSpec sidecarSpec,
                                              final ExecutorServiceSupplier executorServiceSupplier,
                                              final OperationsService operationsService,
                                              final ObjectMapper objectMapper,
                                              final Map<String, BucketServiceFactory> bucketServiceFactoryMap) {
        super(restorerFactoryMap, restorationStrategyResolver);
        this.cassandraJMXService = cassandraJMXService;
        this.sidecarSpec = sidecarSpec;
        this.executorServiceSupplier = executorServiceSupplier;
        this.operationsService = operationsService;
        this.objectMapper = objectMapper;
        this.bucketServiceFactoryMap = bucketServiceFactoryMap;
    }

    @Override
    public ResultGatherer<RestoreOperationRequest> coordinate(final Operation<RestoreOperationRequest> operation) throws OperationCoordinatorException {

        try (final BucketService bucketService = bucketServiceFactoryMap.get(operation.request.storageLocation.storageProvider).createBucketService(operation.request)) {
            bucketService.checkBucket(operation.request.storageLocation.bucket, false);
        } catch (final Exception ex) {
            throw new OperationCoordinatorException(format("Bucket %s does not exist or we could not determine its existence.",
                                                           operation.request.storageLocation.bucket), ex);
        }

        /*
         * I receive a request
         *  If it is a global request, I will be coordinator
         *  otherwise just execute that request
         */

        // if it is not global request, there might be at most one global request running
        // and no other restore operations can run, so this means there might be at most one
        // global request running at this node, together with this "normal" restore operation - hence two.
        //
        // this node can be a coordinator of a global request and it can as well receive "normal" restoration request phase
        // so there is a valid case that this node will be running a global request and restoration phase simultaneously
        // hence there will be up to two operations of "restore" type and at most one of them is global

        if (!operation.request.globalRequest) {

            final List<UUID> restoreUUIDs = operationsService.allRunningOfType("restore");

            if (restoreUUIDs.size() > 2) {
                throw new IllegalStateException("There are more than two concurrent restore operations running!");
            }

            int normalRequests = 0;

            for (final UUID uuid : restoreUUIDs) {
                final Optional<Operation> operationOptional = operationsService.operation(uuid);

                if (!operationOptional.isPresent()) {
                    throw new IllegalStateException(format("received empty optional for uuid %s", uuid.toString()));
                }

                final Operation op = operationOptional.get();

                if (!(op.request instanceof RestoreOperationRequest)) {
                    throw new IllegalStateException(format("Received request is not of type %s", RestoreOperationRequest.class));
                }

                RestoreOperationRequest request = (RestoreOperationRequest) op.request;

                if (!request.globalRequest) {
                    normalRequests += 1;
                }
            }

            if (normalRequests == 2) {
                throw new IllegalStateException("We can not run two normal restoration requests simultaneously.");
            }

            return super.coordinate(operation);
        }

        // if it is a global request, we will coordinate whole restore across a cluster in this operation
        // when this operation finishes, whole cluster will be restored.

        // first we have to make some basic checks, e.g. we can be the only global restore operation on this node
        // and no other restore operations (even partial) can run simultaneously

        final List<UUID> restoreUUIDs = operationsService.allRunningOfType("restore");

        if (restoreUUIDs.size() != 1) {
            throw new IllegalStateException("There is more than one running restoration operation.");
        }

        if (!restoreUUIDs.get(0).equals(operation.id)) {
            throw new IllegalStateException("ID of a running operation does not equal to ID of this restore operation!");
        }

        if (operation.request.restorationPhase != DOWNLOAD) {
            throw new IllegalStateException(format("Restoration coordination has to start with %s phase.", DOWNLOAD));
        }

        final RestorationPhaseResultGatherer gatherer = new RestorationPhaseResultGatherer();

        try (final ClientsWrapper clientsWrapper = new ClientsWrapper(getSidecarClients())) {
            final ClientsWrapper oneClient = getOneClient(clientsWrapper);

            final ResultSupplier[] resultSuppliers = new ResultSupplier[]{
                    () -> gatherer.combine(executePhase(new InitPhasePreparation(), operation, oneClient)),
                    () -> gatherer.combine(executePhase(new DownloadPhasePreparation(), operation, clientsWrapper)),
                    () -> gatherer.combine(executePhase(new TruncatePhasePreparation(), operation, oneClient)),
                    () -> gatherer.combine(executePhase(new ImportingPhasePreparation(), operation, clientsWrapper)),
                    () -> gatherer.combine(executePhase(new CleaningPhasePreparation(), operation, clientsWrapper)),
            };

            for (final ResultSupplier supplier : resultSuppliers) {
                if (supplier.getWithEx().hasErrors())
                    return gatherer;
            }
        } catch (final Exception ex) {
            gatherer.gather(operation, new OperationCoordinatorException("Unable to coordinate distributed restore.", ex));
        }

        return gatherer;
    }

    public static class ClientsWrapper implements Closeable {
        public Map<InetAddress, SidecarClient> sidecarClients;

        public ClientsWrapper(final Map<InetAddress, SidecarClient> sidecarClients) {
            this.sidecarClients = sidecarClients;
        }

        @Override
        public void close() {
            if (sidecarClients != null) {
                for (final SidecarClient sidecarClient : sidecarClients.values()) {
                    if (sidecarClient != null) {
                        try {
                            sidecarClient.close();
                        } catch (final Exception ex) {
                            logger.error("Unable to close the client {}", sidecarClient);
                        }
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface ResultSupplier {

        ResultGatherer<RestoreOperationRequest> getWithEx() throws Exception;
    }

    private ClientsWrapper getOneClient(final ClientsWrapper sidecarWrapper) {
        if (sidecarWrapper.sidecarClients != null) {
            final Iterator<Entry<InetAddress, SidecarClient>> it = sidecarWrapper.sidecarClients.entrySet().iterator();

            if (it.hasNext()) {
                final Entry<InetAddress, SidecarClient> next = it.next();
                return new ClientsWrapper(new HashMap<InetAddress, SidecarClient>() {{
                    put(next.getKey(), next.getValue());
                }});
            }
        }

        throw new IllegalStateException("Unable to detect what client belong to this node!");
    }

    private static abstract class PhasePreparation {

        Operation<RestoreOperationRequest> prepare(final SidecarClient client, final RestoreOperationRequest request) throws OperationCoordinatorException {
            try {
                final RestoreOperation restoreOperation = cloneOp(request);
                prepareBasics(restoreOperation.request, client);
                restoreOperation.request.restorationPhase = getPhaseType();

                return restoreOperation;
            } catch (final Exception ex) {
                throw new OperationCoordinatorException(format("Unable to prepare operation for %s phase.", getPhaseType()), ex);
            }
        }

        abstract RestorationPhaseType getPhaseType();

        RestoreOperation cloneOp(final RestoreOperationRequest request) throws CloneNotSupportedException {
            final RestoreOperationRequest clonedRequest = (RestoreOperationRequest) request.clone();
            return new RestoreOperation(clonedRequest);
        }

        void prepareBasics(final RestoreOperationRequest request, final SidecarClient client) throws OperationCoordinatorException {

            if (!client.getHostId().isPresent()) {
                throw new OperationCoordinatorException(format("There is not any hostId for client %s", client.getHost()));
            }

            request.storageLocation = StorageLocation.update(request.storageLocation, client.getClusterName(), client.getDc(), client.getHostId().get().toString());
            request.storageLocation.globalRequest = false;
            request.globalRequest = false;
        }
    }

    private static final class DownloadPhasePreparation extends PhasePreparation {
        @Override
        public RestorationPhaseType getPhaseType() {
            return DOWNLOAD;
        }
    }

    private static final class TruncatePhasePreparation extends PhasePreparation {
        @Override
        public RestorationPhaseType getPhaseType() {
            return TRUNCATE;
        }
    }

    private static final class ImportingPhasePreparation extends PhasePreparation {
        @Override
        public RestorationPhaseType getPhaseType() {
            return IMPORT;
        }
    }

    private static final class CleaningPhasePreparation extends PhasePreparation {
        @Override
        public RestorationPhaseType getPhaseType() {
            return CLEANUP;
        }
    }

    private static final class InitPhasePreparation extends PhasePreparation {
        @Override
        RestorationPhaseType getPhaseType() {
            return INIT;
        }
    }

    private Map<InetAddress, SidecarClient> getSidecarClients() throws Exception {
        final ClusterTopology clusterTopology = new CassandraClusterTopology(cassandraJMXService, null).act();
        return constructSidecars(clusterTopology.clusterName, clusterTopology.endpoints, clusterTopology.endpointDcs, sidecarSpec, objectMapper);
    }

    private RestorationPhaseResultGatherer executePhase(final PhasePreparation phasePreparation,
                                                        final Operation<RestoreOperationRequest> globalOperation,
                                                        final ClientsWrapper clientsWrapper) throws OperationCoordinatorException {
        final ExecutorService executorService = executorServiceSupplier.get(MAX_NUMBER_OF_CONCURRENT_OPERATIONS);

        final RestorationPhaseResultGatherer resultGatherer = new RestorationPhaseResultGatherer();

        try {
            final List<RestoreOperationCallable> callables = new ArrayList<>();
            final GlobalOperationProgressTracker progressTracker = new GlobalOperationProgressTracker(globalOperation, clientsWrapper.sidecarClients.entrySet().size());

            // create

            for (final Entry<InetAddress, SidecarClient> entry : clientsWrapper.sidecarClients.entrySet()) {
                callables.add(new RestoreOperationCallable(phasePreparation.prepare(entry.getValue(), globalOperation.request),
                                                           entry.getValue(),
                                                           progressTracker));
            }

            // submit & gather results

            allOf(callables.stream().map(c -> supplyAsync(c, executorService).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    resultGatherer.gather(result, throwable);
                }
            })).toArray(CompletableFuture<?>[]::new)).get();

        } catch (ExecutionException | InterruptedException ex) {
            ex.printStackTrace();
            resultGatherer.gather(globalOperation, new OperationCoordinatorException("Unable to coordinate restoration!", ex));
        } finally {
            executorService.shutdownNow();
        }

        return resultGatherer;
    }

    private static class RestoreOperationCallable extends OperationCallable<RestoreOperation, RestoreOperationRequest> {

        public RestoreOperationCallable(final Operation<RestoreOperationRequest> operation,
                                        final SidecarClient sidecarClient,
                                        final GlobalOperationProgressTracker progressTracker) {
            super(operation, operation.request.timeout, sidecarClient, progressTracker, operation.request.restorationPhase.toString().toLowerCase());
        }

        public SidecarClient.OperationResult<RestoreOperation> sendOperation() {
            return sidecarClient.restore(operation.request);
        }
    }
}