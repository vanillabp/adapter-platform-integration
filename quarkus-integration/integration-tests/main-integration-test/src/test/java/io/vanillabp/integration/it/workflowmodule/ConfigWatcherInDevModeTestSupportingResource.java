package io.vanillabp.integration.it.workflowmodule;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class ConfigWatcherInDevModeTestSupportingResource {

  @ConfigProperty(name = "test-module.test")
  String test;

  @Path("test")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getTest() {

    return test;

  }

}
