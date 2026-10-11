package io.vanillabp.integration.test.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.extension.sample.SampleNoteContract;
import io.vanillabp.extension.sample.SampleNoteServiceFactory;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The other things an extension gets. It learns which BPMS holds a workflow right now. It
 * gets its own place in the configuration. And it gets the log which tells what a
 * workflow is waiting for.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = TestApplication.class)
public class ExtensionElectionAndConfigurationTest {

  private static final String MODULE = "extension-module";

  private static final String PROCESS = "DummyProcess";

  @Autowired
  private NotedWorkflowService workflowService;

  @Autowired
  private WorkflowElection election;

  @Autowired
  private MigrationAdapterProperties properties;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DeliveryLogUsingExtension deliveryLog;

  @Test
  @DisplayName("The extension learns which BPMS holds the workflow")
  public void theElectionAnswersTheExtension() {

    final var aggregate = transactionTemplate.execute(status -> {
      final var started = new NotedAggregate();
      started.setContent("elected");
      return workflowService
          .getProcessService()
          .startWorkflow(started);
    });

    assertEquals(
        "the-bpms",
        workflowService
            .getNoteService()
            .bpmsHolding(aggregate));
    assertEquals(
        "the-bpms",
        election.adapterIdOfWorkflow("extension-module", "DummyProcess", aggregate.getId()));

  }

  @Test
  @DisplayName("A workflow no BPMS knows is refused with a message naming the adapters asked")
  public void anUnknownWorkflowIsRefusedGuiding() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> election.adapterIdOfWorkflow(
            "extension-module",
            "DummyProcess",
            TestApplication.UNKNOWN_AGGREGATE_ID));
    assertTrue(failure.getMessage().contains("the-bpms"));

  }

  @Test
  @DisplayName("A BPMN process nobody serves is refused with the workflows this application has")
  public void anUnknownWorkflowProcessIsRefusedGuiding() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> election.adapterIdOfWorkflow("extension-module", "NoSuchProcess", 1L));
    assertTrue(failure.getMessage().contains("extension-module/DummyProcess"));

  }

  @Test
  @DisplayName("An extension is told which delivery log holds the records of the workflow")
  public void anExtensionResolvesTheDeliveryLogOfTheAggregate() {

    // the same shape as for the outbox and the transaction: the resolver is the bean. The
    // store which serves an aggregate follows the persistence VanillaBP resolved for it.
    // This application persists with JPA. So the answer is the JDBC store of the platform,
    // not just anything which is not null
    assertInstanceOf(JdbcTaskDeliveryLog.class, deliveryLog.logOf(NotedAggregate.class));

    // and it answers what an extension would show. Nothing is waiting here. The one user
    // task of this model has no @WorkflowTask method. So the adapter finishes its
    // notification itself, and no record is written (see decision 54 in the repository's
    // DECISIONS.md)
    assertTrue(deliveryLog.openTasksOf(NotedAggregate.class, MODULE, PROCESS, "1").isEmpty());

  }

  @Test
  @DisplayName("A workflow module overrides what the extension is configured with globally")
  public void theWorkflowModuleOverridesTheGlobalSetting() {

    assertEquals(
        "Servus",
        workflowService
            .getNoteService()
            .configuredGreeting());
    assertEquals(
        "Hello",
        properties.extensionProperty(null, SampleNoteContract.EXTENSION_ID, SampleNoteServiceFactory.GREETING));
    // a key the module does not set keeps the value of the global section
    assertEquals(
        "whatever",
        properties.extensionProperty("extension-module", SampleNoteContract.EXTENSION_ID, "unused"));
    assertNull(
        properties.extensionProperty("extension-module", "another-extension", SampleNoteServiceFactory.GREETING));

  }

  @Test
  @DisplayName("An extension setting is resolved from the most specific level which writes it")
  public void extensionSettingsResolveOverFourLevels() {

    final var greeting = SampleNoteServiceFactory.GREETING;

    assertEquals(
        "Hello",
        properties.resolveForExtension(null, null, null, SampleNoteContract.EXTENSION_ID, greeting));
    assertEquals(
        "Servus",
        properties.resolveForExtension(MODULE, "OtherProcess", null, SampleNoteContract.EXTENSION_ID, greeting));
    assertEquals(
        "Gruess Gott",
        properties.resolveForExtension(MODULE, PROCESS, "otherTask", SampleNoteContract.EXTENSION_ID, greeting));
    assertEquals(
        "Moin",
        properties.resolveForExtension(MODULE, PROCESS, "theTask", SampleNoteContract.EXTENSION_ID, greeting));

    // a key the more specific levels do not set keeps the value of the global section
    assertEquals(
        "whatever",
        properties.resolveForExtension(MODULE, PROCESS, "theTask", SampleNoteContract.EXTENSION_ID, "unused"));
    assertNull(
        properties.resolveForExtension(MODULE, PROCESS, "theTask", SampleNoteContract.EXTENSION_ID, "no-such-key"));

  }

}
