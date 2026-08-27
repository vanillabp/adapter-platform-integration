package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.test.activation.ActivationAggregate;
import io.vanillabp.integration.test.activation.ActivationAggregatePersistence;
import io.vanillabp.integration.test.activation.ActivationAwarenessSource;
import io.vanillabp.integration.test.activation.ActivationProcessWiringSource;
import io.vanillabp.integration.test.activation.ActivationWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Which of the two retentions the JDBC delivery log runs with on Quarkus. The two numbers
 * are set to different values here, so a store reading the wrong one cannot pass by
 * accident.
 * <p>
 * They were one property until the outbound deduplication window ended with the dispatch.
 * Since then the outbox number decides how long a dispatched entry stays readable during
 * support, and the delivery number decides whether a late redelivery runs the business
 * code a second time.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeliveryRetentionWiringTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("delivery-retention/application.yaml", "application.yaml")
          .addClass(ActivationAggregate.class)
          .addClass(ActivationAggregatePersistence.class)
          .addClass(ActivationWorkflowService.class)
          .addClass(ActivationProcessWiringSource.class)
          .addClass(ActivationAwarenessSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/retention/ActivationProcess.bpmn")
          .addAsResource("delivery-retention/workflow-module", "META-INF/workflow-module"));

  @Inject
  JdbcTaskDeliveryLog deliveryLog;

  @Test
  @DisplayName("The delivery log runs with the delivery retention, not with the outbox one")
  public void theDeliveryLogReadsItsOwnRetention() {

    // the application sets 'vanillabp.outbox.retention: PT1H' and
    // 'vanillabp.delivery.retention: P30D', so reading the wrong number would show
    assertEquals(Duration.ofDays(30), deliveryLog.getDeliveryRetention());
    assertTrue(
        !Duration.ofHours(1).equals(deliveryLog.getDeliveryRetention()),
        "the outbox number is the one this must NOT be");

  }

}
