package com.instaclustr.operations;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static org.awaitility.Awaitility.await;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import javax.validation.ConstraintViolation;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import com.instaclustr.icarus.operations.rebuild.RebuildOperationRequest;
import com.instaclustr.icarus.rest.IcarusClient;
import com.instaclustr.icarus.rest.IcarusClient.OperationResult;
import org.apache.commons.lang3.tuple.Pair;
import org.glassfish.jersey.server.validation.ValidationError;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

public class OperationsValidationTest extends AbstractIcarusTest {

    @Test
    public void validateRebuildRequests() {

        final RebuildOperationRequest allNullRebuildOperationRequest = new RebuildOperationRequest("rebuild", null, null, null, null);

        final Set<ConstraintViolation<RebuildOperationRequest>> constraintViolations = validator.validate(allNullRebuildOperationRequest);
        assertTrue(constraintViolations.isEmpty());

        final RebuildOperationRequest missingKeyspaceForSpecificTokens = new RebuildOperationRequest("rebuild",
                                                                                                     null,
                                                                                                     null,
                                                                                                     Stream.of(new RebuildOperationRequest.TokenRange("1", "2")).collect(toSet()),
                                                                                                     null);

        Set<ConstraintViolation<RebuildOperationRequest>> missingKeyspaceViolations = validator.validate(missingKeyspaceForSpecificTokens);
        assertFalse(missingKeyspaceViolations.isEmpty());
        assertEquals(missingKeyspaceViolations.size(), 1);

        assertTrue(missingKeyspaceViolations.stream().findFirst().isPresent());
        assertEquals(missingKeyspaceViolations.stream().findFirst().get().getMessage(), "Cannot set specificTokens without specifying a keyspace");
    }

    @Test
    @Ignore
    public void testRebuildRequests() throws IOException {

        final Function<IcarusClient, List<OperationResult<?>>> invalidRequests = client -> {

            final RebuildOperationRequest request1 = new RebuildOperationRequest("rebuild",
                                                                                 null,
                                                                                 null,
                                                                                 Stream.of(new RebuildOperationRequest.TokenRange("1", "2")).collect(toSet()),
                                                                                 null);

            return Stream.of(request1).map(client::rebuild).collect(toList());
        };

        final Pair<AtomicReference<List<OperationResult<?>>>, AtomicBoolean> result = performOnRunningServer(invalidRequests);

        await().atMost(1, MINUTES).until(() -> result.getRight().get());

        Response response = result.getLeft().get().get(0).response;

        final ValidationError[] validationErrors = objectMapper.readValue(IcarusClient.responseEntityToString(response), ValidationError[].class);

        result.getLeft().get().forEach(r -> assertEquals(response.getStatus(), BAD_REQUEST.getStatusCode()));

        assertEquals(validationErrors[0].getMessage(), "Cannot set specificTokens without specifying a keyspace");
    }
}
