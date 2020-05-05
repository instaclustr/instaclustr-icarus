package com.instaclustr.cassandra.sidecar.service;

import static com.google.inject.multibindings.OptionalBinder.newOptionalBinder;
import static com.google.inject.util.Types.newParameterizedType;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.instaclustr.cassandra.backup.impl.backup.BackupOperationRequest;
import com.instaclustr.cassandra.backup.impl.restore.RestoreOperationRequest;
import com.instaclustr.cassandra.sidecar.coordination.SidecarBackupOperationCoordinator;
import com.instaclustr.cassandra.sidecar.coordination.SidecarRestoreOperationCoordinator;
import com.instaclustr.operations.OperationCoordinator;
import jmx.org.apache.cassandra.service.CassandraJMXService;

public class ServicesModule extends AbstractModule {

    @Provides
    @Singleton
    public CassandraStatusService getCassandraStatusService(final CassandraJMXService cassandraJMXService) {
        return new CassandraStatusService(cassandraJMXService);
    }

    @Override
    protected void configure() {

        @SuppressWarnings("unchecked") final TypeLiteral<OperationCoordinator<RestoreOperationRequest>> operationCoordinator =
                (TypeLiteral<OperationCoordinator<RestoreOperationRequest>>) TypeLiteral.get(newParameterizedType(OperationCoordinator.class, RestoreOperationRequest.class));

        @SuppressWarnings("unchecked") final TypeLiteral<OperationCoordinator<BackupOperationRequest>> backupOperationCoordinator =
                (TypeLiteral<OperationCoordinator<BackupOperationRequest>>) TypeLiteral.get(newParameterizedType(OperationCoordinator.class, BackupOperationRequest.class));

        newOptionalBinder(binder(), operationCoordinator).setBinding().to(SidecarRestoreOperationCoordinator.class);
        newOptionalBinder(binder(), backupOperationCoordinator).setBinding().to(SidecarBackupOperationCoordinator.class);
    }
}
