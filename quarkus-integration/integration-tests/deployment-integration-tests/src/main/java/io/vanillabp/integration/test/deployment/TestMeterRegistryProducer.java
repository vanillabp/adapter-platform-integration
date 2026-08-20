package io.vanillabp.integration.test.deployment;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * A registry backend for the test: Quarkus adds every {@code MeterRegistry} bean to
 * its composite, and only a composite WITH a child actually records measurements.
 */
@ApplicationScoped
public class TestMeterRegistryProducer {

  @Produces
  @Singleton
  public SimpleMeterRegistry simpleMeterRegistry() {

    return new SimpleMeterRegistry();

  }

}
