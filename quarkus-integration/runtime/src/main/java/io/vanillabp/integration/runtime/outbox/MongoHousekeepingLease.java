package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.Date;
import java.util.function.Supplier;

import org.bson.Document;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import lombok.extern.slf4j.Slf4j;

/**
 * Which node does the housekeeping of a MongoDB outbox store tonight. There is one document
 * per store.
 * <p>
 * The relational counterpart is
 * {@link io.vanillabp.integration.adapter.migration.outbox.JdbcHousekeepingLease}. Both do
 * the same. A claim is taken by a write which no write of another node can match. It lasts
 * until the window is over. It is not renewed while the work runs (decision 91 in the
 * repository's DECISIONS.md). It is written once per platform, because the core, which does
 * not depend on a platform, has no MongoDB driver. The payload store is written once per
 * platform for the same reason.
 * <p>
 * The claim is an upsert. Where somebody else holds the document, the filter matches
 * nothing. Then the insert runs into the document's own id. That is MongoDB saying no.
 */
@Slf4j
public class MongoHousekeepingLease {

  /**
   * What MongoDB answers to a write which would create a second document with the same id.
   */
  private static final int DUPLICATE_KEY = 11000;

  private final Supplier<MongoCollection<Document>> collection;

  /**
   * Builds the claim of the stores in one MongoDB database.
   *
   * @param collection Where the claims live. It is a supplier for the same reason as the
   *          payload collection: the name is read lazily
   */
  public MongoHousekeepingLease(
      final Supplier<MongoCollection<Document>> collection) {

    this.collection = collection;

  }

  /**
   * Claims a store until a moment.
   *
   * @param store The store to claim
   * @param owner Which node is claiming
   * @param until When the claim runs out by itself
   * @return Whether this node holds the store now
   */
  public boolean claimUntil(
      final String store,
      final String owner,
      final Instant until) {

    try {
      final var result = collection
          .get()
          .updateOne(
              Filters
                  .and(
                      Filters.eq("_id", store),
                      Filters
                          .or(
                              Filters.exists("leasedUntil", false),
                              Filters.eq("leasedUntil", null),
                              Filters.lte("leasedUntil", Date.from(Instant.now())))),
              Updates
                  .combine(
                      Updates.set("leasedBy", owner),
                      Updates.set("leasedUntil", Date.from(until))),
              new UpdateOptions().upsert(true));
      return (result.getMatchedCount() > 0) || (result.getUpsertedId() != null);
    } catch (final MongoWriteException e) {
      if (e.getError().getCode() == DUPLICATE_KEY) {
        // The document is there and somebody else holds it. So the filter matched nothing,
        // and the upsert ran into the document's own id. That is MongoDB saying no.
        return false;
      }
      log.debug("Could not claim the housekeeping of the outbox store '{}'", store, e);
      return false;
    } catch (final RuntimeException e) {
      // The database could not be asked. Here this means the same: this node does no
      // housekeeping tonight, and the meters show the night which was missed.
      log.debug("Could not claim the housekeeping of the outbox store '{}'", store, e);
      return false;
    }

  }

  /**
   * Gives a claim back. A node which does not hold the store writes nothing. That is what
   * the condition on the owner is for.
   *
   * @param store The store to release
   * @param owner The node which claimed
   */
  public void release(
      final String store,
      final String owner) {

    try {
      collection
          .get()
          .updateOne(
              Filters.and(Filters.eq("_id", store), Filters.eq("leasedBy", owner)),
              Updates.combine(Updates.unset("leasedBy"), Updates.unset("leasedUntil")));
    } catch (final RuntimeException e) {
      // The claim runs out by itself, so the next window is free either way.
      log.debug("Could not release the housekeeping claim of the outbox store '{}'", store, e);
    }

  }

}
