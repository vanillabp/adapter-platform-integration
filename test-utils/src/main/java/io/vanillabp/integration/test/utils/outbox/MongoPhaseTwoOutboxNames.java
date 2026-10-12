package io.vanillabp.integration.test.utils.outbox;

import io.vanillabp.integration.test.utils.ConstantOfAnotherModule;

/**
 * The names of the phase-two outbox on MongoDB, taken from the classes which declare
 * them.
 * <p>
 * The same idea as {@link PhaseTwoOutboxNames}, for the same reason. A test which reads
 * the outbox needs the name of a collection and the values of its status field. This
 * class keeps them out of the test. {@link ConstantOfAnotherModule} does the reading and
 * says why it uses reflection.
 * <p>
 * The collection names come from the core, which both platforms share. The three status
 * values are declared per platform, in a class each platform brings. So they are read
 * from whichever of the two is on the classpath. They are the same three strings either
 * way. A reader which picked one platform could not be used on the other.
 */
final class MongoPhaseTwoOutboxNames {

  /**
   * The core's outbox properties, which declare the default name of the collection and
   * the suffix the payload collection is named with.
   */
  private static final String OUTBOX_PROPERTIES = "io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties$MongoOutboxProperties";

  /**
   * The Quarkus store, which declares the three values of the status field.
   */
  private static final String QUARKUS_STORE = "io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutbox";

  /**
   * The document class of the Spring Boot store, which declares the same three values.
   */
  private static final String SPRING_BOOT_ENTRY = "io.vanillabp.integration.outbox.mongo.PhaseTwoOutboxEntry";

  private MongoPhaseTwoOutboxNames() {
  }

  /**
   * @return The collection VanillaBP writes its entries into when the application did
   *         not configure a name of its own
   */
  static String outboxCollection() {

    return ConstantOfAnotherModule.of(MongoPhaseTwoOutboxNames.class, OUTBOX_PROPERTIES, "DEFAULT_COLLECTION");

  }

  /**
   * The payload collection is named after the outbox collection, so a renamed outbox
   * takes its payloads along.
   *
   * @param outboxCollection The collection the entries lie in
   * @return The collection the payloads of those entries lie in
   */
  static String payloadCollectionOf(
      final String outboxCollection) {

    return outboxCollection + ConstantOfAnotherModule
        .of(MongoPhaseTwoOutboxNames.class, OUTBOX_PROPERTIES, "PAYLOAD_COLLECTION_SUFFIX");

  }

  /**
   * @return What the status field of a waiting entry holds
   */
  static String waitingStatus() {

    return statusOfThisPlatform("STATUS_OPEN");

  }

  /**
   * @return What the status field of a dispatched entry holds
   */
  static String dispatchedStatus() {

    return statusOfThisPlatform("STATUS_DONE");

  }

  /**
   * @return What the status field of a blocked entry holds
   */
  static String blockedStatus() {

    return statusOfThisPlatform("STATUS_BLOCKED");

  }

  /**
   * Asks whichever of the two platforms is on the classpath.
   *
   * @param fieldName The constant holding the value
   * @return What that constant holds
   * @throws IllegalStateException If neither platform's class can be loaded. Then the
   *           application under test runs no MongoDB outbox at all
   */
  private static String statusOfThisPlatform(
      final String fieldName) {

    return ConstantOfAnotherModule
        .ofAClassWhichMayBeMissing(MongoPhaseTwoOutboxNames.class, QUARKUS_STORE, fieldName)
        .or(
            () -> ConstantOfAnotherModule
                .ofAClassWhichMayBeMissing(MongoPhaseTwoOutboxNames.class, SPRING_BOOT_ENTRY, fieldName))
        .orElseThrow(
            () -> new IllegalStateException(
                """
                    Neither '%s' nor '%s' is on the classpath, so the values of the status field \
                    cannot be read! A test reading the MongoDB outbox runs against an application \
                    which has one of the two platform integrations."""
                    .formatted(QUARKUS_STORE, SPRING_BOOT_ENTRY)));

  }

}
