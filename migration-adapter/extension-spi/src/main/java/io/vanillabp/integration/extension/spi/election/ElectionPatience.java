package io.vanillabp.integration.extension.spi.election;

/**
 * How long an extension lets the election take, because only the extension knows what it
 * is doing when it asks.
 * <p>
 * A BPMS which answers from a read model its exporter feeds does not report a workflow
 * started moments ago. Waiting that out is right for one caller and wrong for the other,
 * and the difference is not how badly the answer is wanted but what the caller is holding
 * while it waits.
 */
public enum ElectionPatience {

  /**
   * Wait for the read model of the BPMS to catch up, up to the window the adapter names.
   * This is what a read wants: nobody repeats a read, so an answer it does not wait for
   * becomes an error the application sees.
   * <p>
   * It is also what every caller got before this could be said, which is why it is the
   * default of {@link WorkflowElection#adapterIdOfWorkflow(String, String, Object)}.
   */
  WAIT_FOR_VISIBILITY,

  /**
   * Ask every BPMS once and never sleep. This is what a caller inside a transaction of
   * the application wants: the wait would hold that transaction open, and with it the
   * database connection it took and the locks on the workflow aggregate. An extension
   * reporting a change out of a service task is in exactly that position, and a handful
   * of such reports empty a connection pool between them.
   * <p>
   * The price is the answer: a workflow whose BPMS has not made it findable yet is
   * reported as unknown, although it is running. An extension asking this way has to be
   * able to live with that, by reporting later or by leaving the report out.
   */
  ASK_ONCE

}
