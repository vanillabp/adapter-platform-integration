package io.vanillabp.integration.spi;

import java.time.Instant;

/**
 * Where the payload of a {@link PhaseTwoCall} lies while its outbox entry waits for its
 * dispatch. One row per call which carries one, named by
 * {@link PhaseTwoCall#ARG_PAYLOAD_REFERENCE}.
 * <p>
 * A payload is the state the caller saw at the moment it planned the operation, and it
 * is bytes to VanillaBP: whoever passes it decides the format, and nothing here reads
 * it. Why it lies beside the entry instead of in it is decision 60 in the repository's
 * DECISIONS.md - in short, an outbox entry is a row of identifiers, and one of the four
 * stores VanillaBP ships owns neither its table nor the room in it.
 * <p>
 * A payload is written in the transaction which writes the outbox entry, so it becomes
 * visible exactly when the entry does. It is written AFTER the entry was accepted, so a
 * schedule discarded as a duplicate leaves nothing behind.
 * <p>
 * It is read at every dispatch attempt of that entry. That is the price of this form: one
 * lookup by key per dispatched call which carries a payload, and none at all for a call
 * which carries none.
 * <p>
 * It is removed where the entry is finished, in the update marking the entry dispatched.
 * What a crash between those two leaves behind is removed by
 * {@link #removeOlderThan(Instant)}, which every store runs with its own housekeeping.
 * Removal therefore errs towards keeping a payload too long and never towards losing one
 * a dispatch still needs.
 */
public interface PhaseTwoPayloadStore {

  /**
   * Writes the payload of a call, in the transaction which writes the outbox entry.
   *
   * @param call The call whose payload is written - it names the reference and, for
   *        whoever reads the store during support, the workflow it belongs to
   */
  void write(
      PhaseTwoCall call);

  /**
   * The payload stored under a reference.
   *
   * @param reference The reference persisted with the outbox entry
   * @return The bytes, or <code>null</code> where the store holds nothing for this
   *         reference
   */
  byte[] read(
      String reference);

  /**
   * Removes the payload of a dispatched entry.
   *
   * @param reference The reference persisted with the outbox entry
   */
  void remove(
      String reference);

  /**
   * Removes the payloads written before the given moment, whatever became of their
   * entries. It is the housekeeping which keeps the store from growing over the rows a
   * crash between the two writes left behind.
   *
   * @param threshold Payloads written before this moment are removed
   * @return The number of payloads removed
   */
  int removeOlderThan(
      Instant threshold);

}
