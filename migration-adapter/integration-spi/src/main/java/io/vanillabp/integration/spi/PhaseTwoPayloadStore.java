package io.vanillabp.integration.spi;

import java.time.Instant;

/**
 * Where the payload of a {@link PhaseTwoCall} lies while its outbox entry waits for its
 * dispatch. There is one row for each call which carries a payload, named by
 * {@link PhaseTwoCall#ARG_PAYLOAD_REFERENCE}.
 * <p>
 * A payload is the state the caller saw at the moment it planned the operation. For
 * VanillaBP it is just bytes. Whoever passes it decides the format, and nothing here
 * reads it. Why it lies next to the entry and not in it is decision 62 in the repository's
 * DECISIONS.md. In short: an outbox entry is a row of identifiers, and one of the four
 * stores VanillaBP ships owns neither its table nor the space in it.
 * <p>
 * A payload is written in the transaction which writes the outbox entry, so it becomes
 * visible exactly when the entry does. It is written AFTER the entry was accepted. So a
 * schedule which is discarded as a duplicate leaves nothing behind.
 * <p>
 * It is read at every dispatch attempt of that entry. That is the price of this form: one
 * lookup by key for each dispatched call with a payload, and none at all for a call
 * without one.
 * <p>
 * It is removed when the entry is finished, in the update which marks the entry as
 * dispatched, and one retention period later together with the dispatched entry. A
 * payload nothing points at any more is removed by age. That is what
 * {@link #removeOrphansOlderThan(Instant, int)} does.
 * <p>
 * The age never decides about a payload which an entry still names. An entry which waits,
 * or an entry which is blocked until somebody repairs it, keeps its bytes however long
 * that takes. A blocked entry exists so that it can be opened again, and it can only go
 * out with the state its caller planned it with.
 */
public interface PhaseTwoPayloadStore {

  /**
   * Writes the payload of a call, in the transaction which writes the outbox entry.
   *
   * @param call The call whose payload is written. It names the reference and, for
   *        whoever reads the store during support, the workflow it belongs to
   */
  void write(
      PhaseTwoCall call);

  /**
   * The payload stored under a reference.
   *
   * @param reference The reference persisted with the outbox entry
   * @return The bytes, or <code>null</code> if the store holds nothing for this
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
   * Removes payloads which were written before the given moment and which no outbox entry
   * names any more. Such a payload is an orphan: a write which was rolled back, or one
   * whose process died before it could write the entry.
   * <p>
   * Age alone is not enough to delete, because the retention counts at the ENTRY. The
   * payload of a blocked entry is older than the retention as soon as the repair takes
   * longer than that. Deleting it would take the bytes away from the very dispatch the
   * operator is preparing. So every store asks its own entries, and it asks them in the
   * database. Nothing of this call travels through the application, whatever the store
   * holds.
   * <p>
   * Each store decides how it asks, and the four VanillaBP ships ask in four ways. That is
   * why the signature looks like this. A store which can join its entries in one command
   * does so. A store whose entries lie in a table it does not own pays for that alone, and
   * the others do not pay with it.
   *
   * @param threshold Payloads written before this moment are candidates
   * @param maxEntries The most payloads this call may remove. It is an upper limit and not
   *        a target. A store removes what it finds, up to this many. The caller reads a
   *        full count as "there was more" and comes back
   * @return How many payloads were removed, never more than <code>maxEntries</code>
   */
  int removeOrphansOlderThan(
      Instant threshold,
      int maxEntries);

}
