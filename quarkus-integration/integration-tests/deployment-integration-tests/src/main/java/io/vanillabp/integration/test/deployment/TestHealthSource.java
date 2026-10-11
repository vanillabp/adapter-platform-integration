package io.vanillabp.integration.test.deployment;

import java.util.function.Function;

import io.vanillabp.bpmsdouble.DummyHealthSource;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Plays what the dummy adapter finds when it asks its BPMS. The answer is a static field.
 * So a test can change it between two checks without building the application again.
 */
@ApplicationScoped
public class TestHealthSource implements DummyHealthSource {

  /**
   * What to answer. The test sets it. The default adds nothing, just like an adapter
   * without a check.
   */
  public static Function<String, AdapterHealth> answer = adapterId -> null;

  @Override
  public AdapterHealth healthOf(
      final String adapterId) {

    return answer.apply(adapterId);

  }

}
