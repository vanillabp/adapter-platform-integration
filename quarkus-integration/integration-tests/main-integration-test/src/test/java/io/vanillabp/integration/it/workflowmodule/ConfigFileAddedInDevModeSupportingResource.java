package io.vanillabp.integration.it.workflowmodule;

import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Reports what the application read from the configuration files of the workflow module,
 * so a dev-mode test can ask a restarted application whether a file which appeared while
 * it ran was read. Each value has a default, because every file this resource reports on
 * is missing when the application boots for the first time.
 */
@Path("/")
public class ConfigFileAddedInDevModeSupportingResource {

  private final String bootId = UUID.randomUUID().toString();

  @ConfigProperty(name = "test-module.added-at-root", defaultValue = "absent")
  String addedAtRoot;

  @ConfigProperty(name = "test-module.added-in-subdirectory", defaultValue = "absent")
  String addedInSubdirectory;

  @ConfigProperty(name = "test-module.added-for-profile", defaultValue = "absent")
  String addedForProfile;

  @ConfigProperty(name = "test-module.changed", defaultValue = "absent")
  String changed;

  @Path("added-at-root")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getAddedAtRoot() {

    return addedAtRoot;

  }

  @Path("added-in-subdirectory")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getAddedInSubdirectory() {

    return addedInSubdirectory;

  }

  @Path("added-for-profile")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getAddedForProfile() {

    return addedForProfile;

  }

  @Path("changed")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getChanged() {

    return changed;

  }

  @Path("boot-id")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getBootId() {

    return bootId;

  }

}
