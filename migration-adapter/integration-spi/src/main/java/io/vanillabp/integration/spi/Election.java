package io.vanillabp.integration.spi;

/**
 * Which BPMS executes a {@link PhaseOperation}, in both of its phases. An
 * application may run several BPMS at once. That is what the migration feature is. So
 * every operation has to say how the BPMS which serves it is found. The answer is the
 * same in phase one and at dispatch time.
 * <p>
 * Because of the election, an operation needs nothing else from the core. Whoever adds
 * an operation picks one of these. Then the core knows which question to ask, how long
 * it may wait for an answer, and how to word the failure when no BPMS answers. A new
 * value is added when a really new WAY of finding the BPMS appears. That is about ten
 * times rarer than a new operation.
 */
public enum Election {

  /**
   * The first adapter of the prioritized list starts the workflow. Nothing is asked,
   * because there is no workflow to ask about yet. The id of the elected adapter travels
   * with the outbox entry, so phase two reaches the very BPMS phase one chose. Before a
   * repeated dispatch starts a second workflow, it asks that adapter whether it already
   * started the workflow.
   */
  STARTS_THE_WORKFLOW,

  /**
   * The adapter whose BPMS still holds the parked asynchronous task. It is found by
   * asking every prioritized adapter about that task. No adapter id is persisted. The
   * question is asked again at dispatch time, because the BPMS may have moved on
   * between the two phases.
   */
  HOLDS_THE_TASK,

  /**
   * The adapter whose BPMS still holds the parked USER task. User-task ids live in a
   * namespace of their own. That is why this is a question of its own, and not
   * {@link #HOLDS_THE_TASK} with a flag.
   */
  HOLDS_THE_USER_TASK,

  /**
   * The adapter whose BPMS runs the workflow of the aggregate. It is found by asking
   * every prioritized adapter about that workflow. A remote BPMS may know the workflow
   * without showing it yet. So a dispatch waits for the visibility delay of the adapter,
   * and does not give up.
   */
  HOLDS_THE_WORKFLOW,

  /**
   * Every BPMS the workflow module was deployed to. Each gets an outbox entry of its
   * own, with its adapter id. Nothing is asked, and no aggregate is involved. This is
   * for what is broadcast, not addressed.
   */
  EVERY_DEPLOYED_BPMS,

  /**
   * Nothing is elected. The extension which contributed the operation dispatches it
   * itself, through its {@link PhaseOperationDispatch}. This is the default for an
   * extension operation. If an extension operation addresses a workflow like a core
   * operation does, the extension picks one of the elections above instead, and
   * contributes a handler per adapter.
   */
  OWN_DISPATCH

}
