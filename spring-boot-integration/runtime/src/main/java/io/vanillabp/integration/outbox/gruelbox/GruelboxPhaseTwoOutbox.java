package io.vanillabp.integration.outbox.gruelbox;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.adapter.spi.MigratableProcessServicePhaseTwo;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import lombok.RequiredArgsConstructor;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * JPA: delegates to a <a href="https://github.com/gruelbox/transaction-outbox">gruelbox
 * transaction-outbox</a> configured with Spring's transaction manager, so the outbox
 * entry is enlisted in the currently running local (JDBC) transaction.
 * <p>
 * The workflow aggregate's ID is converted to a {@link String} before scheduling since
 * gruelbox's <code>DefaultInvocationSerializer</code> only supports a whitelist of
 * types. The platform's {@link MigratableProcessServicePhaseTwo} bean converts it back
 * to the aggregate's ID type.
 */
@RequiredArgsConstructor
public class GruelboxPhaseTwoOutbox implements PhaseTwoOutbox {

  private final TransactionOutbox transactionOutbox;

  @Override
  public void schedule(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final Object workflowAggregateId) {

    transactionOutbox
        .schedule(GruelboxPhaseTwoDispatch.class)
        .startWorkflowPhaseTwo(
            workflowModuleId,
            bpmnProcessId,
            adapterId,
            workflowAggregateId == null ? null : workflowAggregateId.toString());

  }

}
