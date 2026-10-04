package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.StartAggregate;
import io.vanillabp.integration.test.deployment.StartAggregatePersistence;
import io.vanillabp.integration.test.deployment.StartProcessStartMessageSource;
import io.vanillabp.integration.test.deployment.StartWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Acceptance test on Quarkus of the rule that <code>ProcessService#startWorkflowByMessage</code>
 * starts the process of its own process service and no other. The dummy adapter reports that
 * 'StartProcess' starts on the message 'OrderPlaced'. That message is accepted, and one the
 * model does not know is refused before anything is saved or planned.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AMessageStartsOnlyItsOwnProcessTest {

  private static final String PROCESS = "StartProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("bpms-initiated-start/application.yaml", "application.yaml")
          .addClass(StartAggregate.class)
          .addClass(StartAggregatePersistence.class)
          .addClass(StartWorkflowService.class)
          .addClass(StartProcessStartMessageSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/StartProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:a-message-starts-only-its-own-process;DB_CLOSE_DELAY=-1");

  @Inject
  ProcessService<StartAggregate> processService;

  @Inject
  StartAggregatePersistence persistence;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @Test
  @DisplayName("A message which starts the process of the process service is accepted")
  public void aMessageOfTheOwnProcessIsAccepted() throws Exception {

    startByMessage("accepted", "OrderPlaced");

    assertNotNull(persistence.stored("accepted"), "the aggregate was not saved");
    assertEquals(
        1,
        startsPlannedFor("accepted"),
        "the start was not planned");

  }

  @Test
  @DisplayName("A message which does not start the process of the process service is refused before phase one")
  public void aMessageOfAnotherProcessIsRefused() {

    final var refusal = assertThrows(
        IllegalArgumentException.class,
        () -> startByMessage("refused", "InvoicePaid"));

    assertTrue(
        refusal
            .getMessage()
            .contains("Message 'InvoicePaid' does not start BPMN process 'StartProcess' of workflow module "
                + "'test-module'"),
        refusal.getMessage());
    assertTrue(
        refusal
            .getMessage()
            .contains("The messages which start this process in the model adapter 'demo1' deployed are: "
                + "'OrderPlaced'."),
        refusal.getMessage());

    // refused before anything happened: nothing saved, nothing planned
    assertNull(persistence.stored("refused"));
    assertEquals(0, startsPlannedFor("refused"));

  }

  private long startsPlannedFor(
      final String aggregateId) {

    return PhaseTwoOutboxReader
        .of(dataSource)
        .entriesOf(PROCESS)
        .stream()
        .filter(entry -> aggregateId.equals(entry.aggregateId()))
        .count();

  }

  private void startByMessage(
      final String id,
      final String messageName) throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = new StartAggregate();
      aggregate.setId(id);
      processService.startWorkflowByMessage(aggregate, messageName);
      userTransaction.commit();
    } catch (final RuntimeException e) {
      userTransaction.rollback();
      throw e;
    }

  }

}
