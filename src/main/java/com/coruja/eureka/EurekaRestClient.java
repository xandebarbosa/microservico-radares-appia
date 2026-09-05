package com.coruja.eureka;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "eureka-api-appia")
public interface EurekaRestClient {

    @POST
    @Path("/apps/{appId}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response register(@PathParam("appId") String appId, String payload);

    @PUT
    @Path("/apps/{appId}/{instaceId}")
    Response heartbeat(@PathParam("appId") String appId, @PathParam("instaceId") String instanceId);
}
