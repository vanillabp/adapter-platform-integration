package io.vanillabp.integration.it.workflowmodule;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class ConfigWatcherForSubdirectoryConfigInDevModeTestSupportingResource {

  @ConfigProperty(name = "test-module.subdir-test")
  String subdirTest;

  @Path("subdir-test")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getSubdirTest() {

    return subdirTest;

  }

}
