package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * What a BPMS answers about a task which VanillaBP thinks is still open. The question is asked
 * through an {@link OpenTaskProbe}. There are three answers, not two. The third one is the
 * reason why this is an enum and not a <code>boolean</code>.
 * <p>
 * A "no" and a "cannot say" must be told apart. A cancellation follows only on {@link #GONE}.
 * If a failed question counted as "gone", every open task of a workflow would be reported as
 * canceled whenever the engine has a short problem. An adapter whose API has no typed
 * exceptions cannot tell a refusal from an outage. It says so with {@link #CANNOT_SAY}
 * instead of guessing. Why the difference is part of the contract: see decision 74 in the
 * repository's DECISIONS.md.
 */
public enum TaskExistence {

  /**
   * The BPMS does not have this task any more. The core reports it to the application as
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED} and closes its record.
   * <p>
   * Answer it only if the BPMS really said so. If a task which the engine still holds is
   * reported as gone, the application acts on a cancellation while the workflow still waits.
   */
  GONE,

  /**
   * The BPMS still has this task. Nothing happens, and the next wake-up of that workflow
   * asks again.
   */
  STILL_THERE,

  /**
   * This adapter cannot say. The BPMS could not be reached, the answer was unclear, or the
   * API cannot tell a refusal from an outage. Nothing happens, exactly as for
   * {@link #STILL_THERE}. The two differ in what they mean, not in what follows. So an
   * adapter can stay honest, and it costs nothing.
   */
  CANNOT_SAY

}
