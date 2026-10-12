package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.SampleExtension;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Ten operations of one workflow are planned in one transaction. The application
 * dispatches them on four threads. They reach the handler in the order they were planned.
 * <p>
 * This test adds the wiring to {@code DispatchLanesTest}. That test holds the rule itself,
 * in milliseconds and without a database. A test which starts workflows cannot tell a
 * correct implementation from one which quietly runs everything one after the other. Here
 * the question is whether the dispatcher really hands its entries to the lanes, and
 * whether the poller really reads them in the order they were written.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EntriesOfOneAggregateKeepTheirOrderTest {

  /**
   * How many operations of one workflow wait for their dispatch at the same moment. Ten
   * are enough to catch a dispatcher which took its entries in any other order. Most of
   * them wait in the table meanwhile, because a lane takes only one entry beyond the one it
   * dispatches. Either way, the poller decides the order in which it hands them over.
   */
  private static final int OPERATIONS_OF_ONE_WORKFLOW = 10;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          // a database of its own: the sibling tests of this module count rows of the
          // outbox store which they share within the JVM
          .addAsResource("dispatch-ordering.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(SampleExtension.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:dispatch-ordering-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  SampleExtension extension;

  @Inject
  PhaseTwoOutbox outbox;

  @Inject
  UserTransaction userTransaction;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  @Test
  @DisplayName("Ten operations of one workflow arrive in the order they were planned")
  public void operationsOfOneWorkflowArriveInOrder() throws Exception {

    // all of them are written in ONE transaction. So nothing is dispatched before the last
    // one is written, and the dispatcher meets ten entries at once. This test is about
    // exactly that state
    userTransaction.begin();
    final Aggregate attached;
    try {
      attached = workflowService.startWorkflow("ordering");
      for (var operation = 0; operation < OPERATIONS_OF_ONE_WORKFLOW; operation++) {
        // the entries are ordered by the moment they were written. For two entries written
        // in the same microsecond, the database would decide the order. A millisecond
        // apart keeps this test about the lanes
        Thread.sleep(1);
        outbox
            .schedule(
                SampleExtension
                    .call("test-module", "dummy", attached.getId().toString(), eventOf(operation)));
      }
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();
    assertNotNull(attached);

    final var dispatched = extension.awaitDispatched(OPERATIONS_OF_ONE_WORKFLOW, 30000);

    assertEquals(
        IntStream
            .range(0, OPERATIONS_OF_ONE_WORKFLOW)
            .mapToObj(EntriesOfOneAggregateKeepTheirOrderTest::eventOf)
            .toList(),
        eventsOf(dispatched),
        "two operations of one workflow overtook each other");

  }

  /**
   * The event which the operation at that position notifies about. It is part of the
   * extension's idempotency key, so every operation is an entry of its own.
   *
   * @param operation The position in the row
   * @return The name of the event
   */
  private static String eventOf(
      final int operation) {

    return "event-%02d".formatted(operation);

  }

  private static List<String> eventsOf(
      final List<PhaseTwoCall> dispatched) {

    return dispatched
        .stream()
        .map(call -> String.valueOf(call.args().get(SampleExtension.ARG_EVENT)))
        .toList();

  }

}
