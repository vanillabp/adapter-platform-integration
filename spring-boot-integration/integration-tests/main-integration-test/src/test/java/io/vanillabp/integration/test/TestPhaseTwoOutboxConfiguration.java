package io.vanillabp.integration.test;

import java.util.LinkedList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;

/**
 * The outbox for tests not concerned with it, next to
 * {@link TestPersistenceConfiguration}. Every operation which reaches a BPMS is
 * planned in the caller's transaction and dispatched after it committed, so an
 * application without a store cannot start a workflow and the platform says so while
 * it boots. An application which drives its BPMS through a double needs one all the
 * same, and this is it: it remembers what was planned and dispatches nothing, which
 * is what tests about wiring, versions or delivery want to happen to a phase two.
 * <p>
 * A test which cares about the dispatch brings its own store instead of this one -
 * two of them in one application is the ambiguity the resolver refuses.
 */
@Configuration
public class TestPhaseTwoOutboxConfiguration {

  /**
   * What was planned so far, so a test may look at it.
   */
  public static final List<PhaseTwoCall> PLANNED = new LinkedList<>();

  /**
   * Forgets what was planned so far - a test looking at {@link #PLANNED} starts from
   * a known state, whatever ran before it.
   */
  public static void clear() {

    synchronized (PLANNED) {
      PLANNED.clear();
    }

  }

  @Bean
  PhaseTwoOutbox testPhaseTwoOutbox() {

    return call -> {
      synchronized (PLANNED) {
        PLANNED.add(call);
      }
      return true;
    };

  }

}
