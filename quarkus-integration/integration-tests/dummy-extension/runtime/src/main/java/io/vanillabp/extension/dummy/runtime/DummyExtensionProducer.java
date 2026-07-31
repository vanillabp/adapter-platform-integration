package io.vanillabp.extension.dummy.runtime;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Provides the dummy extension's {@link DummyWiringService} - the reference
 * implementation of the extension convention on Quarkus: extensions contribute
 * plain {@link ExtensionWiringService} <i>element</i> beans (they are not
 * per-adapter-id, so no List shape is involved). The platform keeps such beans from
 * ArC's unused-bean removal - they are only collected via <code>Instance</code>
 * lookups by the deployment runner.
 * <p>
 * The producer method is {@code @Singleton}: wiring-service implementations usually
 * have no no-arg constructor and are therefore not client-proxyable.
 */
@ApplicationScoped
public class DummyExtensionProducer {

  @Produces
  @Singleton
  public ExtensionWiringService<Object, Object> dummyExtensionWiringService(
      @Any final Instance<DummyExtensionListener> listeners) {

    return new DummyWiringService(listeners);

  }

}
