package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Loading the aggregate as it was when an outbox entry was planned: the persistence of
 * the application answers it, a persistence which keeps no history answers the state of
 * now, and a state the auditing has already cleaned up ends in a report of the current
 * values plus a line saying so.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateAsItWasTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

  /**
   * The aggregate of this test: an id and one value which changes, so "as it was" and
   * "as it is" can be told apart.
   */
  public static class Note {

    private String id;

    private String content;

    public String getId() {
      return id;
    }

    public void setId(
        final String id) {
      this.id = id;
    }

    public String getContent() {
      return content;
    }

    public void setContent(
        final String content) {
      this.content = content;
    }

  }

  /**
   * A persistence without any auditing - what nearly every application has. It
   * overrides neither of the two methods this story added, so both defaults apply.
   */
  private static class PlainPersistence implements AggregatePersistenceAware<Note> {

    final Map<String, String> contents = new HashMap<>();

    @Override
    public Class<Note> getAggregateClass() {

      return Note.class;

    }

    @Override
    public Object getAggregateId(
        final Note aggregate) {

      return aggregate.getId();

    }

    @Override
    public Note save(
        final Note aggregate) {

      contents.put(aggregate.getId(), aggregate.getContent());
      return aggregate;

    }

    @Override
    public Note loadById(
        final Object aggregateId) {

      final var content = contents.get(String.valueOf(aggregateId));
      if (content == null) {
        return null;
      }
      final var note = new Note();
      note.setId(String.valueOf(aggregateId));
      note.setContent(content);
      return note;

    }

  }

  /**
   * A persistence keeping a history of its own, the way an application using Hibernate
   * Envers or a document store with versions does: every save gets a revision, and a
   * revision can be read back.
   */
  private static final class HistoryKeepingPersistence extends PlainPersistence {

    private final Map<String, String> revisions = new HashMap<>();

    private final Map<String, String> currentRevision = new HashMap<>();

    private int nextRevision = 1;

    /**
     * The revision this aggregate stands at - the state whoever asks is looking at.
     */
    @Override
    public String getAuditingId(
        final Note aggregate) {

      return currentRevision.get(aggregate.getId());

    }

    @Override
    public Note save(
        final Note aggregate) {

      final var revision = "rev-"
          + nextRevision++;
      revisions.put("%s|%s".formatted(aggregate.getId(), revision), aggregate.getContent());
      currentRevision.put(aggregate.getId(), revision);
      return super.save(aggregate);

    }

    @Override
    public Note loadByIdAndAuditingId(
        final Object aggregateId,
        final String auditingId) {

      final var content = revisions.get("%s|%s".formatted(aggregateId, auditingId));
      if (content == null) {
        return null;
      }
      final var note = new Note();
      note.setId(String.valueOf(aggregateId));
      note.setContent(content);
      return note;

    }

    /**
     * Forgets everything older than the given revision - what a clean-up job of the
     * application does while an outbox entry is still waiting.
     */
    void cleanUpEverythingBefore(
        final int revision) {

      revisions
          .keySet()
          .removeIf(key -> Integer.parseInt(key.substring(key.indexOf("|rev-") + 5)) < revision);

    }

  }

  private ListAppender<ILoggingEvent> logWatcher;

  private Logger serviceLogger;

  @BeforeEach
  public void watchTheLog() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    serviceLogger = (Logger) LoggerFactory.getLogger(MigrationProcessService.class);
    serviceLogger.addAppender(logWatcher);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    serviceLogger.detachAppender(logWatcher);
    logWatcher.stop();

  }

  private MigrationProcessService<Note> serviceOf(
      final AggregatePersistenceAware<Note> persistence) {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Note> adapter = mock(MigratableProcessService.class);
    io.vanillabp.migration.test.AdapterMocks.recordingItsOperations(adapter);
    lenient().when(adapter.getAdapterId()).thenReturn(ADAPTER);

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Note.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .build();

  }

  private static Note noteOf(
      final String id,
      final String content) {

    final var note = new Note();
    note.setId(id);
    note.setContent(content);
    return note;

  }

  private List<String> warnings() {

    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  @Test
  @DisplayName("An application without auditing names no state and loads the one of now")
  public void withoutAuditingNothingChanges() {

    final var persistence = new PlainPersistence();
    final var testee = serviceOf(persistence);
    final var note = noteOf("4711", "as it was");
    persistence.save(note);

    assertNull(testee.getAuditingId(note), "a persistence without a history names no state");

    persistence.save(noteOf("4711", "as it is"));

    // the default of loadByIdAndAuditingId ignores the id, so even an entry which asks
    // for a state gets the current one - and nothing is warned about, because nothing
    // was promised
    assertEquals("as it is", testee.loadWorkflowAggregateById("4711", "rev-1").getContent());
    assertEquals("as it is", testee.loadWorkflowAggregateById("4711", null).getContent());
    assertTrue(warnings().isEmpty(), warnings().toString());

  }

  @Test
  @DisplayName("An application with auditing gets the state its entry was planned in")
  public void theStateOfTheEventIsLoaded() {

    final var persistence = new HistoryKeepingPersistence();
    final var testee = serviceOf(persistence);

    final var note = noteOf("4711", "as it was");
    persistence.save(note);
    final var auditingId = testee.getAuditingId(note);
    persistence.save(noteOf("4711", "as it is"));

    assertEquals("rev-1", auditingId);
    assertEquals("as it was", testee.loadWorkflowAggregateById("4711", auditingId).getContent());
    // and the entry which wants the current state gets it, without reading any history
    assertEquals("as it is", testee.loadWorkflowAggregateById("4711", null).getContent());
    assertTrue(warnings().isEmpty(), warnings().toString());

  }

  @Test
  @DisplayName("A state the auditing cleaned up is reported with the current values, and a warning")
  public void aCleanedUpStateFallsBackToTheCurrentOne() {

    final var persistence = new HistoryKeepingPersistence();
    final var testee = serviceOf(persistence);

    final var note = noteOf("4711", "as it was");
    persistence.save(note);
    final var auditingId = testee.getAuditingId(note);
    persistence.save(noteOf("4711", "as it is"));
    persistence.cleanUpEverythingBefore(2);

    assertEquals("as it is", testee.loadWorkflowAggregateById("4711", auditingId).getContent());

    assertEquals(1, warnings().size(), warnings().toString());
    final var warning = warnings().getFirst();
    assertTrue(warning.contains("4711"), warning);
    assertTrue(warning.contains(auditingId), warning);
    assertTrue(warning.contains(PROCESS), warning);
    assertTrue(warning.contains(MODULE), warning);

  }

  @Test
  @DisplayName("A context written by somebody else keeps working, and answers the state of now")
  public void aContextOfSomebodyElseKeepsWorking() {

    final var persistence = new PlainPersistence();
    persistence.save(noteOf("4711", "as it is"));

    // an extension which wrote its own context - a test double of its own, say - gets
    // the two new methods without touching it, and they answer what an application
    // without an auditing has
    final var context = new io.vanillabp.integration.extension.spi.service.AggregateServiceContext() {

      @Override
      public Class<?> getWorkflowAggregateClass() {

        return Note.class;

      }

      @Override
      public String getWorkflowModuleId() {

        return MODULE;

      }

      @Override
      public String getBpmnProcessId() {

        return PROCESS;

      }

      @Override
      public Object getWorkflowAggregateId(
          final Object workflowAggregate) {

        return ((Note) workflowAggregate).getId();

      }

      @Override
      public Object loadWorkflowAggregate(
          final Object workflowAggregateId) {

        return persistence.loadById(workflowAggregateId);

      }

      @Override
      public Object saveWorkflowAggregate(
          final Object workflowAggregate) {

        return persistence.save((Note) workflowAggregate);

      }

      @Override
      public io.vanillabp.integration.extension.spi.handler.ExtensionHandlers getHandlers() {

        return null;

      }

      @Override
      public io.vanillabp.integration.extension.spi.election.WorkflowElection getElection() {

        return null;

      }

    };

    assertNull(context.getAuditingId(noteOf("4711", "as it is")));
    assertEquals(
        "as it is",
        ((Note) context.loadWorkflowAggregate("4711", "rev-1")).getContent(),
        "the default ignores the auditing id");

  }

  @Test
  @DisplayName("An aggregate which is gone stays gone, and is not reported as an old state")
  public void anAggregateWhichIsGoneIsNotWarnedAbout() {

    final var persistence = new HistoryKeepingPersistence();
    final var testee = serviceOf(persistence);

    assertNull(testee.loadWorkflowAggregateById("4711", "rev-1"));
    assertTrue(
        warnings().isEmpty(),
        "there is nothing to fall back to, so there is nothing to say: "
            + warnings());

  }

}
