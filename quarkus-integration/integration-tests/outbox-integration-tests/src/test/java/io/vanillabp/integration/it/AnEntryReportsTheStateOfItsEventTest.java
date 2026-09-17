package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregateHistoryService;
import io.vanillabp.integration.test.AggregateHistoryServiceFactory;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.SampleExtension;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * An outbox entry is written in the transaction of an event and dispatched later, so the
 * aggregate it reads has moved on by then - milliseconds while everything works, days
 * once a receiver is gone. An entry which reports may say which state it means when it
 * is planned, and its dispatch then reads the aggregate as it was at that moment.
 * <p>
 * The same three cases the Spring Boot integration proves in its own scenario, measured
 * separately per platform on purpose: the seam working in the core says nothing about
 * this platform ever calling it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnEntryReportsTheStateOfItsEventTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "dummy";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          // an own database: the sibling tests of this module count rows of the
          // outbox store they share within the JVM
          .addAsResource("state-of-the-event.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(AggregateHistoryService.class)
          .addClass(AggregateHistoryServiceFactory.class)
          .addClass(WorkflowService.class)
          .addClass(SampleExtension.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:state-of-the-event-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  SampleExtension extension;

  @Inject
  AggregatePersistence persistence;

  @Inject
  AggregateHistoryService<Aggregate> historyService;

  @Inject
  PhaseTwoOutbox outbox;

  @Inject
  UserTransaction userTransaction;

  @BeforeEach
  public void loadTheAggregateWhileDispatching() {

    extension.reset();
    // what the receiver of this extension is handed: the aggregate, read while the entry
    // is dispatched and in the state the entry asked for
    extension.loadTheAggregateWhileDispatching(historyService::asItWasAt);

  }

  @AfterEach
  public void stopLoading() {

    extension.reset();

  }

  /**
   * Starts a workflow and then, in ONE later transaction, plans an entry of the
   * extension and changes the aggregate afterwards. So by the time the entry is
   * dispatched - which is after that transaction committed - the state the entry was
   * planned in is not the current one any more, whatever the dispatcher's timing is.
   *
   * @param event The event the entry is about
   * @param auditingIdOfTheEvent Takes the aggregate as the planner sees it and answers
   *          the state the entry is to see, or <code>null</code> for an entry which asks
   *          for nothing
   * @return The aggregate, changed
   * @throws Exception If a transaction fails
   */
  private Aggregate anEntryPlannedBeforeTheAggregateChanged(
      final String event,
      final Function<Aggregate, String> auditingIdOfTheEvent) throws Exception {

    userTransaction.begin();
    final var started = workflowService.startWorkflow("as it was");
    userTransaction.commit();

    userTransaction.begin();
    final var atTheEvent = persistence.loadById(started.getId());
    outbox
        .schedule(
            SampleExtension
                .callAboutTheStateOfNow(
                    MODULE, PROCESS, atTheEvent.getId().toString(), event,
                    auditingIdOfTheEvent.apply(atTheEvent)));
    atTheEvent.setContent("as it is");
    final var changed = persistence.save(atTheEvent);
    userTransaction.commit();
    return changed;

  }

  @Test
  @DisplayName("An entry which asked for the state of its event reads the aggregate as it was")
  public void theDispatchReadsTheAggregateAsItWas() throws Exception {

    final var auditingIdOfTheEvent = new java.util.concurrent.atomic.AtomicReference<String>();
    final var aggregate = anEntryPlannedBeforeTheAggregateChanged("created", atTheEvent -> {
      auditingIdOfTheEvent.set(historyService.auditingIdOf(atTheEvent));
      return auditingIdOfTheEvent.get();
    });
    assertEquals("as it is", aggregate.getContent(), "the aggregate really moved on");
    assertNotNull(auditingIdOfTheEvent.get(), "the persistence of this application names its states");

    final var call = extension.awaitDispatched(1, 10000).getFirst();
    assertEquals(auditingIdOfTheEvent.get(), call.auditingId(), "the id travelled with the entry");

    final var loaded = (Aggregate) extension.getLoadedWhileDispatching().getFirst();
    assertEquals("as it was", loaded.getContent());

  }

  @Test
  @DisplayName("An entry which asked for nothing reads the aggregate as it is at its dispatch")
  public void theDispatchReadsTheCurrentStateByDefault() throws Exception {

    final var aggregate = anEntryPlannedBeforeTheAggregateChanged("unasked", atTheEvent -> null);
    assertEquals("as it is", aggregate.getContent());

    final var call = extension.awaitDispatched(1, 10000).getFirst();
    assertNull(call.auditingId());

    final var loaded = (Aggregate) extension.getLoadedWhileDispatching().getFirst();
    assertEquals("as it is", loaded.getContent());

  }

  /**
   * The warning VanillaBP writes about the missing state is asserted by the Spring Boot
   * scenario and by {@code AggregateAsItWasTest} in the core. A test method of this
   * platform cannot take the captured output as a parameter: the Quarkus test extension
   * invokes it itself and hands it no arguments. What is measured here is the behaviour,
   * which is the half a platform has to prove for itself.
   */
  @Test
  @DisplayName("A state the auditing no longer has is reported with the current one")
  public void aStateWhichIsGoneFallsBackToTheCurrentOne() throws Exception {

    // an entry which waited longer than the auditing keeps its revisions: the id is
    // there, the state behind it is not
    anEntryPlannedBeforeTheAggregateChanged(
        "cleaned-up",
        atTheEvent -> "rev-nothing-is-stored-under-this");

    final var call = extension.awaitDispatched(1, 10000).getFirst();
    assertEquals("rev-nothing-is-stored-under-this", call.auditingId());

    final var reported = (Aggregate) extension.getLoadedWhileDispatching().getFirst();
    assertEquals("as it is", reported.getContent(), "a report with newer values beats no report");

  }

}
