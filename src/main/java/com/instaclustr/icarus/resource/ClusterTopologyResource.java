package com.instaclustr.icarus.resource;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import com.instaclustr.icarus.service.CassandraService;

@Path("/topology")
@Produces(APPLICATION_JSON)
public class ClusterTopologyResource {

    private final CassandraService cassandraService;

    @Inject
    public ClusterTopologyResource(final CassandraService cassandraService) {
        this.cassandraService = cassandraService;
    }

    @GET
    public Response getCassandraTopology() {
        try {
            return Response.ok(cassandraService.getClusterTopology(null)).build();
        } catch (final Exception ex) {
            return Response.serverError().entity(ex).build();
        }
    }

    @GET
    @Path("{dc}")
    public Response getCassandraTopology(final @PathParam("dc") String dc) {
        try {
            return Response.ok(cassandraService.getClusterTopology(dc)).build();
        } catch (final Exception ex) {
            return Response.serverError().entity(ex).build();
        }
    }
}
