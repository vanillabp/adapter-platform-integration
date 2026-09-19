package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * What a BPMS answers about a task VanillaBP believes is still open, asked through an
 * {@link OpenTaskProbe}. Three answers and not two, and the third one is the reason this
 * enum exists rather than a <code>boolean</code>.
 * <p>
 * Why a "no" and a "cannot say" must be told apart: a cancellation follows only on
 * {@link #GONE}, and reading a failed question as "gone" would report every open task of a
 * workflow as canceled whenever the engine hiccups. An adapter whose API has no typed
 * exceptions cannot tell a refusal from an outage, and it says so with
 * {@link #CANNOT_SAY} instead of guessing. Why the distinction is part of the contract is
 * decision 74 in the repository's DECISIONS.md.
 */
public enum TaskExistence {

  /**
   * The BPMS does not have this task any more. The core reports it to the application as
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED} and closes its record.
   * <p>
   * Answer it only where the BPMS really said so. A task the engine still holds and reports
   * as gone is a cancellation the application acts on although the workflow waits on.
   */
  GONE,

  /**
   * The BPMS still has this task. Nothing happens, and the next wake-up of that workflow
   * asks again.
   */
  STILL_THERE,

  /**
   * This adapter cannot say: the BPMS was unreachable, the answer was ambiguous, or the API
   * gives no way to tell a refusal from an outage. Nothing happens, exactly as for
   * {@link #STILL_THERE} - the two differ in what they mean and not in what follows, which
   * is what keeps an adapter honest at no cost.
   */
  CANNOT_SAY

}
