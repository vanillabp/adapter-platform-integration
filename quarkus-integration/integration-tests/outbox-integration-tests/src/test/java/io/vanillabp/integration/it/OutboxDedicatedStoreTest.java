package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.DedicatedOutbox;
import io.vanillabp.integration.test.DedicatedOutboxAware;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Per-aggregate outbox attribution (story 26i): a {@code PhaseTwoOutboxAware} bean
 * routes the aggregate's phase-two calls to an application-defined outbox - the JDBC
 * default (also present in the container) stays untouched. With TWO outbox beans in
 * the container this attribution is also what resolves the ambiguity at startup.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxDedicatedStoreTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(DedicatedOutbox.class)
          .addClass(DedicatedOutboxAware.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // separate database: the module's other tests share the class-level H2 URL
      .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-dedicated-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  DedicatedOutbox dedicatedOutbox;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  private long countDefaultTableEntries() throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement
            .executeQuery("SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX")) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  @Test
  @DisplayName("The aggregate's phase-two call is routed to the dedicated outbox only")
  public void phaseTwoCallIsRoutedToTheDedicatedOutbox() throws Exception {

    userTransaction.begin();
    final Aggregate attachedAggregate;
    try {
      attachedAggregate = workflowService.startWorkflow("dedicated-store-test");
    } finally {
      userTransaction.commit();
    }
    assertNotNull(attachedAggregate.getId());

    // the PhaseTwoOutboxAware bean routed the call to the dedicated outbox ...
    assertEquals(1, dedicatedOutbox.getScheduledCalls().size());
    assertEquals(
        attachedAggregate.getId().toString(),
        dedicatedOutbox
            .getScheduledCalls()
            .getFirst()
            .workflowAggregateId());

    // ... and the JDBC default (also present in the container) stays untouched
    assertEquals(0, countDefaultTableEntries());

  }

}
