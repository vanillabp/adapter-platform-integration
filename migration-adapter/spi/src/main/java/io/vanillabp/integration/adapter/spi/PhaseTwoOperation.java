package io.vanillabp.integration.adapter.spi;

import java.util.Optional;

/**
 * The operation a {@link PhaseTwoCall} executes after the local transaction was
 * committed.
 * <p>
 * <strong>Persisted contract:</strong> The enum constant's name as well as the
 * idempotency-key derivation rules below are persisted by {@link PhaseTwoOutbox}
 * implementations. Never rename constants and never change a derivation rule for an
 * existing operation - outbox entries scheduled by a previous version of the
 * application must still dispatch and deduplicate correctly after an upgrade.
 * <p>
 * <strong>Idempotency-key derivation rules</strong> (parts joined by <code>|</code>):
 * <ul>
 * <li>{@link #START_WORKFLOW}:
 * <code>workflowModuleId|bpmnProcessId|workflowAggregateId</code> - a workflow is
 * started at most once per aggregate.</li>
 * <li>future <i>completeTask</i>/<i>cancelTask</i> operations will additionally
 * include the task id - the same task is completed at most once, but multiple tasks
 * of the same workflow may be completed.</li>
 * <li>future <i>correlateMessage</i> operations WITHOUT a correlation-id return
 * {@link Optional#empty()} - no deduplication is possible because the same message
 * may legitimately be correlated multiple times.</li>
 * </ul>
 */
public enum PhaseTwoOperation {

  /**
   * Phase two of starting a workflow - see
   * {@link MigratableProcessService#startWorkflowPhaseTwo(String, String, Object)}.
   */
  START_WORKFLOW {
    @Override
    public Optional<String> idempotencyKey(
        final PhaseTwoCall call) {
      return Optional.of(
          "%s|%s|%s".formatted(
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId()));
    }
  };

  /**
   * Derive the idempotency key of the given call according to the rules documented
   * on this enum.
   *
   * @param call The call to derive the key for
   * @return The idempotency key or {@link Optional#empty()} if the operation must
   *         not be deduplicated
   */
  public abstract Optional<String> idempotencyKey(
      PhaseTwoCall call);

}
