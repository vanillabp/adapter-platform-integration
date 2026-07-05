package io.vanillabp.integration.test;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Exposes a property defined in <code>no-index-module.yaml</code> which is part of the
 * workflow-module JAR not having a Jandex index. The property is only available if the
 * workflow module was detected: only then the config source for the module-specific
 * config file is generated.
 */
@Path("/introspect")
@ApplicationScoped
public class NoIndexModuleIntrospectionController {

  @ConfigProperty(name = "no-index-module.test", defaultValue = "-1")
  int noIndexModuleTestProperty;

  @Path("/no-index-module-test-property")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getNoIndexModuleTestProperty() {

    return Integer.toString(noIndexModuleTestProperty);

  }

}
