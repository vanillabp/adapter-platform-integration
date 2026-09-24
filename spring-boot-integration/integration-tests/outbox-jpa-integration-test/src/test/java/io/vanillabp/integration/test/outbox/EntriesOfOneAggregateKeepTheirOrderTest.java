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
 * Ten operations of one workflow, planned in one transaction and dispatched by an
 * application which dispatches on four threads: they reach the handler in the order they
 * were planned.
 * <p>
 * What this test adds to {@code DispatchLanesTest} is the wiring. The rule itself is held
 * there, in milliseconds and without a database, because a test which starts workflows
 * cannot tell a correct implementation from one which quietly serialises everything. Here
 * the question is whether the dispatcher really hands its entries to the lanes, and
 * whether the poller really reads them in the order they were written.
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
   * of them: enough that a dispatcher which took its entries in any other order would be
   * caught. Most of them wait in the table meanwhile, because a lane takes one entry beyond
   * the one it dispatches, and the order is what the poller hands them over in either way.
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

    // all of them ride ONE transaction, so nothing is dispatched before the last one is
    // written and the dispatcher meets ten entries at once - which is the state this test
    // is about
    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("ordering");
      final var attached = processService.startWorkflow(newAggregate);
      IntStream
          .range(0, OPERATIONS_OF_ONE_WORKFLOW)
          .forEach(operation -> {
            // the entries are ordered by the moment they were written, and two of them
            // written in the same microsecond would leave that order to the database.
            // A millisecond apart is what keeps this test about the lanes
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
   * The event the operation of that position notifies about. It is part of the
   * extension's idempotency key, so every one of them is an entry of its own.
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
