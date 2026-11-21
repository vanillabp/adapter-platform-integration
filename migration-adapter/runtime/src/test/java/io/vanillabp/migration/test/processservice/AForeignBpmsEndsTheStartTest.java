package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.extension.spi.resolver.PhaseTwoOutboxResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The startup check which compares the identity of a BPMS with the one written down at an
 * earlier start. A new installation of a BPMS hands out the ids of the old one again, and a
 * record of the old one would then answer a delivery of the new one. So the start ends where
 * the BPMS changed while the delivery log still holds records of its adapter.
 * <p>
 * The delivery log is the JDBC store on H2, behind the interface, so the SQL of the identity
 * row is part of what is tested. Each test is one sequence of starts against one database.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AForeignBpmsEndsTheStartTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c8";

  private final JdbcTaskDeliveryStore store;

  private final TaskDeliveryLog deliveryLog;

  public AForeignBpmsEndsTheStartTest() {

    final var url = "jdbc:h2:mem:foreign-bpms-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
    final JdbcConnectionAccess connections = () -> DriverManager.getConnection(url, "sa", "");
    this.store = new JdbcTaskDeliveryStore(connections, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME);
    store.createSchemaIfNotExists();
    this.deliveryLog = new TaskDeliveryLog() {

      @Override
      public java.util.Optional<TaskDelivery> recordedDelivery(
          final String deliveryKey) {

        return store.recordedDelivery(deliveryKey);

      }

      @Override
      public boolean record(
          final TaskDelivery delivery) {

        return store.record(delivery);

      }

      @Override
      public Boolean holdsRecordsOf(
          final String adapterId,
          final String workflowModuleId,
          final String bpmnProcessId) {

        return store.holdsRecordsOf(adapterId, workflowModuleId, bpmnProcessId);

      }

      @Override
      public boolean replaceBpmsIdentity(
          final TaskDelivery identity) {

        return store.replaceBpmsIdentity(identity);

      }

    };

  }

  /**
   * One start of the application against a BPMS with the given identity, up to and including
   * the check under test.
   *
   * @param identity What the adapter reports, <code>null</code> for a BPMS which cannot say
   */
  private void startAgainst(
      final String identity) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    final var service = MigrationProcessService
        .<Object>forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(persistence())
        .processServices(List.of(adapterOf(identity)))
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return OUTBOX;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

          @Override
          public java.util.Collection<PhaseTwoOutbox> allStores() {

            return List.of(OUTBOX);

          }

        })
        .taskDeliveryLogResolver(
            new io.vanillabp.integration.extension.spi.resolver.TaskDeliveryLogResolver() {

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
    // the order of the platform integrations: the stores first, then the persisted state
    service.validatePhaseTwoOutboxAtStartup();
    service.validateTaskDeliveryLogAtStartup();
    service.validatePersistedAdapterIdsAtStartup();

  }

  private static final PhaseTwoOutbox OUTBOX = new PhaseTwoOutbox() {

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      return true;

    }

  };

  /** A remote adapter, which reports the given identity of its BPMS. */
  private static MigratableProcessService<Object> adapterOf(
      final String identity) {

    return new MigratableProcessService<>() {

      @Override
      public String identityOfTheBpms() {

        return identity;

      }

      @Override
      public String getAdapterId() {

        return ADAPTER;

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

  /** A record which the BPMS of the first start left behind, as a handler writes it. */
  private void aTaskWasDelivered() {

    store
        .record(
            new TaskDelivery(
                "c8|test-module|TestProcess|CREATED|2251799813685251", ADAPTER, MODULE, PROCESS, "4711", "2251799813685249", "awaitSignature", null, "2251799813685251", "COMPLETED", null, null, Instant
                    .now(), null));

  }

  private String identityWrittenDown() {

    return deliveryLog
        .bpmsIdentityOf(ADAPTER, MODULE, PROCESS)
        .map(TaskDelivery::workflowId)
        .orElse(null);

  }

  @Test
  @DisplayName("The first start writes the identity down, and the same BPMS starts again")
  public void theSameBpmsStartsAgain() {

    startAgainst("cluster-1");
    assertEquals("cluster-1", identityWrittenDown(), "a log without an identity takes the BPMS it meets");

    aTaskWasDelivered();
    assertDoesNotThrow(() -> startAgainst("cluster-1"), "a restart, or a restored backup, keeps its identity");
    assertEquals("cluster-1", identityWrittenDown());

  }

  @Test
  @DisplayName("Another BPMS ends the start while the log holds records of its adapter")
  public void anotherBpmsEndsTheStart() {

    startAgainst("cluster-1");
    aTaskWasDelivered();

    final var failure = assertThrows(IllegalStateException.class, () -> startAgainst("cluster-2"));

    final var message = failure.getMessage();
    assertTrue(message.contains("The BPMS behind adapter 'c8' is not the one this application met before"), message);
    assertTrue(message.contains("identity 'cluster-2'") && message.contains("wrote down 'cluster-1'"),
        "names both identities: "
            + message);
    assertTrue(message.contains("BPMN process 'TestProcess' of workflow module 'test-module'"), message);
    assertTrue(message.contains("'vanillabp.adapters.c8.*'"), "names the property keys of the address: "
        + message);
    assertTrue(message.contains("ADAPTER_ID 'c8'"), "says which records to delete: "
        + message);
    assertTrue(message.contains("c8|test-module|(bpms-identity)|TestProcess"),
        "names the row to delete to keep the records: "
            + message);
    assertEquals("cluster-1", identityWrittenDown(), "nothing is replaced while records are there");

  }

  @Test
  @DisplayName("Another BPMS is taken over where the log holds no records of its adapter")
  public void anotherBpmsIsTakenOverWithoutRecords() {

    startAgainst("cluster-1");

    assertDoesNotThrow(() -> startAgainst("cluster-2"), "nothing could collide");
    assertEquals("cluster-2", identityWrittenDown());

    aTaskWasDelivered();
    assertDoesNotThrow(() -> startAgainst("cluster-2"), "the records belong to the BPMS taken over");

  }

  @Test
  @DisplayName("A BPMS which cannot say who it is is not compared, and nothing is written down")
  public void aBpmsWhichCannotSayIsNotCompared() {

    aTaskWasDelivered();

    assertDoesNotThrow(() -> startAgainst(null));
    assertEquals(null, identityWrittenDown());

    // the first start which can say takes the BPMS it meets, like a log written before the
    // check existed
    assertDoesNotThrow(() -> startAgainst("cluster-1"));
    assertEquals("cluster-1", identityWrittenDown());

  }

}
