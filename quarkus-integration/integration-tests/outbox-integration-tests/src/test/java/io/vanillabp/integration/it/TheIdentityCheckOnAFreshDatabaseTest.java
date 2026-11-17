package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An application starts for the first time on an empty database, and its adapter reports
 * the identity of its BPMS. A Camunda 8 adapter does that. The identity is compared with
 * the one in the delivery log while the application starts. So the table of the delivery
 * log has to exist before that question is asked, and VanillaBP creates it itself by
 * default.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheIdentityCheckOnAFreshDatabaseTest {

  private static final String DELIVERY_TABLE = "VANILLABP_TASK_DELIVERY";

  private static final String THE_BPMS = "cluster-a";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(AnIdentifiedBpms.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // a database of its own: the module's other tests created the tables in theirs
      .overrideRuntimeConfigKey(
          "quarkus.datasource.jdbc.url", "jdbc:h2:mem:identity-check-on-a-fresh-database;DB_CLOSE_DELAY=-1");

  @Inject
  DataSource dataSource;

  @Test
  @DisplayName("The first start on an empty database writes down the identity of the BPMS")
  public void theFirstStartWritesDownTheIdentity() throws Exception {

    final var identities = identitiesWrittenDown();
    assertFalse(
        identities.isEmpty(),
        "the start has to write down the identity of the BPMS");
    assertTrue(
        identities.stream().allMatch(THE_BPMS::equals),
        "the identity written down is the one the adapter reported: "
            + identities);

  }

  private List<String> identitiesWrittenDown() throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("SELECT WORKFLOW_ID FROM "
            + DELIVERY_TABLE
            + " WHERE RECORD_KIND = 'BPMS_IDENTITY'"); var resultSet = statement.executeQuery()) {
      final var identities = new ArrayList<String>();
      while (resultSet.next()) {
        identities.add(resultSet.getString(1));
      }
      return identities;
    }

  }

  /**
   * Makes the double report the identity of a BPMS, as a Camunda 8 adapter does. It knows
   * nothing about tasks, so the double's own answers stay.
   */
  @ApplicationScoped
  public static class AnIdentifiedBpms implements DummyTaskAwarenessSource {

    @Override
    public WorkflowAwareness awarenessOfTask(
        final String adapterId,
        final Object workflowAggregateId,
        final String taskId) {

      return null;

    }

    @Override
    public String identityOfTheBpms(
        final String adapterId) {

      return THE_BPMS;

    }

  }

}
