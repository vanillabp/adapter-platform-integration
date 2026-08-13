package io.vanillabp.integration.outbox.gruelbox;

import com.gruelbox.transactionoutbox.AlreadyScheduledException;
import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Spring Boot applications using
 * JPA: delegates to a <a href="https://github.com/gruelbox/transaction-outbox">gruelbox
 * transaction-outbox</a> configured with Spring's transaction manager, so the outbox
 * entry is enlisted in the currently running local (JDBC) transaction.
 * <p>
 * The idempotency contract of {@link PhaseTwoOutbox} maps onto gruelbox's
 * <code>uniqueRequestId</code> mechanism: the {@link PhaseTwoCall#idempotencyKey()} is
 * used as unique request ID, enforced by a unique constraint of gruelbox's outbox
 * table. A duplicate schedule raises {@link AlreadyScheduledException} which is turned
 * into the contract's no-op (<code>false</code>). Successfully dispatched entries with
 * a unique request ID are retained by gruelbox until the configured retention
 * threshold passes (the contract's "DONE instead of delete").
 */
@RequiredArgsConstructor
@Slf4j
public class GruelboxPhaseTwoOutbox implements PhaseTwoOutbox {

  private final TransactionOutbox transactionOutbox;

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    try {
      transactionOutbox
          .with()
          .uniqueRequestId(call
              .idempotencyKey()
              .orElse(null))
          .schedule(GruelboxPhaseTwoDispatch.class)
          .dispatch(
              call.operation(),
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.adapterId(),
              PhaseTwoCall.serializeArgs(call.args()));
      return true;
    } catch (AlreadyScheduledException e) {
      log.debug(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "was already scheduled - skipping",
          call.operation(),
          call.bpmnProcessId(),
          call.workflowModuleId(),
          call.workflowAggregateId());
      return false;
    }

  }

}
