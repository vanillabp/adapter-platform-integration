package io.vanillabp.integration.runtime.persistence;

import java.util.IdentityHashMap;
import java.util.Map;

import org.bson.RawBsonDocument;
import org.bson.codecs.Codec;

import com.mongodb.client.MongoCollection;

import io.quarkus.arc.Arc;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Remembers in which form a workflow aggregate stored by MongoDB Panache was loaded, for as
 * long as the JTA transaction which loaded it runs. The MongoDB persistence implementations
 * ask before they save. If the document did not change, they do not write it (see
 * {@code DECISIONS.pending/989.md}).
 * <p>
 * The form is the document which the codec of the collection writes, the same codec which
 * Panache uses when it saves. It is encoded into bytes at once. So it compares by value, and
 * a later change of the object does not reach it.
 * <p>
 * The documents are kept per object, by identity, as a resource of the JTA transaction. So
 * one never reaches another delivery, which runs in a transaction of its own, and the
 * transaction manager drops them when the transaction ends. Without an active transaction
 * nothing is remembered, and every save writes.
 */
final class DocumentsAtLoad {

  /**
   * The key of the documents in the resources of the transaction. Each persistence has its
   * own key, so two of them do not see each other's documents.
   */
  private final Object resourceKey = new Object();

  private volatile TransactionSynchronizationRegistry transactionRegistry;

  /**
   * Remembers the document of the given aggregate in the running transaction.
   *
   * @param collection The collection the aggregate is stored in
   * @param aggregate The aggregate as it was loaded or written
   */
  void remember(
      final MongoCollection<?> collection,
      final Object aggregate) {

    final var documents = documentsOfTheRunningTransaction(true);
    if (documents != null) {
      documents.put(aggregate, documentOf(collection, aggregate));
    }

  }

  /**
   * Whether the given aggregate is known in the running transaction and its document did not
   * change since. The document is encoded right now, before anything is written.
   *
   * @param collection The collection the aggregate is stored in
   * @param aggregate The aggregate to save
   * @return <code>true</code> if writing it would not change anything
   */
  boolean isUnchanged(
      final MongoCollection<?> collection,
      final Object aggregate) {

    final var documents = documentsOfTheRunningTransaction(false);
    if (documents == null) {
      return false;
    }
    final var atLoad = documents.get(aggregate);
    return (atLoad != null) && atLoad.equals(documentOf(collection, aggregate));

  }

  @SuppressWarnings("unchecked")
  private static RawBsonDocument documentOf(
      final MongoCollection<?> collection,
      final Object aggregate) {

    final var codec = (Codec<Object>) collection
        .getCodecRegistry()
        .get(aggregate.getClass());
    return new RawBsonDocument(aggregate, codec);

  }

  @SuppressWarnings("unchecked")
  private Map<Object, RawBsonDocument> documentsOfTheRunningTransaction(
      final boolean create) {

    final var registry = transactionRegistry();
    if ((registry == null) || (registry.getTransactionStatus() != Status.STATUS_ACTIVE)) {
      return null;
    }
    final var known = (Map<Object, RawBsonDocument>) registry.getResource(resourceKey);
    if ((known != null) || !create) {
      return known;
    }
    final var documents = new IdentityHashMap<Object, RawBsonDocument>();
    registry.putResource(resourceKey, documents);
    return documents;

  }

  private TransactionSynchronizationRegistry transactionRegistry() {

    if (transactionRegistry == null) {
      final var registry = Arc
          .container()
          .instance(TransactionSynchronizationRegistry.class);
      transactionRegistry = registry.isAvailable()
          ? registry.get()
          : null;
    }
    return transactionRegistry;

  }

}
