package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of the default {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
 * implementations (properties section <code>vanillabp.outbox</code>) - the single
 * source of truth for keys, defaults and documentation, used by every store VanillaBP
 * ships: the JDBC one both platforms run on a relational database, and the MongoDB one of
 * each platform.
 * <p>
 * The delivery log reads its store settings here as well - whether the store is created,
 * whether the MongoDB default is active, and the name of its table respectively collection.
 * What that log owns alone is the period a record is kept, which lives in
 * {@link DeliveryProperties}.
 */
@Getter
@Setter
@SuperBuilder
public class PhaseTwoOutboxProperties {

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: Lombok moves the
   * initializer of a field with a default into the builder, so a constructor which sets
   * nothing itself would hand an application which configures no outbox a poll interval of
   * <code>null</code> and no store sections at all.
   */
  public PhaseTwoOutboxProperties() {

    this(builder());

  }

  /**
   * The section an application writes these keys below: <code>vanillabp.outbox</code>.
   * Built from the prefix rather than written out, so a message names the section the way
   * the application has to spell it.
   */
  public static final String SECTION = MigrationAdapterProperties.PREFIX
      + ".outbox";

  /**
   * The longest a store's background poller sleeps while it owes nothing. Key
   * <code>vanillabp.outbox.poll-interval</code>.
   * <p>
   * It is a cap and not a rhythm. A poller asks its store when the earliest entry which
   * is still owed is due and sleeps until exactly that moment, so an entry is dispatched
   * at its due time however long this is, and an application waiting in a timer issues no
   * database command at all. What the cap covers is the one thing a sleeping node cannot
   * see: work a node wrote down and then DIED before dispatching, which nobody is waiting
   * for a notification about (decision 42 in the repository's DECISIONS.md). Raising it is
   * how an application buys the saving; lowering it back to seconds gives the saving away
   * and buys nothing else.
   * <p>
   * Ten seconds by default, which is the rhythm every VanillaBP application polled at
   * before the sleeping was there, so an application which sets nothing keeps the timing
   * it had.
   */
  @Builder.Default
  private Duration pollInterval = DEFAULT_POLL_INTERVAL;

  /**
   * The default of {@link #pollInterval}, which the startup message about what still keeps
   * a database awake compares against: a value moved away from it is an application asking
   * for the saving, and that is the moment to say what remains.
   */
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(10);

  /**
   * The distance to the FIRST retry after a failed dispatch. Every further attempt
   * doubles it until {@link #maxAttemptFrequency} is reached (see
   * {@link #attemptDelay(int)}). It is the length of a claim on an entry as well, which is
   * what {@link io.vanillabp.integration.adapter.migration.outbox.DispatchLease} renews
   * while a dispatch runs.
   * <p>
   * Key <code>vanillabp.outbox.attempt-frequency</code>, thirty seconds by default.
   */
  @Builder.Default
  private Duration attemptFrequency = Duration.ofSeconds(30);

  /**
   * The longest distance the growing backoff reaches. Five minutes, so a BPMS which
   * comes back is noticed within five minutes however long it was away.
   * <p>
   * Key <code>vanillabp.outbox.max-attempt-frequency</code>, five minutes by default.
   */
  @Builder.Default
  private Duration maxAttemptFrequency = Duration.ofMinutes(5);

  /**
   * After how many failed attempts an entry is blocked (not retried any longer).
   * Fifty of them, which with the two defaults above span an outage of about four
   * hours - a cluster upgrade rather than an exotic event. What ends up blocked is
   * then an entry which is broken rather than one whose BPMS was away for a while,
   * and it is the case an operator has to look at.
   * <p>
   * Key <code>vanillabp.outbox.block-after-attempts</code>, fifty attempts by default.
   */
  @Builder.Default
  private int blockAfterAttempts = 50;

  /**
   * The distance to the next attempt after a dispatch which failed, doubling per
   * attempt and capped at {@link #maxAttemptFrequency}. The first retry keeps
   * {@link #attemptFrequency}, because most failures are momentary and waiting
   * longer buys nothing there.
   * <p>
   * The stores VanillaBP owns compute their next attempt with this method, so the
   * curve is the same on every platform and on every persistence. An application which
   * kept gruelbox (<code>vanillabp.outbox.gruelbox.enabled</code>) gets the retry policy
   * of that library, which knows one fixed distance - the per-store table of the platform
   * pages owns that difference.
   *
   * @param attemptsSoFar The number of attempts already made, zero before the first
   *        retry
   * @return The distance to the next attempt
   */
  public Duration attemptDelay(
      final int attemptsSoFar) {

    if (attemptsSoFar <= 0) {
      return attemptFrequency;
    }
    // doubling in the exponent rather than in a loop, and bounded before it is
    // computed: 2^attempts overflows a long at 63 attempts, and blockAfterAttempts is
    // configurable
    if (attemptsSoFar >= 62) {
      return maxAttemptFrequency;
    }
    final var doubled = attemptFrequency.multipliedBy(1L << attemptsSoFar);
    return doubled.compareTo(maxAttemptFrequency) > 0 ? maxAttemptFrequency : doubled;

  }

  /**
   * How many entries an outbox of VanillaBP's own dispatches at the same time - the JDBC one
   * and the MongoDB one of each platform. Which of the threads takes an entry is decided by
   * the workflow aggregate, so two operations of one workflow keep the order they were
   * written in while operations of different workflows travel at the same time (see
   * {@link io.vanillabp.integration.adapter.migration.outbox.DispatchLanes}).
   * <p>
   * Four of them, which is small on purpose. Everything a dispatch does costs a database
   * connection and a call to the BPMS, so a large number here only moves the limit into
   * the connection pool, where it is harder to see. Raise it where the BPMS is slow
   * enough that the threads wait for it rather than for the database.
   * <p>
   * Key <code>vanillabp.outbox.dispatch-threads</code>, four threads by default. Fewer
   * than one is refused with a message naming the key.
   */
  @Builder.Default
  private int dispatchThreads = 4;

  /**
   * Whether the schema (table/collection) used to store outbox entries is created
   * automatically. Disable this if the database schema is managed manually (e.g. by
   * Flyway or Liquibase).
   * <p>
   * Key <code>vanillabp.outbox.create-schema</code>, <code>true</code> by default. The
   * records of processed task deliveries share it, because it is a setting of the store
   * rather than of the outbox.
   */
  @Builder.Default
  private boolean createSchema = true;

  /**
   * How long successfully dispatched entries (marked as DONE) are retained before
   * they are deleted asynchronously - what a retained entry buys is a dispatched
   * operation somebody can still look at during support, not a longer deduplication
   * window: that one ends with the dispatch (see
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
   * <p>
   * This is the OUTBOX half only. The records of processed task deliveries have a
   * retention of their own (<code>vanillabp.delivery.retention</code>), which defaults to
   * this number and is a correctness setting rather than an operational one: a delivery
   * arriving later than it finds no record and runs the business code a second time (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). The two were one property until
   * they were told apart (decision 24 in the repository's DECISIONS.md), which is why
   * shortening this one to keep the outbox table small used to shorten a correctness window
   * with the same hand.
   * <p>
   * Key <code>vanillabp.outbox.retention</code>, seven days by default.
   */
  @Builder.Default
  private Duration retention = DEFAULT_RETENTION;

  /**
   * The default of {@link #retention}, and therefore the default of
   * <code>vanillabp.delivery.retention</code>, which follows it where it is not set
   * itself. Seven days.
   */
  public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

  /**
   * Configuration of the JDBC default outbox, which both platforms run: Spring Boot on
   * the connection of its transaction manager, Quarkus on an Agroal connection of the
   * running JTA transaction. Both default outboxes (JDBC and MongoDB) may be active in
   * the same application - each aggregate is served by the outbox matching its
   * persistence.
   */
  @Builder.Default
  private JdbcOutboxProperties jdbc = new JdbcOutboxProperties();

  /**
   * Configuration of the MongoDB-based default outbox. Both default outboxes (JDBC
   * and MongoDB) may be active in the same application - each aggregate is served by
   * the outbox matching its persistence.
   */
  @Builder.Default
  private MongoOutboxProperties mongo = new MongoOutboxProperties();

  /**
   * When the housekeeping of the outbox runs and in which time zone (properties section
   * <code>vanillabp.outbox.housekeeping.*</code>).
   */
  @Builder.Default
  private HousekeepingProperties housekeeping = new HousekeepingProperties();

  /**
   * Refuses a configuration the housekeeping cannot run on - a window which is no window
   * and a time zone nobody knows - and warns about the one case where a correct-looking
   * configuration house-keeps at the wrong hour: a JVM on UTC without a zone of its own.
   *
   * @throws IllegalStateException Naming the key and the way out
   */
  public void validateHousekeeping() {

    if (housekeeping == null) {
      // a binder mapping an absent section onto null must not cost the defaults
      housekeeping = new HousekeepingProperties();
    }
    housekeeping.validate();

  }

  /**
   * Refuses a configuration in which two stores of one database would work on the same
   * table or collection.
   * <p>
   * Six names can be set, and nothing about them says that they have to differ. Two
   * stores sharing one place read, count and delete each other's rows, and what comes
   * out of that looks like a defect of the outbox rather than like a property somebody
   * wrote twice. The names are compared at startup because that is the last moment
   * before the first store works on the wrong place.
   * <p>
   * Both databases are checked whether their outbox is switched on or not. A name which
   * is wrong is wrong the moment somebody turns the store on, and telling them now
   * spares them the search then.
   */
  public void validateStoreNames() {

    if (jdbc == null) {
      // a binder mapping an absent section onto null must not cost the defaults
      jdbc = new JdbcOutboxProperties();
    }
    if (mongo == null) {
      // a binder mapping an absent section onto null must not cost the defaults
      mongo = new MongoOutboxProperties();
    }
    refuseTwoStoresInOnePlace(
        tablesOfTheRelationalStores(),
        name -> name.toUpperCase(Locale.ROOT),
        "table",
        WHAT_TO_DO_ABOUT_TWO_TABLES);
    refuseTwoStoresInOnePlace(
        collectionsOfTheMongoStores(),
        UnaryOperator.identity(),
        "collection",
        WHAT_TO_DO_ABOUT_TWO_COLLECTIONS);

  }

  /**
   * @return What each key of the relational stores names, the resolved name where the
   *         application set none
   */
  private Map<String, String> tablesOfTheRelationalStores() {

    final var tables = new LinkedHashMap<String, String>();
    tables.put(JdbcOutboxProperties.TABLE_PROPERTY, JdbcPhaseTwoOutboxStore.tableName(this));
    tables.put(JdbcOutboxProperties.PAYLOAD_TABLE_PROPERTY, JdbcPhaseTwoOutboxStore.payloadTableName(this));
    tables.put(JdbcOutboxProperties.DELIVERY_TABLE_PROPERTY, JdbcTaskDeliveryStore.tableName(this));
    tables.put(JdbcOutboxProperties.HOUSEKEEPING_TABLE_PROPERTY, jdbc.housekeepingTableName());
    return tables;

  }

  /**
   * @return What each key of the MongoDB stores names, the resolved name where the
   *         application set none
   */
  private Map<String, String> collectionsOfTheMongoStores() {

    final var collections = new LinkedHashMap<String, String>();
    collections.put(MongoOutboxProperties.COLLECTION_PROPERTY, mongo.getCollection());
    collections.put(MongoOutboxProperties.PAYLOAD_COLLECTION_PROPERTY, mongo.payloadCollectionName());
    collections.put(MongoOutboxProperties.DELIVERY_COLLECTION_PROPERTY, mongo.getDeliveryCollection());
    collections.put(MongoOutboxProperties.HOUSEKEEPING_COLLECTION_PROPERTY, mongo.getHousekeepingCollection());
    return collections;

  }

  /**
   * Throws where two of the given keys name the same place.
   *
   * @param namesByProperty What each key names, in the order the message lists them
   * @param asTheDatabaseReadsThem How the database decides that two names are one place.
   *          A relational database looks the table up in capitals whatever the
   *          application wrote, while MongoDB tells two spellings of a collection apart
   * @param whatIsShared The word for the place, for the message
   * @param whatToDo How the application gets out of it again
   * @throws IllegalStateException Naming both keys, what each of them says, and the way
   *           out
   */
  private static void refuseTwoStoresInOnePlace(
      final Map<String, String> namesByProperty,
      final UnaryOperator<String> asTheDatabaseReadsThem,
      final String whatIsShared,
      final String whatToDo) {

    final var properties = List.copyOf(namesByProperty.keySet());
    for (var first = 0; first < properties.size(); first++) {
      for (var second = first + 1; second < properties.size(); second++) {
        final var oneKey = properties.get(first);
        final var otherKey = properties.get(second);
        final var oneName = namesByProperty.get(oneKey);
        final var otherName = namesByProperty.get(otherKey);
        if ((oneName == null) || (otherName == null)) {
          continue;
        }
        if (!asTheDatabaseReadsThem
            .apply(oneName)
            .equals(asTheDatabaseReadsThem.apply(otherName))) {
          continue;
        }
        throw new IllegalStateException(
            """
                Two VanillaBP stores would work on the same %s:
                  '%s' names '%s'
                  '%s' names '%s'
                Each of them would then read, count and delete what the other wrote, and every \
                report about it would name the outbox rather than this configuration. %s"""
                .formatted(whatIsShared, oneKey, oneName, otherKey, otherName, whatToDo));
      }
    }

  }

  /**
   * How an application separates two relational stores again.
   */
  private static final String WHAT_TO_DO_ABOUT_TWO_TABLES = """
      Give each store a table of its own, or remove the key you did not mean: a payload table \
      nobody names follows the outbox table and carries '%s' behind it, and the deliveries lie \
      in '%s' where nobody names them either."""
      .formatted(JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME);

  /**
   * How an application separates three MongoDB stores again.
   */
  private static final String WHAT_TO_DO_ABOUT_TWO_COLLECTIONS = """
      Give each store a collection of its own, or remove the key you did not mean: a payload \
      collection nobody names follows the outbox collection and carries '%s' behind it, and the \
      deliveries lie in '%s' where nobody names them either."""
      .formatted(
          MongoOutboxProperties.PAYLOAD_COLLECTION_SUFFIX,
          MongoOutboxProperties.DEFAULT_DELIVERY_COLLECTION);

  /**
   * The keys of the JDBC default outbox (properties section
   * <code>vanillabp.outbox.jdbc.*</code>): whether it is built at all, and the two tables
   * it works on.
   */
  @Getter
  @Setter
  @SuperBuilder
  public static class JdbcOutboxProperties {

    /**
     * The empty section a configuration binder starts from, and the section an application
     * which writes nothing about the JDBC outbox gets, since the field holding it has this
     * as its default.
     * <p>
     * It asks the builder for the values, and that is not a detour: Lombok moves the
     * initializer of a field with a default into the builder, so a constructor which sets
     * nothing itself would switch the JDBC outbox off for every application which
     * configures no outbox.
     */
    public JdbcOutboxProperties() {

      this(JdbcOutboxProperties.builder());

    }

    /**
     * The key of {@link #table}: <code>vanillabp.outbox.jdbc.table</code>. It is a
     * constant because the message about two stores in one table names it and a test
     * reads the key from here instead of writing it a second time.
     */
    public static final String TABLE_PROPERTY = SECTION
        + ".jdbc.table";

    /**
     * The key of {@link #payloadTable}:
     * <code>vanillabp.outbox.jdbc.payload-table</code>, named by the same message as
     * {@link #TABLE_PROPERTY}.
     */
    public static final String PAYLOAD_TABLE_PROPERTY = SECTION
        + ".jdbc.payload-table";

    /**
     * The key of {@link #deliveryTable}:
     * <code>vanillabp.outbox.jdbc.delivery-table</code>, named by the same message as
     * {@link #TABLE_PROPERTY}.
     */
    public static final String DELIVERY_TABLE_PROPERTY = SECTION
        + ".jdbc.delivery-table";

    /**
     * The key of {@link #housekeepingTable}:
     * <code>vanillabp.outbox.jdbc.housekeeping-table</code>, named by the same message as
     * {@link #TABLE_PROPERTY}.
     */
    public static final String HOUSEKEEPING_TABLE_PROPERTY = SECTION
        + ".jdbc.housekeeping-table";

    /**
     * The name of the table the housekeeping of a relational outbox takes its lease in
     * where the application configures none.
     */
    public static final String DEFAULT_HOUSEKEEPING_TABLE = "VANILLABP_HOUSEKEEPING";

    /**
     * Whether the JDBC-based default outbox is created when a data source is
     * available. Disable it if the application defines its own
     * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} bean and the
     * default (including its store and background dispatcher) is unwanted.
     * <p>
     * Key <code>vanillabp.outbox.jdbc.enabled</code>, <code>true</code> by default.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * The name of the table storing outbox entries. Every outbox instance needs its
     * own store - two dispatchers polling the same table would compete and
     * double-dispatch. <code>null</code> means
     * <code>VANILLABP_PHASE_TWO_OUTBOX</code>, on both platforms. The table meant here
     * is the one VanillaBP writes itself. An application which kept gruelbox
     * (<code>vanillabp.outbox.gruelbox.enabled</code>) stores its entries in gruelbox'
     * own <code>TXNO_OUTBOX</code>, which this key does not rename, because that table
     * and its columns belong to the library.
     * <p>
     * Key <code>vanillabp.outbox.jdbc.table</code>, unset by default.
     */
    @Builder.Default
    private String table = null;

    /**
     * The name of the table storing the payloads of phase-two calls which carry one
     * (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}). One table per
     * outbox, for the reason the outbox itself has one: two applications sharing it
     * would house-keep each other's rows. <code>null</code> means the name of the
     * outbox table plus
     * {@link io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore#TABLE_NAME_SUFFIX},
     * so an application which renames the outbox renames the payloads with it.
     * <p>
     * Key <code>vanillabp.outbox.jdbc.payload-table</code>, unset by default.
     */
    @Builder.Default
    private String payloadTable = null;

    /**
     * The name of the table storing the records of processed task deliveries (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). The name lies in this
     * section because the STORE settings of the delivery log are the outbox' ones, the
     * way <code>vanillabp.outbox.create-schema</code> already is; what belongs to the log
     * alone is how long a record is kept
     * (<code>vanillabp.delivery.retention</code>). <code>null</code> means
     * {@link io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore#DEFAULT_TABLE_NAME},
     * and unlike the payload table this one does NOT follow a renamed outbox: it is a
     * store of its own, and a name derived from the outbox would rename it behind the
     * application's back.
     * <p>
     * An application which sets it applies the same name to
     * <code>io.vanillabp:vanillabp-schema</code>, through the changelog property
     * <code>vanillabp.delivery.table</code> - see decision 78 in the repository's
     * DECISIONS.md.
     * <p>
     * Key <code>vanillabp.outbox.jdbc.delivery-table</code>, unset by default.
     */
    @Builder.Default
    private String deliveryTable = null;

    /**
     * The name of the table the nightly housekeeping takes its lease in - one row per
     * store, holding who is house-keeping it and until when, so only one node of a
     * cluster measures its own work (see decision 91 in the repository's DECISIONS.md).
     * <code>null</code> means {@link #DEFAULT_HOUSEKEEPING_TABLE}. Like the delivery
     * table and unlike the payload table this one does NOT follow a renamed outbox: it
     * carries a row per store rather than belonging to one.
     * <p>
     * An application which sets it applies the same name to
     * <code>io.vanillabp:vanillabp-schema</code>, through the changelog property
     * <code>vanillabp.housekeeping.table</code> - see decision 78 in the repository's
     * DECISIONS.md.
     * <p>
     * Key <code>vanillabp.outbox.jdbc.housekeeping-table</code>, unset by default.
     */
    @Builder.Default
    private String housekeepingTable = null;

    /**
     * The table the housekeeping takes its lease in: the configured name where there is
     * one, and {@link #DEFAULT_HOUSEKEEPING_TABLE} otherwise. Read this instead of the
     * plain getter, which answers what the application wrote and is <code>null</code>
     * most of the time.
     *
     * @return The housekeeping table name
     */
    public String housekeepingTableName() {

      return housekeepingTable == null ? DEFAULT_HOUSEKEEPING_TABLE : housekeepingTable;

    }

  }

  /**
   * The keys of the MongoDB default outbox (properties section
   * <code>vanillabp.outbox.mongo.*</code>): whether it is built at all, and the
   * collections it and the delivery log work on.
   */
  @Getter
  @Setter
  @SuperBuilder
  public static class MongoOutboxProperties {

    /**
     * The empty section a configuration binder starts from, and the section an application
     * which writes nothing about the MongoDB outbox gets, since the field holding it has
     * this as its default.
     * <p>
     * It asks the builder for the values, and that is not a detour: Lombok moves the
     * initializer of a field with a default into the builder, so a constructor which sets
     * nothing itself would switch the MongoDB outbox off and leave both collections
     * unnamed.
     */
    public MongoOutboxProperties() {

      this(MongoOutboxProperties.builder());

    }

    /**
     * The key of {@link #collection}:
     * <code>vanillabp.outbox.mongo.collection</code>. It is a constant because the
     * message about two stores in one collection names it and a test reads the key from
     * here instead of writing it a second time.
     */
    public static final String COLLECTION_PROPERTY = SECTION
        + ".mongo.collection";

    /**
     * The key of {@link #payloadCollection}:
     * <code>vanillabp.outbox.mongo.payload-collection</code>, named by the same message
     * as {@link #COLLECTION_PROPERTY}.
     */
    public static final String PAYLOAD_COLLECTION_PROPERTY = SECTION
        + ".mongo.payload-collection";

    /**
     * The key of {@link #deliveryCollection}:
     * <code>vanillabp.outbox.mongo.delivery-collection</code>, named by the same message
     * as {@link #COLLECTION_PROPERTY}.
     */
    public static final String DELIVERY_COLLECTION_PROPERTY = SECTION
        + ".mongo.delivery-collection";

    /**
     * The name of the collection the entries go into where the application configures
     * none.
     */
    public static final String DEFAULT_COLLECTION = "vanillabp-phase-two-outbox";

    /**
     * What is appended to the name of the outbox collection to get the name of the
     * payload collection. It is written the way a MongoDB collection is written here,
     * in small letters with a hyphen, while the JDBC store appends
     * {@link io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore#TABLE_NAME_SUFFIX}
     * in the way a table is written. The two are the same idea in two spellings, so
     * do not pull them together into one string.
     */
    public static final String PAYLOAD_COLLECTION_SUFFIX = "-payloads";

    /**
     * The name of the collection the records of processed task deliveries go into where
     * the application configures none.
     */
    public static final String DEFAULT_DELIVERY_COLLECTION = "vanillabp-task-deliveries";

    /**
     * The key of {@link #housekeepingCollection}:
     * <code>vanillabp.outbox.mongo.housekeeping-collection</code>, named by the same
     * message as {@link #COLLECTION_PROPERTY}.
     */
    public static final String HOUSEKEEPING_COLLECTION_PROPERTY = SECTION
        + ".mongo.housekeeping-collection";

    /**
     * The name of the collection the housekeeping takes its lease in where the
     * application configures none.
     */
    public static final String DEFAULT_HOUSEKEEPING_COLLECTION = "vanillabp-housekeeping";

    /**
     * Whether the MongoDB-based default outbox is created when a MongoDB connection
     * is available. Disable it if the application defines its own
     * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} bean and the
     * default (including its store and background dispatcher) is unwanted.
     * <p>
     * Key <code>vanillabp.outbox.mongo.enabled</code>, <code>true</code> by default.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * The name of the collection storing outbox entries. Every outbox instance
     * needs its own store - two dispatchers polling the same collection would
     * compete and double-dispatch.
     * <p>
     * Key <code>vanillabp.outbox.mongo.collection</code>,
     * {@value #DEFAULT_COLLECTION} by default.
     */
    @Builder.Default
    private String collection = DEFAULT_COLLECTION;

    /**
     * The name of the collection storing the payloads of phase-two calls which carry
     * one (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}). One
     * collection per outbox, for the reason the outbox itself has one.
     * <code>null</code> means the name of the outbox collection plus
     * {@link #PAYLOAD_COLLECTION_SUFFIX}, so an application which renames the outbox
     * renames the payloads with it.
     * <p>
     * Key <code>vanillabp.outbox.mongo.payload-collection</code>, unset by default.
     */
    @Builder.Default
    private String payloadCollection = null;

    /**
     * The name of the collection storing the records of processed task deliveries (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). The name lies in this
     * section because the STORE settings of the delivery log are the outbox' ones, the
     * way <code>vanillabp.outbox.mongo.enabled</code> and
     * <code>vanillabp.outbox.create-schema</code> already are; what belongs to the log
     * alone is how long a record is kept
     * (<code>vanillabp.delivery.retention</code>).
     * <p>
     * Give it a name of its own where the database has naming rules, and keep it apart
     * from {@link #collection} and the payload collection: three stores sharing one
     * collection would read each other's documents.
     * <p>
     * Key <code>vanillabp.outbox.mongo.delivery-collection</code>,
     * {@value #DEFAULT_DELIVERY_COLLECTION} by default.
     */
    @Builder.Default
    private String deliveryCollection = DEFAULT_DELIVERY_COLLECTION;

    /**
     * The name of the collection the nightly housekeeping takes its lease in - one
     * document per store, holding who is house-keeping it and until when, so only one
     * node of a cluster measures its own work (see decision 91 in the repository's
     * DECISIONS.md). Like the delivery collection and unlike the payload collection it
     * does not follow a renamed outbox: it carries a document per store rather than
     * belonging to one.
     * <p>
     * Key <code>vanillabp.outbox.mongo.housekeeping-collection</code>,
     * {@value #DEFAULT_HOUSEKEEPING_COLLECTION} by default.
     */
    @Builder.Default
    private String housekeepingCollection = DEFAULT_HOUSEKEEPING_COLLECTION;

    /**
     * The collection both MongoDB stores write their payloads into: the configured
     * name where there is one, and otherwise the name of the outbox collection plus
     * {@link #PAYLOAD_COLLECTION_SUFFIX}. Read this instead of the plain getter, which
     * answers what the application wrote and is <code>null</code> most of the time.
     *
     * @return The payload collection name
     */
    public String payloadCollectionName() {

      return payloadCollection == null
          ? collection + PAYLOAD_COLLECTION_SUFFIX
          : payloadCollection;

    }

  }


  /**
   * When the outbox house-keeps and in which time zone (properties section
   * <code>vanillabp.outbox.housekeeping.*</code>).
   * <p>
   * The housekeeping deletes the entries whose retention passed and the payloads no entry
   * names any more. Both used to run at the end of every poll, which made every
   * application pay for them all day long. They run in a window at night now, and inside
   * that window the outbox works off as much as fits (see decision 91 in the repository's
   * DECISIONS.md).
   */
  @Getter
  @Setter
  @SuperBuilder
  public static class HousekeepingProperties {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory
        .getLogger(HousekeepingProperties.class);

    /**
     * The empty section a configuration binder starts from, and the section an application
     * which writes nothing about the housekeeping gets.
     * <p>
     * It asks the builder for the values, and that is not a detour: Lombok moves the
     * initializer of a field with a default into the builder, so a constructor which sets
     * nothing itself would hand an application which configures no outbox a window from
     * <code>null</code> to <code>null</code>.
     */
    public HousekeepingProperties() {

      this(HousekeepingProperties.builder());

    }

    /**
     * The key of {@link #start}: <code>vanillabp.outbox.housekeeping.start</code>.
     */
    public static final String START_PROPERTY = SECTION
        + ".housekeeping.start";

    /**
     * The key of {@link #end}: <code>vanillabp.outbox.housekeeping.end</code>.
     */
    public static final String END_PROPERTY = SECTION
        + ".housekeeping.end";

    /**
     * The key of {@link #zone}: <code>vanillabp.outbox.housekeeping.zone</code>. It is a
     * constant because the warning about a JVM on UTC names it, and a test reads the key
     * from here instead of writing it a second time.
     */
    public static final String ZONE_PROPERTY = SECTION
        + ".housekeeping.zone";

    /**
     * The environment variable which carries {@link #ZONE_PROPERTY}. The message about a
     * JVM on UTC names it rather than the property, because that message is read by
     * whoever installs the application on a server and their way in is the environment,
     * not a rebuild.
     */
    public static final String ZONE_ENVIRONMENT_VARIABLE = "VANILLABP_OUTBOX_HOUSEKEEPING_ZONE";

    /**
     * The default of {@link #start}: four in the morning, which on most installations is
     * the quietest hour of the day.
     */
    public static final LocalTime DEFAULT_START = LocalTime.of(4, 0);

    /**
     * The default of {@link #end}: five in the morning.
     */
    public static final LocalTime DEFAULT_END = LocalTime.of(5, 0);

    /**
     * The spellings which all mean UTC. A JVM standing on one of them without a zone
     * configured here is warned, because "four in the morning" then means four UTC,
     * which is almost never what somebody meant.
     */
    private static final List<String> MEANS_UTC = List.of("UTC", "ETC/UTC", "GMT", "ETC/GMT", "Z", "ZULU", "UCT");

    /**
     * When the window opens, in the zone below. Key
     * <code>vanillabp.outbox.housekeeping.start</code>, <code>04:00</code> by default.
     * <p>
     * A window which ends before it starts is read as crossing midnight, so
     * <code>23:00</code> to <code>01:00</code> is two hours and not a mistake.
     */
    @Builder.Default
    private LocalTime start = DEFAULT_START;

    /**
     * When the window closes. Key <code>vanillabp.outbox.housekeeping.end</code>,
     * <code>05:00</code> by default.
     * <p>
     * It is a deadline and not a promise: a batch which is running when the window closes
     * runs to its end, and the next one does not start. Widen the window where the meters
     * say that the outbox did not get through (see
     * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#HOUSEKEEPING_REMAINING}).
     */
    @Builder.Default
    private LocalTime end = DEFAULT_END;

    /**
     * The zone the two times above are read in. Key
     * <code>vanillabp.outbox.housekeeping.zone</code>, unset by default, which means the
     * zone of the JVM.
     * <p>
     * Written the way {@link ZoneId} spells one, for example <code>Europe/Vienna</code>.
     * <p>
     * A JVM standing on UTC without this key is warned once at the startup, because "four
     * in the morning" is then four UTC - see {@link #sayWhichZoneTheWindowRunsIn()}.
     */
    @Builder.Default
    private String zone = null;

    /**
     * The zone the window is read in: the configured one where there is one, and the
     * zone of the JVM otherwise.
     *
     * @return The zone
     */
    public ZoneId resolvedZone() {

      return (zone == null) || zone.isBlank()
          ? ZoneId.systemDefault()
          : ZoneId.of(zone.trim());

    }

    /**
     * Refuses a window which is none and a zone nobody knows, and warns where a JVM
     * stands on UTC and nobody gave the window a zone of its own.
     *
     * @throws IllegalStateException Naming the key and the way out
     */
    public void validate() {

      refuseAWindowWhichIsNone();
      refuseAZoneNobodyKnows();
      sayWhichZoneTheWindowRunsIn();

    }

    private void refuseAWindowWhichIsNone() {

      if ((start == null) || (end == null) || start.equals(end)) {
        throw new IllegalStateException(
            """
                The housekeeping window of the outbox is '%s' to '%s', which is no window! The \
                entries whose retention passed and the payloads no entry names are removed inside \
                it, so an application with no window never removes either. Remove both keys to get \
                the default of %s to %s, or write a start and an end which differ:
                  %s: '04:00'
                  %s: '05:00'
                A window whose end lies before its start crosses midnight and is allowed."""
                .formatted(start, end, DEFAULT_START, DEFAULT_END, START_PROPERTY, END_PROPERTY));
      }

    }

    private void refuseAZoneNobodyKnows() {

      if ((zone == null) || zone.isBlank()) {
        return;
      }
      try {
        ZoneId.of(zone.trim());
      } catch (final RuntimeException e) {
        throw new IllegalStateException(
            """
                The property '%s' is '%s', which is no time zone this JVM knows! Write it the way \
                the zone database spells it, for example 'Europe/Vienna' or 'America/New_York', or \
                remove the property to use the zone of the JVM."""
                .formatted(ZONE_PROPERTY, zone), e);
      }

    }

    /**
     * Says which zone the window really runs in, where a JVM stands on UTC and nobody
     * configured one.
     * <p>
     * A container runs on UTC unless somebody sets its zone, so "four in the morning"
     * becomes four UTC, which in most places is the middle of the working day. The
     * application would house-keep at that hour and nothing would say so, which is what
     * this line is for.
     * <p>
     * <strong>A warning and not a refusal.</strong> UTC is what a container ships with
     * and what a Kubernetes deployment normally has, so refusing the start would invent a
     * precondition rather than uncover a mistake. What the wrong zone costs is a sweep at
     * an hour nobody expected, which is surprise and a little load; what a refused start
     * costs is the deployment. The two are not the same size.
     * <p>
     * It is one of the notes VanillaBP means to collect into one box at the end of a
     * startup, so an operator reads what to look at in one place rather than a line per
     * check.
     * <p>
     * An application which really wants UTC writes it down, and then this says nothing.
     */
    private void sayWhichZoneTheWindowRunsIn() {

      if ((zone != null) && !zone.isBlank()) {
        return;
      }
      final var jvmZone = ZoneId.systemDefault();
      if (!MEANS_UTC.contains(jvmZone.getId().toUpperCase(Locale.ROOT))) {
        return;
      }
      logger
          .warn(
              """
                  The housekeeping of the VanillaBP outbox runs from {} to {} UTC, because this JVM \
                  stands on '{}' and no time zone was configured for it. In most places that is the \
                  middle of the working day rather than the quiet hour it is meant to be. Say which \
                  zone you mean, in one of two ways, neither of which needs a new build:
                    - set the zone of the container, for example TZ=Europe/Vienna, or
                    - set the zone of the housekeeping alone, for example {}=Europe/Vienna (property \
                  '{}').
                  Write 'UTC' there if UTC is what you mean, and this line goes away.""",
              start,
              end,
              jvmZone.getId(),
              ZONE_ENVIRONMENT_VARIABLE,
              ZONE_PROPERTY);

    }

  }

}
