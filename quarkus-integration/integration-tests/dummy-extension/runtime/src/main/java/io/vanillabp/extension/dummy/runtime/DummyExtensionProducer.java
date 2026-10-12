package io.vanillabp.extension.dummy.runtime;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Provides the dummy extension's {@link DummyWiringService}. It is the reference
 * implementation of the extension convention on Quarkus. Extensions contribute plain
 * {@link ExtensionWiringService} <i>element</i> beans. They do not exist per adapter id,
 * so no List shape is involved. The platform keeps such beans from being removed by ArC as
 * unused, because only the deployment runner collects them, through <code>Instance</code>
 * lookups.
 * <p>
 * The producer method is {@code @Singleton}. Wiring-service implementations usually have
 * no constructor without arguments, so the container cannot wrap them in a client proxy.
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
