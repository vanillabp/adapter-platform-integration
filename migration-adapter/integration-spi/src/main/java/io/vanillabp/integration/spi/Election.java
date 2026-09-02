package io.vanillabp.integration.spi;

/**
 * Which BPMS executes a {@link PhaseOperation}, in both of its phases. An
 * application may run several BPMS at once - that is what the migration feature is -
 * so every operation has to say how the one which serves it is found, and the answer
 * is the same in phase one and at dispatch time.
 * <p>
 * The election is the reason an operation needs nothing else from the core: whoever
 * adds an operation picks one of these, and the core knows which probe to ask, how
 * long it may wait for an answer and how to phrase the failure when no BPMS answers.
 * A new value is added when a genuinely new WAY of finding the BPMS appears, which is
 * rarer than a new operation by an order of magnitude.
 */
public enum Election {

  /**
   * The first adapter of the prioritized list starts the workflow, nothing is probed
   * (there is no workflow to ask about yet), and the elected adapter's id travels with
   * the outbox entry so phase two reaches the very BPMS phase one chose. A repeated
   * dispatch asks that adapter whether it already started the workflow before starting
   * a second one.
   */
  STARTS_THE_WORKFLOW,

  /**
   * The adapter whose BPMS still holds the parked asynchronous task, found by asking
   * every prioritized adapter about that task. No adapter id is persisted: the entry is
   * asked again at dispatch time, because between the two phases the BPMS may have
   * moved on.
   */
  HOLDS_THE_TASK,

  /**
   * The adapter whose BPMS still holds the parked USER task. User-task ids live in a
   * namespace of their own, which is why this is a question of its own rather than
   * {@link #HOLDS_THE_TASK} with a flag.
   */
  HOLDS_THE_USER_TASK,

  /**
   * The adapter whose BPMS runs the workflow of the aggregate, found by asking every
   * prioritized adapter about that workflow. A remote BPMS may know the workflow
   * without showing it yet, so a dispatch waits out the adapter's visibility delay
   * instead of giving up.
   */
  HOLDS_THE_WORKFLOW,

  /**
   * Every BPMS the workflow module was deployed to, each getting an outbox entry of its
   * own carrying its adapter id. Nothing is probed and no aggregate is involved: this is
   * for what is broadcast rather than addressed.
   */
  EVERY_DEPLOYED_BPMS,

  /**
   * Nothing is elected - the extension which contributed the operation dispatches it
   * itself through its {@link PhaseOperationDispatch}. This is what an extension
   * operation is by default; an extension whose operation addresses a workflow the way
   * a core operation does picks one of the elections above instead and contributes a
   * handler per adapter.
   */
  OWN_DISPATCH

}
