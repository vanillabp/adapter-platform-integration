package io.vanillabp.integration.spi;

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
 * <li>{@link #COMPLETE_TASK} / {@link #CANCEL_TASK}:
 * <code>workflowModuleId|bpmnProcessId|workflowAggregateId|taskId</code> - the same
 * task is completed (or canceled) at most once, but multiple tasks of the same
 * workflow may be completed. The BPMN error code of a cancellation is NOT part of
 * the key (it is carried in {@link PhaseTwoCall#args()}).</li>
 * <li>future <i>correlateMessage</i> operations WITHOUT a correlation-id return
 * {@link Optional#empty()} - no deduplication is possible because the same message
 * may legitimately be correlated multiple times.</li>
 * </ul>
 */
public enum PhaseTwoOperation {

  /**
   * Phase two of starting a workflow - see
   * {@code MigratableProcessService#startWorkflowPhaseTwo}.
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
  },

  /**
   * Phase two of completing an asynchronous task - see
   * {@code MigratableProcessService#completeTaskPhaseTwo}. The task ID travels in
   * {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID}. No adapter
   * ID is persisted - the executing adapter is elected at dispatch time by probing
   * the prioritized adapters.
   */
  COMPLETE_TASK {
    @Override
    public Optional<String> idempotencyKey(
        final PhaseTwoCall call) {
      return Optional.of(
          "%s|%s|%s|%s".formatted(
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.args().get(PhaseTwoCall.ARG_TASK_ID)));
    }
  },

  /**
   * Phase two of canceling an asynchronous task by BPMN error - see
   * {@code MigratableProcessService#cancelTaskPhaseTwo}. The task ID and the BPMN
   * error code travel in {@link PhaseTwoCall#args()} under
   * {@link PhaseTwoCall#ARG_TASK_ID} / {@link PhaseTwoCall#ARG_BPMN_ERROR_CODE};
   * only the task ID is part of the idempotency key.
   */
  CANCEL_TASK {
    @Override
    public Optional<String> idempotencyKey(
        final PhaseTwoCall call) {
      return Optional.of(
          "%s|%s|%s|%s".formatted(
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.args().get(PhaseTwoCall.ARG_TASK_ID)));
    }
  },

  /**
   * Phase two of completing a USER task - see
   * {@code MigratableProcessService#completeUserTaskPhaseTwo}. Same shape as
   * {@link #COMPLETE_TASK} (task ID in {@link PhaseTwoCall#ARG_TASK_ID}, no
   * adapter ID persisted).
   */
  COMPLETE_USER_TASK {
    @Override
    public Optional<String> idempotencyKey(
        final PhaseTwoCall call) {
      return Optional.of(
          "%s|%s|%s|%s".formatted(
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.args().get(PhaseTwoCall.ARG_TASK_ID)));
    }
  },

  /**
   * Phase two of canceling a USER task by BPMN error - see
   * {@code MigratableProcessService#cancelUserTaskPhaseTwo}. Same shape as
   * {@link #CANCEL_TASK}.
   */
  CANCEL_USER_TASK {
    @Override
    public Optional<String> idempotencyKey(
        final PhaseTwoCall call) {
      return Optional.of(
          "%s|%s|%s|%s".formatted(
              call.workflowModuleId(),
              call.bpmnProcessId(),
              call.workflowAggregateId(),
              call.args().get(PhaseTwoCall.ARG_TASK_ID)));
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
