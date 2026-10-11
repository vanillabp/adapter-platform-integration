package io.vanillabp.integration.test;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Exposes a property defined in <code>no-index-module.yaml</code>. That file is part of the
 * workflow-module JAR which has no Jandex index. The property is only there if the
 * workflow module was detected, because only then is the config source for the module's
 * own config file generated.
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
