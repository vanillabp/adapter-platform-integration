package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Ten operations of one workflow, planned in one transaction. The application dispatches
 * on four threads. The operations reach the handler in the order they were planned.
 * <p>
 * This test adds the wiring to {@code DispatchLanesTest}. That test holds the rule
 * itself, in milliseconds and without a database. A test which starts workflows cannot
 * tell a correct implementation from one which quietly runs everything one after the
 * other. Here the question is whether the dispatcher really hands its entries to the
 * lanes, and whether the poller really reads them in the order they were written.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = TestApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox-ordering;DB_CLOSE_DELAY=-1", "vanillabp.outbox.dispatch-threads=4"
    })
public class EntriesOfOneAggregateKeepTheirOrderTest {

  /**
   * How many operations of one workflow wait for their dispatch at the same moment. Ten
   * are enough to catch a dispatcher which takes its entries in any other order. Most of
   * them wait in the table meanwhile, because a lane takes only one entry beyond the one
   * it dispatches. Either way, the poller decides the order in which it hands them over.
   */
  private static final int OPERATIONS_OF_ONE_WORKFLOW = 10;

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  @Test
  @DisplayName("Ten operations of one workflow arrive in the order they were planned")
  public void operationsOfOneWorkflowArriveInOrder() throws Exception {

    // all of them are in ONE transaction. So nothing is dispatched before the last one is
    // written, and the dispatcher meets ten entries at once. This test is about that state
    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("ordering");
      final var attached = processService.startWorkflow(newAggregate);
      IntStream
          .range(0, OPERATIONS_OF_ONE_WORKFLOW)
          .forEach(operation -> {
            // the entries are ordered by the moment they were written. For two entries
            // written in the same microsecond, the database would decide the order. A
            // millisecond apart keeps this test about the lanes
            pauseAMoment();
            outbox
                .schedule(
                    SampleExtension
                        .call("test-module", "dummy", attached.getId().toString(), eventOf(operation)));
          });
      return attached;
    });
    assertNotNull(aggregate);

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
   * The event the operation at that position notifies about. It is part of the
   * idempotency key of the extension, so each of them is a separate entry.
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

  private static void pauseAMoment() {

    try {
      Thread.sleep(1);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

  }

}
