package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * An outbox entry is written in the transaction of an event and dispatched later, so the
 * aggregate it reads has moved on by then - milliseconds while everything works, days
 * once a receiver is gone. An entry which reports may say which state it means when it
 * is planned, and its dispatch then reads the aggregate as it was at that moment.
 * <p>
 * The application of this scenario keeps a history of its aggregate
 * ({@link AggregatePersistenceWithAHistory}), which is what makes an old state
 * available at all. An application without one gets the state of the dispatch, whatever
 * its entries ask for - {@code AggregateAsItWasTest} holds that half in the core.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class AnEntryReportsTheStateOfItsEventTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "dummy";

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @Autowired
  private AggregatePersistenceWithAHistory persistence;

  @Autowired
  private AggregateHistoryService<Aggregate> historyService;

  private ListAppender<ILoggingEvent> logWatcher;

  private Logger serviceLogger;

  @BeforeEach
  public void loadTheAggregateWhileDispatchingAndWatchTheLog() {

    extension.reset();
    // what the receiver of this extension is handed: the aggregate, read while the entry
    // is dispatched and in the state the entry asked for
    extension.loadTheAggregateWhileDispatching(historyService::asItWasAt);
    logWatcher = new ListAppender<>();
    logWatcher.start();
    serviceLogger = (Logger) LoggerFactory.getLogger(MigrationProcessService.class);
    serviceLogger.addAppender(logWatcher);

  }

  @AfterEach
  public void stopLoadingAndWatching() {

    extension.reset();
    serviceLogger.detachAppender(logWatcher);
    logWatcher.stop();

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
   */
  private Aggregate anEntryPlannedBeforeTheAggregateChanged(
      final String event,
      final java.util.function.Function<Aggregate, String> auditingIdOfTheEvent) {

    final var started = new AtomicReference<Aggregate>();
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("as it was");
      started.set(processService.startWorkflow(aggregate));
    });

    return transactionTemplate.execute(status -> {
      final var atTheEvent = persistence.loadById(started.get().getId());
      outbox
          .schedule(
              SampleExtension
                  .callAboutTheStateOfNow(
                      MODULE, PROCESS, atTheEvent.getId().toString(), event,
                      auditingIdOfTheEvent.apply(atTheEvent)));
      atTheEvent.setContent("as it is");
      return persistence.save(atTheEvent);
    });

  }

  private List<String> warnings() {

    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  @Test
  @DisplayName("An entry which asked for the state of its event reads the aggregate as it was")
  public void theDispatchReadsTheAggregateAsItWas() throws Exception {

    final var auditingIdOfTheEvent = new AtomicReference<String>();
    final var aggregate = anEntryPlannedBeforeTheAggregateChanged("created", attached -> {
      auditingIdOfTheEvent.set(historyService.auditingIdOf(attached));
      return auditingIdOfTheEvent.get();
    });
    assertEquals("as it is", aggregate.getContent(), "the aggregate really moved on");
    assertNotNull(auditingIdOfTheEvent.get(), "the persistence of this application names its states");

    final var call = extension.awaitDispatched(1, 10000).getFirst();
    assertEquals(auditingIdOfTheEvent.get(), call.auditingId(), "the id travelled with the entry");

    final var loaded = (Aggregate) extension.getLoadedWhileDispatching().getFirst();
    assertEquals("as it was", loaded.getContent());
    assertTrue(warnings().isEmpty(), warnings().toString());

  }

  @Test
  @DisplayName("An entry which asked for nothing reads the aggregate as it is at its dispatch")
  public void theDispatchReadsTheCurrentStateByDefault() throws Exception {

    final var aggregate = anEntryPlannedBeforeTheAggregateChanged("unasked", attached -> null);
    assertEquals("as it is", aggregate.getContent());

    final var call = extension.awaitDispatched(1, 10000).getFirst();
    assertNull(call.auditingId());

    final var loaded = (Aggregate) extension.getLoadedWhileDispatching().getFirst();
    assertEquals("as it is", loaded.getContent());
    assertTrue(warnings().isEmpty(), warnings().toString());

  }

  @Test
  @DisplayName("A state the auditing no longer has is reported with the current one, and a warning")
  public void aStateWhichIsGoneFallsBackToTheCurrentOne() throws Exception {

    // an entry which waited longer than the auditing keeps its revisions: the id is
    // there, the state behind it is not
    final var aggregate = anEntryPlannedBeforeTheAggregateChanged(
        "cleaned-up",
        attached -> "rev-nothing-is-stored-under-this");

    extension.awaitDispatched(1, 10000);

    final var reported = (Aggregate) extension.getLoadedWhileDispatching().getFirst();
    assertEquals("as it is", reported.getContent(), "a report with newer values beats no report");

    final var warning = warnings()
        .stream()
        .filter(line -> line.contains("rev-nothing-is-stored-under-this"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no warning about the missing state: "
            + warnings()));
    // named the way somebody reading the log finds the report it belongs to
    assertTrue(warning.contains(aggregate.getId().toString()), warning);
    assertTrue(warning.contains(MODULE), warning);

  }

}
