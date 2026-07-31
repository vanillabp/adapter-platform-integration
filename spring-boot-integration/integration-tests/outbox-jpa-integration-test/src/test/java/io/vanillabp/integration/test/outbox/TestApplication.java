package io.vanillabp.integration.test.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test application for the gruelbox-based JPA {@link io.vanillabp.integration.spi.PhaseTwoOutbox}:
 * the dummy adapter is forced to require a two-phase commit for starting workflows
 * (property <code>dummy-adapter.two-phase-commit</code>) and a
 * {@link RecordingPhaseTwoListener} observes (and optionally fails) phase two.
 */
@SpringBootApplication
public class TestApplication {

  @Bean
  public RecordingPhaseTwoListener recordingPhaseTwoListener() {

    return new RecordingPhaseTwoListener();

  }

}
