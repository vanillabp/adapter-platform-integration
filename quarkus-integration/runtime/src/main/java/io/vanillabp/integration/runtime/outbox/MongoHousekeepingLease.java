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
 * Which node house-keeps a MongoDB outbox store tonight, one document per store.
 * <p>
 * The relational counterpart is
 * {@link io.vanillabp.integration.adapter.migration.outbox.JdbcHousekeepingLease}, and
 * what both do is the same: a claim is taken by a write nobody else's write can match,
 * and it lasts until the window is over rather than being renewed while the work runs
 * (decision 91 in the repository's DECISIONS.md). It is written once per platform because
 * the platform-neutral core carries no MongoDB driver, the way the payload store of each
 * platform is written once per platform.
 * <p>
 * The claim is an upsert. Where the document is held by somebody else the filter matches
 * nothing and the insert runs into the document's own id, which is MongoDB saying no.
 */
@Slf4j
public class MongoHousekeepingLease {

  /**
   * What MongoDB answers a write which would produce a second document of the same id.
   */
  private static final int DUPLICATE_KEY = 11000;

  private final Supplier<MongoCollection<Document>> collection;

  /**
   * Builds the claim of the stores in one MongoDB database.
   *
   * @param collection Where the claims live - a supplier for the reason the payload
   *          collection is one: the name is read lazily
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
        // the document is there and held by somebody else, so the filter matched nothing
        // and the upsert ran into the document's own id. That is MongoDB saying no
        return false;
      }
      log.debug("Could not claim the housekeeping of the outbox store '{}'", store, e);
      return false;
    } catch (final RuntimeException e) {
      // the database could not be asked, which here means the same: this node does not
      // house-keep tonight, and the meters show the night which was missed
      log.debug("Could not claim the housekeeping of the outbox store '{}'", store, e);
      return false;
    }

  }

  /**
   * Gives a claim back. A node which does not hold the store writes nothing, which is
   * what the condition over the owner is for.
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
      // the claim runs out by itself, so the next window is free either way
      log.debug("Could not release the housekeeping claim of the outbox store '{}'", store, e);
    }

  }

}
