package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.DeliveryRecords;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter id which the persisted state still names although the configuration does not.
 * <p>
 * VanillaBP persists the adapter id twice: an outbox entry of a START operation names the
 * adapter elected in phase one, and the delivery key of every record is built from the
 * delivering adapter. An id which is gone therefore has two readings, and both cost the
 * application something - a workflow which was persisted and never started, or a
 * <code>&#64;WorkflowTask</code> method which runs a second time. Both were silent until
 * the entry was dispatched respectively the task was delivered again.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PersistedAdapterIdTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /** Configures and prioritizes exactly one adapter. */
  private static MigrationAdapterProperties properties(
      final List<String> retiredAdapters) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("new-bpms", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("new-bpms"))
        .retiredAdapters(retiredAdapters)
        .build();
    properties.validateAndLink();
    return properties;

  }

  /**
   * A REMOTE adapter: it may repeat a delivery, which is what makes the platform resolve
   * a delivery log next to the outbox every application has - the state this check runs
   * in.
   */
  private static MigratableProcessService<Object> adapter() {

    return adapter(null);

  }

  /**
   * An adapter which reports the given number of open tasks - <code>null</code> being the
   * BPMS which cannot say.
   */
  private static MigratableProcessService<Object> adapter(
      final Long openTasks) {

    return adapter(openTasks, new java.util.HashMap<>());

  }

  /**
   * The same adapter, noting every question the start puts to it - see
   * {@link #theQuestionsDoNotDependOnWhatThePastLeftBehind()}.
   */
  private static MigratableProcessService<Object> adapter(
      final Long openTasks,
      final Map<String, Integer> questions) {

    return new MigratableProcessService<>() {

      @Override
      public Long openTaskCount(
          final String workflowModuleId,
          final String bpmnProcessId) {

        questions.merge("openTaskCount", 1, Integer::sum);
        return openTasks;

      }

      @Override
      public String getAdapterId() {

        return "new-bpms";

      }

      @Override
      public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Object>> phaseOperations() {
        return io.vanillabp.migration.test.TestPhaseOperations.doingNothing();
      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {

        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;

      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfWorkflow(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId) {

        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;

      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {

        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;

      }

      @Override
      public boolean deliversTasksAtLeastOnce() {

        return true;

      }

    };

  }

  /** The aggregate's persistence, reduced to what the constructor asks it. */
  private static AggregatePersistenceAware<Object> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {

        return Object.class;

      }

      @Override
      public Class<?> getAggregateIdType() {

        return String.class;

      }

    };

  }

  /** A store answering the given adapter ids, and nothing else. */
  private static PhaseTwoOutbox outboxWaitingFor(
      final Set<String> adapterIds) {

    return new PhaseTwoOutbox() {

      @Override
      public boolean schedule(
          final PhaseTwoCall call) {

        return true;

      }

      @Override
      public Set<String> adapterIdsOfPendingCalls(
          final String workflowModuleId,
          final String bpmnProcessId) {

        assertEquals(MODULE, workflowModuleId, "the store is asked about the calling process service");
        assertEquals(PROCESS, bpmnProcessId, "the store is asked about the calling process service");
        return adapterIds;

      }

    };

  }

  /** A log answering the given adapter ids for its open records, and nothing else. */
  private static TaskDeliveryLog logWithOpenTasksOf(
      final Set<String> adapterIds) {

    return logWithOpenTasksOf(adapterIds, null);

  }

  /**
   * A log which additionally answers whether it holds open records at all -
   * <code>null</code> being the store which cannot say.
   */
  private static TaskDeliveryLog logWithOpenTasksOf(
      final Set<String> adapterIds,
      final Boolean hasOpenRecords) {

    return new TaskDeliveryLog() {

      @Override
      public Boolean hasOpenRecords(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return hasOpenRecords;

      }

      @Override
      public Optional<TaskDelivery> recordedDelivery(
          final String deliveryKey) {

        return Optional.empty();

      }

      @Override
      public boolean record(
          final TaskDelivery delivery) {

        return true;

      }

      @Override
      public Set<String> adapterIdsOfOpenTasks(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return adapterIds;

      }

    };

  }

  /**
   * The WARNings the check logged. "Normal" logging is off during tests, so the appender is
   * attached to the class which logs (the same pattern the delivery tests use).
   */
  private static List<String> infoLoggedBy(
      final Runnable work) {

    return loggedBy(work, ch.qos.logback.classic.Level.INFO);

  }

  private static List<String> loggedBy(
      final Runnable work) {

    return loggedBy(work, ch.qos.logback.classic.Level.WARN);

  }

  private static List<String> loggedBy(
      final Runnable work,
      final ch.qos.logback.classic.Level level) {

    // INFO is asked for on its own: the check under test logs there, and a WARN of a
    // neighbouring check would otherwise count as its output
    final var onlyThatLevel = level == ch.qos.logback.classic.Level.INFO;

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    // the process service says some of this itself and lets its collaborator say the rest,
    // so both are listened to - which class a message comes from is not what is under test
    final var loggers = java.util.List
        .of(
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MigrationProcessService.class),
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DeliveryRecords.class));
    loggers.forEach(logger -> logger.addAppender(logWatcher));
    try {
      work.run();
    } finally {
      loggers.forEach(ch.qos.logback.classic.Logger::detachAndStopAllAppenders);
    }
    return logWatcher.list
        .stream()
        .filter(event -> onlyThatLevel
            ? (event.getLevel() == level)
            : event.getLevel().isGreaterOrEqual(level))
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  /**
   * A process service whose stores are already resolved - which is the state the check
   * runs in: the platform integration calls it after the outbox and the delivery-log
   * validations.
   */
  private static MigrationProcessService<Object> serviceWith(
      final MigrationAdapterProperties properties,
      final PhaseTwoOutbox outbox,
      final TaskDeliveryLog deliveryLog) {

    return serviceWith(properties, outbox, deliveryLog, adapter());

  }

  private static MigrationProcessService<Object> serviceWith(
      final MigrationAdapterProperties properties,
      final PhaseTwoOutbox outbox,
      final TaskDeliveryLog deliveryLog,
      final MigratableProcessService<Object> adapter) {

    final var service = MigrationProcessService
        .<Object>forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(persistence())
        .processServices(List
            .of(adapter))
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return outbox;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

        })
        .taskDeliveryLogResolver(
            new io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver() {

              @Override
              public TaskDeliveryLog resolveFor(
                  final Class<?> workflowAggregateClass) {

                return deliveryLog;

              }

              @Override
              public String remediesDescription() {

                return "";

              }

            })
        .build();
    // resolves both stores the way the platform's own validations do before this check
    service.validatePhaseTwoOutboxAtStartup();
    service.validateTaskDeliveryLogAtStartup();
    return service;

  }


  @Test
  @DisplayName("Open tasks the BPMS holds and the log has no record of are counted")
  public void openTasksNobodyRemembersAreCounted() {

    final var service = serviceWith(
        properties(List.of()),
        outboxWaitingFor(Set.of()),
        logWithOpenTasksOf(Set.of(), Boolean.FALSE),
        adapter(12L));

    final var output = String.join("\n", infoLoggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertTrue(output.contains("12 open task(s)"), output);
    assertTrue(output.contains("no record of"), output);
    assertTrue(output.contains("a SECOND time"), output);
    assertTrue(output.contains("keep the guards"), output);
    assertTrue(output.contains(MODULE), output);
    assertTrue(output.contains(PROCESS), output);

  }

  @Test
  @DisplayName("An application which has been running says nothing - its log holds open records")
  public void aRunningApplicationIsQuiet() {

    final var service = serviceWith(
        properties(List.of()),
        outboxWaitingFor(Set.of()),
        logWithOpenTasksOf(Set.of(), Boolean.TRUE),
        adapter(12L));

    assertTrue(
        infoLoggedBy(service::validatePersistedAdapterIdsAtStartup).isEmpty(),
        "a log which remembers open tasks is not the upgrade case");

  }

  @Test
  @DisplayName("A fresh installation says nothing - the BPMS holds nothing open")
  public void aFreshInstallationIsQuiet() {

    final var service = serviceWith(
        properties(List.of()),
        outboxWaitingFor(Set.of()),
        logWithOpenTasksOf(Set.of(), Boolean.FALSE),
        adapter(0L));

    assertTrue(infoLoggedBy(service::validatePersistedAdapterIdsAtStartup).isEmpty());

  }

  @Test
  @DisplayName("Neither side is guessed: a store or a BPMS which cannot say ends the check")
  public void anUnanswerableSideIsQuiet() {

    final var storeCannotSay = serviceWith(
        properties(List.of()),
        outboxWaitingFor(Set.of()),
        logWithOpenTasksOf(Set.of(), null),
        adapter(12L));
    assertTrue(
        infoLoggedBy(storeCannotSay::validatePersistedAdapterIdsAtStartup).isEmpty(),
        "a store which cannot say whether it holds records");

    final var bpmsCannotCount = serviceWith(
        properties(List.of()),
        outboxWaitingFor(Set.of()),
        logWithOpenTasksOf(Set.of(), Boolean.FALSE),
        adapter(null));
    assertTrue(
        infoLoggedBy(bpmsCannotCount::validatePersistedAdapterIdsAtStartup).isEmpty(),
        "a BPMS which cannot count what it holds open");

  }

  @Test
  @DisplayName("An id which entries are waiting for and which is not configured is named, with both readings")
  public void anUnconfiguredIdWithWaitingEntriesIsNamed() {

    final var service = serviceWith(
        properties(List.of()), outboxWaitingFor(Set.of("old-bpms")), logWithOpenTasksOf(Set.of()));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertTrue(output.contains("old-bpms"), output);
    assertTrue(output.contains("RENAMED"), output);
    assertTrue(output.contains("never started"), output);
    assertTrue(output.contains("retired-adapters"), output);
    assertTrue(output.contains(MODULE), output);
    assertTrue(output.contains(PROCESS), output);

  }

  @Test
  @DisplayName("An id which open task records belong to is named with what a rename costs there")
  public void anUnconfiguredIdWithOpenTasksIsNamed() {

    final var service = serviceWith(
        properties(List.of()), outboxWaitingFor(Set.of()), logWithOpenTasksOf(Set.of("old-bpms")));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertTrue(output.contains("old-bpms"), output);
    assertTrue(output.contains("still open"), output);
    assertTrue(output.contains("@WorkflowTask method a second time"), output);

  }

  @Test
  @DisplayName("A retired id is not named - the end of a migration stays quiet")
  public void aRetiredIdIsQuiet() {

    final var service = serviceWith(
        properties(List.of("old-bpms")), outboxWaitingFor(Set.of("old-bpms")), logWithOpenTasksOf(
            Set.of("old-bpms")));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertFalse(output.contains("RENAMED"), output);

  }

  @Test
  @DisplayName("A configured id is never named, however much is waiting for it")
  public void aConfiguredIdIsNeverNamed() {

    final var service = serviceWith(
        properties(List.of()), outboxWaitingFor(Set.of("new-bpms")), logWithOpenTasksOf(Set.of("new-bpms")));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertFalse(output.contains("new-bpms"), output);

  }

  @Test
  @DisplayName("A store which cannot say leaves the check silent instead of inventing an answer")
  public void aStoreWhichCannotSayIsSilent() {

    // the SPI default of both methods: an empty answer
    final var service = serviceWith(
        properties(List.of()), new PhaseTwoOutbox() {

          @Override
          public boolean schedule(
              final PhaseTwoCall call) {

            return true;

          }

        }, new TaskDeliveryLog() {

          @Override
          public Optional<TaskDelivery> recordedDelivery(
              final String deliveryKey) {

            return Optional.empty();

          }

          @Override
          public boolean record(
              final TaskDelivery delivery) {

            return true;

          }

        });

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertFalse(output.contains("RENAMED"), output);

  }

  /**
   * What a start asks the two stores and the BPMS, by name.
   * <p>
   * The three questions of this check are answered from tables and from a BPMS which grow
   * with everything the application ever did, so all three could get slower the longer it
   * runs. The number of them must not - see decision 19 in the repository's DECISIONS.md.
   *
   * @param openTasks What the BPMS reports as open
   * @param pendingIds The adapter ids the outbox is waiting for
   * @param openIds The adapter ids the delivery log holds open records for
   * @return The questions asked, by name
   */
  private static Map<String, Integer> questionsOfAStart(
      final Long openTasks,
      final Set<String> pendingIds,
      final Set<String> openIds,
      final Boolean hasOpenRecords) {

    final var questions = new java.util.TreeMap<String, Integer>();
    final var outbox = new PhaseTwoOutbox() {

      @Override
      public boolean schedule(
          final PhaseTwoCall call) {

        return true;

      }

      @Override
      public Set<String> adapterIdsOfPendingCalls(
          final String workflowModuleId,
          final String bpmnProcessId) {

        questions.merge("adapterIdsOfPendingCalls", 1, Integer::sum);
        return pendingIds;

      }

    };
    final var deliveryLog = new TaskDeliveryLog() {

      @Override
      public Boolean hasOpenRecords(
          final String workflowModuleId,
          final String bpmnProcessId) {

        questions.merge("hasOpenRecords", 1, Integer::sum);
        return hasOpenRecords;

      }

      @Override
      public Set<String> adapterIdsOfOpenTasks(
          final String workflowModuleId,
          final String bpmnProcessId) {

        questions.merge("adapterIdsOfOpenTasks", 1, Integer::sum);
        return openIds;

      }

      @Override
      public Optional<TaskDelivery> recordedDelivery(
          final String deliveryKey) {

        return Optional.empty();

      }

      @Override
      public boolean record(
          final TaskDelivery delivery) {

        return true;

      }

    };
    serviceWith(properties(List.of()), outbox, deliveryLog, adapter(openTasks, questions))
        .validatePersistedAdapterIdsAtStartup();
    return questions;

  }

  @Test
  @DisplayName("A start asks the same questions whether the past left nothing or a great deal behind")
  public void theQuestionsDoNotDependOnWhatThePastLeftBehind() {

    // both of them are an application which was upgraded from version 1, so neither log
    // remembers an open task yet - what differs is how much the past left behind
    final var onTheDayAfterTheUpgrade = questionsOfAStart(0L, Set.of(), Set.of(), Boolean.FALSE);
    final var twoYearsOfIt = questionsOfAStart(
        84_000L,
        Set.of("new-bpms"),
        Set.of("new-bpms"),
        Boolean.FALSE);

    assertEquals(
        onTheDayAfterTheUpgrade,
        twoYearsOfIt,
        "what a start asks belongs to the application's shape, never to its history");

  }

}
