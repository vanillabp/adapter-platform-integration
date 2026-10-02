package io.vanillabp.integration.adapter.migration.workflowtask;

import io.vanillabp.integration.spi.TaskDelivery;

/**
 * The key a delivery is written down under, plus the one thing a reader of that key cannot
 * see: whether a second delivery can ever be found under it.
 * <p>
 * The record of a delivery answers three questions and only one of them is the
 * deduplication: who holds the task, which kind of id it is, and whether this delivery was
 * answered before. An adapter which reports no delivery id gives up the third one and
 * nothing else, so the record is written either way and the key says which of the two kinds
 * of row it is (see {@link TaskDeliveryKey#of(String, String,
 * io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext)}).
 *
 * @param key What {@link TaskDelivery#deliveryKey()} holds
 * @param deduplicates Whether a repeated delivery of the same task yields this key again.
 *          Where it does, the key is read before the handler runs and the recorded outcome
 *          answers the repetition; where it does not, the key belongs to this one row and
 *          nothing is ever looked up by it
 */
public record TaskDeliveryIdentity(
                                   String key,
                                   boolean deduplicates) {

}
