package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;

import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import lombok.extern.slf4j.Slf4j;

/**
 * Which node house-keeps a MongoDB outbox store tonight, one document per store.
 * <p>
 * The relational counterpart is
 * {@link io.vanillabp.integration.adapter.migration.outbox.JdbcHousekeepingLease}, and
 * what both do is the same: a claim is taken by a write nobody else's write can match,
 * and it lasts until the window is over rather than being renewed while the work runs
 * (decision 91 in the repository's DECISIONS.md). The two are written twice because the
 * platform-neutral core carries no MongoDB driver, the way the payload store of each
 * platform is written twice.
 * <p>
 * The claim is an upsert. Where the document is held by somebody else the filter matches
 * nothing and the insert runs into the document's own id, which is MongoDB saying no.
 */
@Slf4j
public class MongoHousekeepingLease {

  private final MongoTemplate mongoTemplate;

  private final String collection;

  /**
   * Builds the claim of the stores in one MongoDB database.
   *
   * @param mongoTemplate The template the claims are written through
   * @param collection The collection the claims lie in
   *          (<code>vanillabp.outbox.mongo.housekeeping-collection</code>)
   */
  public MongoHousekeepingLease(
      final MongoTemplate mongoTemplate,
      final String collection) {

    this.mongoTemplate = mongoTemplate;
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

    final var free = Query
        .query(Criteria
            .where("_id")
            .is(store)
            .orOperator(
                Criteria.where("leasedUntil").is(null),
                Criteria.where("leasedUntil").lte(Instant.now())));
    final var claim = new Update()
        .set("leasedBy", owner)
        .set("leasedUntil", until);
    try {
      return mongoTemplate
          .findAndModify(
              free, claim, FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class,
              collection) != null;
    } catch (final DuplicateKeyException e) {
      // the document is there and held by somebody else, so the filter matched nothing
      // and the upsert ran into the document's own id. That is MongoDB saying no
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
      mongoTemplate
          .updateFirst(
              Query.query(Criteria.where("_id").is(store).and("leasedBy").is(owner)),
              new Update().unset("leasedBy").unset("leasedUntil"),
              collection);
    } catch (final RuntimeException e) {
      // the claim runs out by itself, so the next window is free either way
      log.debug("Could not release the housekeeping claim of the outbox store '{}'", store, e);
    }

  }

}
