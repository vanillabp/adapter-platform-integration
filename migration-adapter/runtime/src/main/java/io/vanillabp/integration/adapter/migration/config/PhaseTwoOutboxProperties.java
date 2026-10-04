package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;
import java.time.Instant;
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
public class PhaseTwoOutboxProperties {

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: every default of this
   * class stands on the builder as well as on the field, and both ways into an object
   * end here. A constructor which set the fields itself would have to repeat every
   * default, and the first one somebody forgets would hand an application which
   * configures no outbox a poll interval of <code>null</code> and no store sections at
   * all.
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
  private Duration attemptFrequency = Duration.ofSeconds(30);

  /**
   * The key of {@link #attemptFrequency}:
   * <code>vanillabp.outbox.attempt-frequency</code>. It is a constant because the
   * message about an entry which is dispatched again names it.
   */
  public static final String ATTEMPT_FREQUENCY_PROPERTY = SECTION
      + ".attempt-frequency";

  /**
   * The longest distance the growing backoff reaches. Five minutes, so a BPMS which
   * comes back is noticed within five minutes however long it was away.
   * <p>
   * Key <code>vanillabp.outbox.max-attempt-frequency</code>, five minutes by default.
   */
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
  private int blockAfterAttempts = 50;

  /**
   * The key of {@link #blockAfterAttempts}:
   * <code>vanillabp.outbox.block-after-attempts</code>. It is a constant because the
   * message about an entry which could not be blocked names it.
   */
  public static final String BLOCK_AFTER_ATTEMPTS_PROPERTY = SECTION
      + ".block-after-attempts";

  /**
   * How long an entry may wait for a BPMS which does not report its workflow yet, counted
   * from the moment the entry was written. After that the entry is blocked.
   * <p>
   * Waiting for a read model is not a failure, so it uses no attempts: an adapter which
   * answers with {@link io.vanillabp.integration.spi.PhaseTwoRetryLater} gets its entry back
   * after the window it named, and <code>ATTEMPTS</code> stays as it was. Counting those
   * answers blocked an entry after fifty windows, which is about eight minutes of a stopped
   * Camunda 8 exporter, while a database which was away for hours blocked nothing. This
   * setting is what ends the wait instead.
   * <p>
   * Key <code>vanillabp.outbox.wait-for-visibility-at-most</code>. Not set by default, and
   * then it is the time the attempts of {@link #blockAfterAttempts} take with the growing
   * backoff, see {@link #waitForVisibilityAtMost()}: about four hours with the defaults, so
   * a stopped read model is given as long as a BPMS which is away. See decision 113 in the
   * repository's DECISIONS.md.
   */
  private Duration waitForVisibilityAtMost = null;

  /**
   * The key of {@link #waitForVisibilityAtMost}:
   * <code>vanillabp.outbox.wait-for-visibility-at-most</code>. It is a constant because the
   * message about an entry which waited too long names it.
   */
  public static final String WAIT_FOR_VISIBILITY_AT_MOST_PROPERTY = SECTION
      + ".wait-for-visibility-at-most";

  /**
   * How long an entry may wait for a BPMS which does not report its workflow yet: the value
   * of <code>vanillabp.outbox.wait-for-visibility-at-most</code> where it is set, and
   * otherwise the time the attempts take until an entry which keeps failing is blocked.
   * <p>
   * That time is the sum of the distances between the attempts. The first attempt runs at
   * once, and the entry is blocked when attempt number <code>block-after-attempts</code>
   * failed, so there are <code>block-after-attempts - 1</code> distances. With the defaults
   * these are 30 seconds, 1, 2 and 4 minutes, and 45 times 5 minutes, which is 3 hours and
   * 52.5 minutes.
   *
   * @return How long an entry may wait, counted from the moment it was written
   */
  public Duration waitForVisibilityAtMost() {

    if (waitForVisibilityAtMost != null) {
      return waitForVisibilityAtMost;
    }
    var span = Duration.ZERO;
    for (var attempt = 0; attempt < blockAfterAttempts - 1; attempt++) {
      span = span.plus(attemptDelay(attempt));
    }
    return span;

  }

  /**
   * Whether an entry has waited for its BPMS long enough to be blocked.
   * <p>
   * Every store asks this when a dispatch is answered with
   * {@link io.vanillabp.integration.spi.PhaseTwoRetryLater}, so the rule is the same on all
   * of them. The clock starts when the entry was written. A younger call which replaced the
   * entry starts it again, because the replacement writes that moment anew.
   *
   * @param writtenAt When the entry was written, <code>null</code> where the store does not
   *          know, which never counts as waited long enough
   * @param now The moment the dispatch was answered
   * @return Whether the entry is to be blocked instead of being dispatched again
   */
  public boolean hasWaitedForVisibilityLongEnough(
      final Instant writtenAt,
      final Instant now) {

    if (writtenAt == null) {
      return false;
    }
    return !now.isBefore(writtenAt.plus(waitForVisibilityAtMost()));

  }

  /**
   * Refuses a time for waiting on a read model which is zero or negative. Such a value would
   * block every entry the first time its BPMS was a moment behind, which is the opposite of
   * what somebody writing the key wants.
   *
   * @throws IllegalStateException Naming the key and the way out
   */
  public void validateVisibilityWait() {

    if ((waitForVisibilityAtMost == null) || waitForVisibilityAtMost.isPositive()) {
      return;
    }
    throw new IllegalStateException(
        """
            The property '%s' is '%s', but it has to be longer than zero! It says how long an \
            outbox entry may wait for a BPMS which does not report its workflow yet, for example \
            a Camunda 8 cluster whose exporter is behind. Write a duration like '4h' or '30m', or \
            remove the property to wait as long as the attempts of '%s' take."""
            .formatted(WAIT_FOR_VISIBILITY_AT_MOST_PROPERTY, waitForVisibilityAtMost, BLOCK_AFTER_ATTEMPTS_PROPERTY));

  }

  /**
   * The distance to the next attempt after a dispatch which failed, doubling per
   * attempt and capped at {@link #maxAttemptFrequency}. The first retry keeps
   * {@link #attemptFrequency}, because most failures are momentary and waiting
   * longer buys nothing there.
   * <p>
   * The stores VanillaBP owns compute their next attempt with this method, so the
   * curve is the same on every platform and on every persistence. The gruelbox store is
   * not one of them. It lives in the artifact
   * <code>io.vanillabp:gruelbox-phase-two-outbox</code>, and an application which adds that
   * artifact gets the retry policy of the gruelbox library. That library knows one fixed
   * distance, and the README of that artifact says what follows from it.
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
  private int dispatchThreads = 4;

  /**
   * The key of {@link #dispatchThreads}:
   * <code>vanillabp.outbox.dispatch-threads</code>. It is a constant because the message
   * about fewer than one thread names it.
   */
  public static final String DISPATCH_THREADS_PROPERTY = SECTION
      + ".dispatch-threads";

  /**
   * Whether the schema (table/collection) used to store outbox entries is created
   * automatically. Disable this if the database schema is managed manually (e.g. by
   * Flyway or Liquibase).
   * <p>
   * Key <code>vanillabp.outbox.create-schema</code>, <code>true</code> by default. The
   * records of processed task deliveries share it, because it is a setting of the store
   * rather than of the outbox.
   */
  private boolean createSchema = true;

  /**
   * The key of {@link #createSchema}: <code>vanillabp.outbox.create-schema</code>. It is
   * a constant because every message about a table or a collection which is missing
   * names it, and a test reads the key from here instead of writing it a second time.
   */
  public static final String CREATE_SCHEMA_PROPERTY = SECTION
      + ".create-schema";

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
  private Duration retention = DEFAULT_RETENTION;

  /**
   * The key of {@link #retention}: <code>vanillabp.outbox.retention</code>. It is a
   * constant because the two messages telling the retentions apart name it next to
   * {@link DeliveryProperties#RETENTION_PROPERTY}.
   */
  public static final String RETENTION_PROPERTY = SECTION
      + ".retention";

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
  private JdbcOutboxProperties jdbc = new JdbcOutboxProperties();

  /**
   * Configuration of the MongoDB-based default outbox. Both default outboxes (JDBC
   * and MongoDB) may be active in the same application - each aggregate is served by
   * the outbox matching its persistence.
   */
  private MongoOutboxProperties mongo = new MongoOutboxProperties();

  /**
   * When the housekeeping of the outbox runs and in which time zone (properties section
   * <code>vanillabp.outbox.housekeeping.*</code>).
   */
  private HousekeepingProperties housekeeping = new HousekeepingProperties();

  /**
   * Refuses a configuration the housekeeping cannot run on - a window which is no window
   * and a time zone nobody knows - and warns about the one case where a correct-looking
   * configuration house-keeps at the wrong hour: a JVM on UTC without a zone of its own.
   *
   * @param findings Where the warning about the zone is noted, so it reaches the reader in
   *          the box at the end of the start rather than as a line of its own
   * @throws IllegalStateException Naming the key and the way out
   */
  public void validateHousekeeping(
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    if (housekeeping == null) {
      // a binder mapping an absent section onto null must not cost the defaults
      housekeeping = new HousekeepingProperties();
    }
    housekeeping.validate(findings);

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
  public static class JdbcOutboxProperties {

    /**
     * The empty section a configuration binder starts from, and the section an application
     * which writes nothing about the JDBC outbox gets, since the field holding it has this
     * as its default.
     * <p>
     * It asks the builder for the values, and that is not a detour: every default of
     * this class stands on the builder as well as on the field, and both ways into an
     * object end here. A constructor which set the fields itself would have to repeat
     * every default, and the first one somebody forgets would switch the JDBC outbox off
     * for every application which configures no outbox.
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
    private boolean enabled = true;

    /**
     * The name of the table storing outbox entries. Every outbox instance needs its
     * own store - two dispatchers polling the same table would compete and
     * double-dispatch. <code>null</code> means
     * <code>VANILLABP_PHASE_TWO_OUTBOX</code>, on both platforms. The table meant here
     * is the one VanillaBP writes itself. An application which uses the gruelbox store of
     * <code>io.vanillabp:gruelbox-phase-two-outbox</code> stores its entries in gruelbox'
     * own <code>TXNO_OUTBOX</code>, which this key does not rename, because that table
     * and its columns belong to the library.
     * <p>
     * Key <code>vanillabp.outbox.jdbc.table</code>, unset by default.
     */
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

    /**
     * The builder of {@link JdbcOutboxProperties}. Its two type parameters carry the
     * class being built and the builder itself, so a call inherited from a base class
     * comes back as the builder of the subclass and the next call in the chain sees
     * every key again.
     *
     * @param <C> The class this builder builds
     * @param <B> The builder itself, which every method of the chain returns
     */
    public abstract static class JdbcOutboxPropertiesBuilder<C extends JdbcOutboxProperties, B extends JdbcOutboxProperties.JdbcOutboxPropertiesBuilder<C, B>> {

      /**
       * Whether the JDBC-based default outbox is created when a data source is
       * available. The builder starts from the same value the field does.
       */
      private boolean enabled = true;

      /**
       * The name of the table storing outbox entries. The builder starts from the same
       * value the field does.
       */
      private String table = null;

      /**
       * The name of the table storing the payloads of phase-two calls which carry one
       * (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}). The builder
       * starts from the same value the field does.
       */
      private String payloadTable = null;

      /**
       * The name of the table storing the records of processed task deliveries (see
       * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). The builder starts from
       * the same value the field does.
       */
      private String deliveryTable = null;

      /**
       * The name of the table the nightly housekeeping takes its lease in - one row per
       * store, holding who is house-keeping it and until when, so only one node of a
       * cluster measures its own work (see decision 91 in the repository's
       * DECISIONS.md). The builder starts from the same value the field does.
       */
      private String housekeepingTable = null;

      /**
       * The builder of a subclass calls this while it is built. Nobody else needs one:
       * {@link JdbcOutboxProperties#builder()} hands out the builder of this class.
       */
      public JdbcOutboxPropertiesBuilder() {
      }

      /**
       * Whether the JDBC-based default outbox is created when a data source is
       * available.
       *
       * @param enabled The value of {@link #enabled}
       * @return This builder, so the calls chain
       */
      public B enabled(
          final boolean enabled) {

        this.enabled = enabled;
        return self();

      }

      /**
       * The name of the table storing outbox entries.
       *
       * @param table The value of {@link #table}
       * @return This builder, so the calls chain
       */
      public B table(
          final String table) {

        this.table = table;
        return self();

      }

      /**
       * The name of the table storing the payloads of phase-two calls which carry one
       * (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}).
       *
       * @param payloadTable The value of {@link #payloadTable}
       * @return This builder, so the calls chain
       */
      public B payloadTable(
          final String payloadTable) {

        this.payloadTable = payloadTable;
        return self();

      }

      /**
       * The name of the table storing the records of processed task deliveries (see
       * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
       *
       * @param deliveryTable The value of {@link #deliveryTable}
       * @return This builder, so the calls chain
       */
      public B deliveryTable(
          final String deliveryTable) {

        this.deliveryTable = deliveryTable;
        return self();

      }

      /**
       * The name of the table the nightly housekeeping takes its lease in - one row per
       * store, holding who is house-keeping it and until when, so only one node of a
       * cluster measures its own work (see decision 91 in the repository's
       * DECISIONS.md).
       *
       * @param housekeepingTable The value of {@link #housekeepingTable}
       * @return This builder, so the calls chain
       */
      public B housekeepingTable(
          final String housekeepingTable) {

        this.housekeepingTable = housekeepingTable;
        return self();

      }

      /**
       * The builder itself, typed as the builder of the subclass. Every method of the
       * chain returns it, which is what keeps a chain started on a subclass builder at
       * that subclass.
       *
       * @return This builder
       */
      protected abstract B self();

      /**
       * Builds the object from what was written into this builder.
       *
       * @return The built object
       */
      public abstract C build();

      /**
       * What this builder holds, for a message and for a debugger.
       *
       * @return The name of this builder and every value written into it
       */
      @Override
      public String toString() {

        return "JdbcOutboxProperties.JdbcOutboxPropertiesBuilder("
            + "enabled="
            + enabled
            + ", "
            + "table="
            + table
            + ", "
            + "payloadTable="
            + payloadTable
            + ", "
            + "deliveryTable="
            + deliveryTable
            + ", "
            + "housekeepingTable="
            + housekeepingTable
            + ")";

      }

    }

    /**
     * The builder {@link #builder()} hands out: the one which builds
     * {@link JdbcOutboxProperties} itself rather than a subclass of it.
     */
    private static final class JdbcOutboxPropertiesBuilderImpl extends JdbcOutboxProperties.JdbcOutboxPropertiesBuilder<JdbcOutboxProperties, JdbcOutboxProperties.JdbcOutboxPropertiesBuilderImpl> {

      /**
       * Nobody but {@link JdbcOutboxProperties#builder()} builds one.
       */
      private JdbcOutboxPropertiesBuilderImpl() {
      }

      /**
       * This builder, typed as itself.
       *
       * @return This builder
       */
      @Override
      protected JdbcOutboxProperties.JdbcOutboxPropertiesBuilderImpl self() {

        return this;

      }

      /**
       * Builds the object from what was written into this builder.
       *
       * @return The built object
       */
      @Override
      public JdbcOutboxProperties build() {

        return new JdbcOutboxProperties(this);

      }

    }

    /**
     * What every builder of this class and of its subclasses builds through. It is the
     * one place the values of this class move from the builder into the object, so a
     * subclass builder fills the keys of its base class as well.
     *
     * @param b The builder holding what was written
     */
    protected JdbcOutboxProperties(
        final JdbcOutboxProperties.JdbcOutboxPropertiesBuilder<?, ?> b) {

      this.enabled = b.enabled;
      this.table = b.table;
      this.payloadTable = b.payloadTable;
      this.deliveryTable = b.deliveryTable;
      this.housekeepingTable = b.housekeepingTable;

    }

    /**
     * A builder of {@link JdbcOutboxProperties}, empty except for the values which have
     * a default.
     *
     * @return The builder
     */
    public static JdbcOutboxProperties.JdbcOutboxPropertiesBuilder<?, ?> builder() {

      return new JdbcOutboxProperties.JdbcOutboxPropertiesBuilderImpl();

    }

    /**
     * Whether the JDBC-based default outbox is created when a data source is available.
     *
     * @return The value of {@link #enabled}
     */
    public boolean isEnabled() {

      return enabled;

    }

    /**
     * The name of the table storing outbox entries.
     *
     * @return The value of {@link #table}
     */
    public String getTable() {

      return table;

    }

    /**
     * The name of the table storing the payloads of phase-two calls which carry one (see
     * {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}).
     *
     * @return The value of {@link #payloadTable}
     */
    public String getPayloadTable() {

      return payloadTable;

    }

    /**
     * The name of the table storing the records of processed task deliveries (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     *
     * @return The value of {@link #deliveryTable}
     */
    public String getDeliveryTable() {

      return deliveryTable;

    }

    /**
     * The name of the table the nightly housekeeping takes its lease in - one row per
     * store, holding who is house-keeping it and until when, so only one node of a
     * cluster measures its own work (see decision 91 in the repository's DECISIONS.md).
     *
     * @return The value of {@link #housekeepingTable}
     */
    public String getHousekeepingTable() {

      return housekeepingTable;

    }

    /**
     * Whether the JDBC-based default outbox is created when a data source is available.
     *
     * @param enabled The value of {@link #enabled}
     */
    public void setEnabled(
        final boolean enabled) {

      this.enabled = enabled;

    }

    /**
     * The name of the table storing outbox entries.
     *
     * @param table The value of {@link #table}
     */
    public void setTable(
        final String table) {

      this.table = table;

    }

    /**
     * The name of the table storing the payloads of phase-two calls which carry one (see
     * {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}).
     *
     * @param payloadTable The value of {@link #payloadTable}
     */
    public void setPayloadTable(
        final String payloadTable) {

      this.payloadTable = payloadTable;

    }

    /**
     * The name of the table storing the records of processed task deliveries (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     *
     * @param deliveryTable The value of {@link #deliveryTable}
     */
    public void setDeliveryTable(
        final String deliveryTable) {

      this.deliveryTable = deliveryTable;

    }

    /**
     * The name of the table the nightly housekeeping takes its lease in - one row per
     * store, holding who is house-keeping it and until when, so only one node of a
     * cluster measures its own work (see decision 91 in the repository's DECISIONS.md).
     *
     * @param housekeepingTable The value of {@link #housekeepingTable}
     */
    public void setHousekeepingTable(
        final String housekeepingTable) {

      this.housekeepingTable = housekeepingTable;

    }

  }

  /**
   * The keys of the MongoDB default outbox (properties section
   * <code>vanillabp.outbox.mongo.*</code>): whether it is built at all, and the
   * collections it and the delivery log work on.
   */
  public static class MongoOutboxProperties {

    /**
     * The empty section a configuration binder starts from, and the section an application
     * which writes nothing about the MongoDB outbox gets, since the field holding it has
     * this as its default.
     * <p>
     * It asks the builder for the values, and that is not a detour: every default of
     * this class stands on the builder as well as on the field, and both ways into an
     * object end here. A constructor which set the fields itself would have to repeat
     * every default, and the first one somebody forgets would switch the MongoDB outbox
     * off and leave both collections unnamed.
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
    private boolean enabled = true;

    /**
     * The name of the collection storing outbox entries. Every outbox instance
     * needs its own store - two dispatchers polling the same collection would
     * compete and double-dispatch.
     * <p>
     * Key <code>vanillabp.outbox.mongo.collection</code>,
     * {@value #DEFAULT_COLLECTION} by default.
     */
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

    /**
     * The builder of {@link MongoOutboxProperties}. Its two type parameters carry the
     * class being built and the builder itself, so a call inherited from a base class
     * comes back as the builder of the subclass and the next call in the chain sees
     * every key again.
     *
     * @param <C> The class this builder builds
     * @param <B> The builder itself, which every method of the chain returns
     */
    public abstract static class MongoOutboxPropertiesBuilder<C extends MongoOutboxProperties, B extends MongoOutboxProperties.MongoOutboxPropertiesBuilder<C, B>> {

      /**
       * Whether the MongoDB-based default outbox is created when a MongoDB connection is
       * available. The builder starts from the same value the field does.
       */
      private boolean enabled = true;

      /**
       * The name of the collection storing outbox entries. The builder starts from the
       * same value the field does.
       */
      private String collection = DEFAULT_COLLECTION;

      /**
       * The name of the collection storing the payloads of phase-two calls which carry
       * one (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}). The builder
       * starts from the same value the field does.
       */
      private String payloadCollection = null;

      /**
       * The name of the collection storing the records of processed task deliveries (see
       * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). The builder starts from
       * the same value the field does.
       */
      private String deliveryCollection = DEFAULT_DELIVERY_COLLECTION;

      /**
       * The name of the collection the nightly housekeeping takes its lease in - one
       * document per store, holding who is house-keeping it and until when, so only one
       * node of a cluster measures its own work (see decision 91 in the repository's
       * DECISIONS.md). The builder starts from the same value the field does.
       */
      private String housekeepingCollection = DEFAULT_HOUSEKEEPING_COLLECTION;

      /**
       * The builder of a subclass calls this while it is built. Nobody else needs one:
       * {@link MongoOutboxProperties#builder()} hands out the builder of this class.
       */
      public MongoOutboxPropertiesBuilder() {
      }

      /**
       * Whether the MongoDB-based default outbox is created when a MongoDB connection is
       * available.
       *
       * @param enabled The value of {@link #enabled}
       * @return This builder, so the calls chain
       */
      public B enabled(
          final boolean enabled) {

        this.enabled = enabled;
        return self();

      }

      /**
       * The name of the collection storing outbox entries.
       *
       * @param collection The value of {@link #collection}
       * @return This builder, so the calls chain
       */
      public B collection(
          final String collection) {

        this.collection = collection;
        return self();

      }

      /**
       * The name of the collection storing the payloads of phase-two calls which carry
       * one (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}).
       *
       * @param payloadCollection The value of {@link #payloadCollection}
       * @return This builder, so the calls chain
       */
      public B payloadCollection(
          final String payloadCollection) {

        this.payloadCollection = payloadCollection;
        return self();

      }

      /**
       * The name of the collection storing the records of processed task deliveries (see
       * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
       *
       * @param deliveryCollection The value of {@link #deliveryCollection}
       * @return This builder, so the calls chain
       */
      public B deliveryCollection(
          final String deliveryCollection) {

        this.deliveryCollection = deliveryCollection;
        return self();

      }

      /**
       * The name of the collection the nightly housekeeping takes its lease in - one
       * document per store, holding who is house-keeping it and until when, so only one
       * node of a cluster measures its own work (see decision 91 in the repository's
       * DECISIONS.md).
       *
       * @param housekeepingCollection The value of {@link #housekeepingCollection}
       * @return This builder, so the calls chain
       */
      public B housekeepingCollection(
          final String housekeepingCollection) {

        this.housekeepingCollection = housekeepingCollection;
        return self();

      }

      /**
       * The builder itself, typed as the builder of the subclass. Every method of the
       * chain returns it, which is what keeps a chain started on a subclass builder at
       * that subclass.
       *
       * @return This builder
       */
      protected abstract B self();

      /**
       * Builds the object from what was written into this builder.
       *
       * @return The built object
       */
      public abstract C build();

      /**
       * What this builder holds, for a message and for a debugger.
       *
       * @return The name of this builder and every value written into it
       */
      @Override
      public String toString() {

        return "MongoOutboxProperties.MongoOutboxPropertiesBuilder("
            + "enabled="
            + enabled
            + ", "
            + "collection="
            + collection
            + ", "
            + "payloadCollection="
            + payloadCollection
            + ", "
            + "deliveryCollection="
            + deliveryCollection
            + ", "
            + "housekeepingCollection="
            + housekeepingCollection
            + ")";

      }

    }

    /**
     * The builder {@link #builder()} hands out: the one which builds
     * {@link MongoOutboxProperties} itself rather than a subclass of it.
     */
    private static final class MongoOutboxPropertiesBuilderImpl extends MongoOutboxProperties.MongoOutboxPropertiesBuilder<MongoOutboxProperties, MongoOutboxProperties.MongoOutboxPropertiesBuilderImpl> {

      /**
       * Nobody but {@link MongoOutboxProperties#builder()} builds one.
       */
      private MongoOutboxPropertiesBuilderImpl() {
      }

      /**
       * This builder, typed as itself.
       *
       * @return This builder
       */
      @Override
      protected MongoOutboxProperties.MongoOutboxPropertiesBuilderImpl self() {

        return this;

      }

      /**
       * Builds the object from what was written into this builder.
       *
       * @return The built object
       */
      @Override
      public MongoOutboxProperties build() {

        return new MongoOutboxProperties(this);

      }

    }

    /**
     * What every builder of this class and of its subclasses builds through. It is the
     * one place the values of this class move from the builder into the object, so a
     * subclass builder fills the keys of its base class as well.
     *
     * @param b The builder holding what was written
     */
    protected MongoOutboxProperties(
        final MongoOutboxProperties.MongoOutboxPropertiesBuilder<?, ?> b) {

      this.enabled = b.enabled;
      this.collection = b.collection;
      this.payloadCollection = b.payloadCollection;
      this.deliveryCollection = b.deliveryCollection;
      this.housekeepingCollection = b.housekeepingCollection;

    }

    /**
     * A builder of {@link MongoOutboxProperties}, empty except for the values which have
     * a default.
     *
     * @return The builder
     */
    public static MongoOutboxProperties.MongoOutboxPropertiesBuilder<?, ?> builder() {

      return new MongoOutboxProperties.MongoOutboxPropertiesBuilderImpl();

    }

    /**
     * Whether the MongoDB-based default outbox is created when a MongoDB connection is
     * available.
     *
     * @return The value of {@link #enabled}
     */
    public boolean isEnabled() {

      return enabled;

    }

    /**
     * The name of the collection storing outbox entries.
     *
     * @return The value of {@link #collection}
     */
    public String getCollection() {

      return collection;

    }

    /**
     * The name of the collection storing the payloads of phase-two calls which carry one
     * (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}).
     *
     * @return The value of {@link #payloadCollection}
     */
    public String getPayloadCollection() {

      return payloadCollection;

    }

    /**
     * The name of the collection storing the records of processed task deliveries (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     *
     * @return The value of {@link #deliveryCollection}
     */
    public String getDeliveryCollection() {

      return deliveryCollection;

    }

    /**
     * The name of the collection the nightly housekeeping takes its lease in - one
     * document per store, holding who is house-keeping it and until when, so only one
     * node of a cluster measures its own work (see decision 91 in the repository's
     * DECISIONS.md).
     *
     * @return The value of {@link #housekeepingCollection}
     */
    public String getHousekeepingCollection() {

      return housekeepingCollection;

    }

    /**
     * Whether the MongoDB-based default outbox is created when a MongoDB connection is
     * available.
     *
     * @param enabled The value of {@link #enabled}
     */
    public void setEnabled(
        final boolean enabled) {

      this.enabled = enabled;

    }

    /**
     * The name of the collection storing outbox entries.
     *
     * @param collection The value of {@link #collection}
     */
    public void setCollection(
        final String collection) {

      this.collection = collection;

    }

    /**
     * The name of the collection storing the payloads of phase-two calls which carry one
     * (see {@link io.vanillabp.integration.spi.PhaseTwoPayloadStore}).
     *
     * @param payloadCollection The value of {@link #payloadCollection}
     */
    public void setPayloadCollection(
        final String payloadCollection) {

      this.payloadCollection = payloadCollection;

    }

    /**
     * The name of the collection storing the records of processed task deliveries (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     *
     * @param deliveryCollection The value of {@link #deliveryCollection}
     */
    public void setDeliveryCollection(
        final String deliveryCollection) {

      this.deliveryCollection = deliveryCollection;

    }

    /**
     * The name of the collection the nightly housekeeping takes its lease in - one
     * document per store, holding who is house-keeping it and until when, so only one
     * node of a cluster measures its own work (see decision 91 in the repository's
     * DECISIONS.md).
     *
     * @param housekeepingCollection The value of {@link #housekeepingCollection}
     */
    public void setHousekeepingCollection(
        final String housekeepingCollection) {

      this.housekeepingCollection = housekeepingCollection;

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
  public static class HousekeepingProperties {

    /**
     * The empty section a configuration binder starts from, and the section an application
     * which writes nothing about the housekeeping gets.
     * <p>
     * It asks the builder for the values, and that is not a detour: every default of
     * this class stands on the builder as well as on the field, and both ways into an
     * object end here. A constructor which set the fields itself would have to repeat
     * every default, and the first one somebody forgets would hand an application which
     * configures no outbox a window from <code>null</code> to <code>null</code>.
     */
    public HousekeepingProperties() {

      this(HousekeepingProperties.builder());

    }

    /**
     * What the box calls this section when one of its settings is worth a look:
     * <code>vanillabp.outbox.housekeeping</code>. It is the scope of the finding, which
     * is why it stands beside the message rather than inside it.
     */
    public static final String HOUSEKEEPING_PREFIX = SECTION
        + ".housekeeping";

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
    private LocalTime end = DEFAULT_END;

    /**
     * The zone the two times above are read in. Key
     * <code>vanillabp.outbox.housekeeping.zone</code>, unset by default, which means the
     * zone of the JVM.
     * <p>
     * Written the way {@link ZoneId} spells one, for example <code>Europe/Vienna</code>.
     * <p>
     * A JVM standing on UTC without this key is warned once at the startup, because "four
     * in the morning" is then four UTC - see {@link #sayWhichZoneTheWindowRunsIn(io.vanillabp.integration.adapter.migration.startup.StartupFindings)}.
     */
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
     * @param findings Where the warning about the zone is noted
     * @throws IllegalStateException Naming the key and the way out
     */
    public void validate(
        final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

      refuseAWindowWhichIsNone();
      refuseAZoneNobodyKnows();
      sayWhichZoneTheWindowRunsIn(findings);

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
     * It goes into the box VanillaBP writes at the end of a start rather than into a line
     * of its own, because it is exactly the kind of note nobody goes looking for.
     * <p>
     * An application which really wants UTC writes it down, and then this says nothing.
     *
     * @param findings Where the note is left
     */
    private void sayWhichZoneTheWindowRunsIn(
        final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

      if ((zone != null) && !zone.isBlank()) {
        return;
      }
      final var jvmZone = ZoneId.systemDefault();
      if (!MEANS_UTC.contains(jvmZone.getId().toUpperCase(Locale.ROOT))) {
        return;
      }
      findings
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.CONFIGURATION,
              HOUSEKEEPING_PREFIX,
              """
                  The housekeeping of the VanillaBP outbox runs from %s to %s UTC, because this JVM \
                  stands on '%s' and no time zone was configured for it. In most places that is the \
                  middle of the working day rather than the quiet hour it is meant to be. Say which \
                  zone you mean, in one of two ways, neither of which needs a new build:
                    - set the zone of the container, for example TZ=Europe/Vienna, or
                    - set the zone of the housekeeping alone, for example %s=Europe/Vienna (property \
                  '%s').
                  Write 'UTC' there if UTC is what you mean, and this line goes away."""
                  .formatted(
                      start,
                      end,
                      jvmZone.getId(),
                      ZONE_ENVIRONMENT_VARIABLE,
                      ZONE_PROPERTY));

    }

    /**
     * The builder of {@link HousekeepingProperties}. Its two type parameters carry the
     * class being built and the builder itself, so a call inherited from a base class
     * comes back as the builder of the subclass and the next call in the chain sees
     * every key again.
     *
     * @param <C> The class this builder builds
     * @param <B> The builder itself, which every method of the chain returns
     */
    public abstract static class HousekeepingPropertiesBuilder<C extends HousekeepingProperties, B extends HousekeepingProperties.HousekeepingPropertiesBuilder<C, B>> {

      /**
       * When the window opens, in the zone below. The builder starts from the same value
       * the field does.
       */
      private LocalTime start = DEFAULT_START;

      /**
       * When the window closes. The builder starts from the same value the field does.
       */
      private LocalTime end = DEFAULT_END;

      /**
       * The zone the two times above are read in. The builder starts from the same value
       * the field does.
       */
      private String zone = null;

      /**
       * The builder of a subclass calls this while it is built. Nobody else needs one:
       * {@link HousekeepingProperties#builder()} hands out the builder of this class.
       */
      public HousekeepingPropertiesBuilder() {
      }

      /**
       * When the window opens, in the zone below.
       *
       * @param start The value of {@link #start}
       * @return This builder, so the calls chain
       */
      public B start(
          final LocalTime start) {

        this.start = start;
        return self();

      }

      /**
       * When the window closes.
       *
       * @param end The value of {@link #end}
       * @return This builder, so the calls chain
       */
      public B end(
          final LocalTime end) {

        this.end = end;
        return self();

      }

      /**
       * The zone the two times above are read in.
       *
       * @param zone The value of {@link #zone}
       * @return This builder, so the calls chain
       */
      public B zone(
          final String zone) {

        this.zone = zone;
        return self();

      }

      /**
       * The builder itself, typed as the builder of the subclass. Every method of the
       * chain returns it, which is what keeps a chain started on a subclass builder at
       * that subclass.
       *
       * @return This builder
       */
      protected abstract B self();

      /**
       * Builds the object from what was written into this builder.
       *
       * @return The built object
       */
      public abstract C build();

      /**
       * What this builder holds, for a message and for a debugger.
       *
       * @return The name of this builder and every value written into it
       */
      @Override
      public String toString() {

        return "HousekeepingProperties.HousekeepingPropertiesBuilder("
            + "start="
            + start
            + ", "
            + "end="
            + end
            + ", "
            + "zone="
            + zone
            + ")";

      }

    }

    /**
     * The builder {@link #builder()} hands out: the one which builds
     * {@link HousekeepingProperties} itself rather than a subclass of it.
     */
    private static final class HousekeepingPropertiesBuilderImpl extends HousekeepingProperties.HousekeepingPropertiesBuilder<HousekeepingProperties, HousekeepingProperties.HousekeepingPropertiesBuilderImpl> {

      /**
       * Nobody but {@link HousekeepingProperties#builder()} builds one.
       */
      private HousekeepingPropertiesBuilderImpl() {
      }

      /**
       * This builder, typed as itself.
       *
       * @return This builder
       */
      @Override
      protected HousekeepingProperties.HousekeepingPropertiesBuilderImpl self() {

        return this;

      }

      /**
       * Builds the object from what was written into this builder.
       *
       * @return The built object
       */
      @Override
      public HousekeepingProperties build() {

        return new HousekeepingProperties(this);

      }

    }

    /**
     * What every builder of this class and of its subclasses builds through. It is the
     * one place the values of this class move from the builder into the object, so a
     * subclass builder fills the keys of its base class as well.
     *
     * @param b The builder holding what was written
     */
    protected HousekeepingProperties(
        final HousekeepingProperties.HousekeepingPropertiesBuilder<?, ?> b) {

      this.start = b.start;
      this.end = b.end;
      this.zone = b.zone;

    }

    /**
     * A builder of {@link HousekeepingProperties}, empty except for the values which
     * have a default.
     *
     * @return The builder
     */
    public static HousekeepingProperties.HousekeepingPropertiesBuilder<?, ?> builder() {

      return new HousekeepingProperties.HousekeepingPropertiesBuilderImpl();

    }

    /**
     * When the window opens, in the zone below.
     *
     * @return The value of {@link #start}
     */
    public LocalTime getStart() {

      return start;

    }

    /**
     * When the window closes.
     *
     * @return The value of {@link #end}
     */
    public LocalTime getEnd() {

      return end;

    }

    /**
     * The zone the two times above are read in.
     *
     * @return The value of {@link #zone}
     */
    public String getZone() {

      return zone;

    }

    /**
     * When the window opens, in the zone below.
     *
     * @param start The value of {@link #start}
     */
    public void setStart(
        final LocalTime start) {

      this.start = start;

    }

    /**
     * When the window closes.
     *
     * @param end The value of {@link #end}
     */
    public void setEnd(
        final LocalTime end) {

      this.end = end;

    }

    /**
     * The zone the two times above are read in.
     *
     * @param zone The value of {@link #zone}
     */
    public void setZone(
        final String zone) {

      this.zone = zone;

    }

  }

  /**
   * The builder of {@link PhaseTwoOutboxProperties}. Its two type parameters carry the
   * class being built and the builder itself, so a call inherited from a base class
   * comes back as the builder of the subclass and the next call in the chain sees every
   * key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class PhaseTwoOutboxPropertiesBuilder<C extends PhaseTwoOutboxProperties, B extends PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilder<C, B>> {

    /**
     * The longest a store's background poller sleeps while it owes nothing. The builder
     * starts from the same value the field does.
     */
    private Duration pollInterval = DEFAULT_POLL_INTERVAL;

    /**
     * The distance to the FIRST retry after a failed dispatch. The builder starts from
     * the same value the field does.
     */
    private Duration attemptFrequency = Duration.ofSeconds(30);

    /**
     * The longest distance the growing backoff reaches. The builder starts from the same
     * value the field does.
     */
    private Duration maxAttemptFrequency = Duration.ofMinutes(5);

    /**
     * After how many failed attempts an entry is blocked (not retried any longer). The
     * builder starts from the same value the field does.
     */
    private int blockAfterAttempts = 50;

    /**
     * How long an entry may wait for a BPMS which does not report its workflow yet. The
     * builder starts from the same value the field does: not set.
     */
    private Duration waitForVisibilityAtMost = null;

    /**
     * How many entries an outbox of VanillaBP's own dispatches at the same time - the
     * JDBC one and the MongoDB one of each platform. The builder starts from the same
     * value the field does.
     */
    private int dispatchThreads = 4;

    /**
     * Whether the schema (table/collection) used to store outbox entries is created
     * automatically. The builder starts from the same value the field does.
     */
    private boolean createSchema = true;

    /**
     * How long successfully dispatched entries (marked as DONE) are retained before they
     * are deleted asynchronously - what a retained entry buys is a dispatched operation
     * somebody can still look at during support, not a longer deduplication window: that
     * one ends with the dispatch (see
     * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}). The builder starts from the
     * same value the field does.
     */
    private Duration retention = DEFAULT_RETENTION;

    /**
     * Configuration of the JDBC default outbox, which both platforms run: Spring Boot on
     * the connection of its transaction manager, Quarkus on an Agroal connection of the
     * running JTA transaction. The builder starts from the same value the field does.
     */
    private JdbcOutboxProperties jdbc = new JdbcOutboxProperties();

    /**
     * Configuration of the MongoDB-based default outbox. The builder starts from the
     * same value the field does.
     */
    private MongoOutboxProperties mongo = new MongoOutboxProperties();

    /**
     * When the housekeeping of the outbox runs and in which time zone (properties
     * section <code>vanillabp.outbox.housekeeping.*</code>). The builder starts from the
     * same value the field does.
     */
    private HousekeepingProperties housekeeping = new HousekeepingProperties();

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link PhaseTwoOutboxProperties#builder()} hands out the builder of this class.
     */
    public PhaseTwoOutboxPropertiesBuilder() {
    }

    /**
     * The longest a store's background poller sleeps while it owes nothing.
     *
     * @param pollInterval The value of {@link #pollInterval}
     * @return This builder, so the calls chain
     */
    public B pollInterval(
        final Duration pollInterval) {

      this.pollInterval = pollInterval;
      return self();

    }

    /**
     * The distance to the FIRST retry after a failed dispatch.
     *
     * @param attemptFrequency The value of {@link #attemptFrequency}
     * @return This builder, so the calls chain
     */
    public B attemptFrequency(
        final Duration attemptFrequency) {

      this.attemptFrequency = attemptFrequency;
      return self();

    }

    /**
     * The longest distance the growing backoff reaches.
     *
     * @param maxAttemptFrequency The value of {@link #maxAttemptFrequency}
     * @return This builder, so the calls chain
     */
    public B maxAttemptFrequency(
        final Duration maxAttemptFrequency) {

      this.maxAttemptFrequency = maxAttemptFrequency;
      return self();

    }

    /**
     * After how many failed attempts an entry is blocked (not retried any longer).
     *
     * @param blockAfterAttempts The value of {@link #blockAfterAttempts}
     * @return This builder, so the calls chain
     */
    public B blockAfterAttempts(
        final int blockAfterAttempts) {

      this.blockAfterAttempts = blockAfterAttempts;
      return self();

    }

    /**
     * How long an entry may wait for a BPMS which does not report its workflow yet.
     *
     * @param waitForVisibilityAtMost The value of {@link #waitForVisibilityAtMost}
     * @return This builder, so the calls chain
     */
    public B waitForVisibilityAtMost(
        final Duration waitForVisibilityAtMost) {

      this.waitForVisibilityAtMost = waitForVisibilityAtMost;
      return self();

    }

    /**
     * How many entries an outbox of VanillaBP's own dispatches at the same time - the
     * JDBC one and the MongoDB one of each platform.
     *
     * @param dispatchThreads The value of {@link #dispatchThreads}
     * @return This builder, so the calls chain
     */
    public B dispatchThreads(
        final int dispatchThreads) {

      this.dispatchThreads = dispatchThreads;
      return self();

    }

    /**
     * Whether the schema (table/collection) used to store outbox entries is created
     * automatically.
     *
     * @param createSchema The value of {@link #createSchema}
     * @return This builder, so the calls chain
     */
    public B createSchema(
        final boolean createSchema) {

      this.createSchema = createSchema;
      return self();

    }

    /**
     * How long successfully dispatched entries (marked as DONE) are retained before they
     * are deleted asynchronously - what a retained entry buys is a dispatched operation
     * somebody can still look at during support, not a longer deduplication window: that
     * one ends with the dispatch (see
     * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
     *
     * @param retention The value of {@link #retention}
     * @return This builder, so the calls chain
     */
    public B retention(
        final Duration retention) {

      this.retention = retention;
      return self();

    }

    /**
     * Configuration of the JDBC default outbox, which both platforms run: Spring Boot on
     * the connection of its transaction manager, Quarkus on an Agroal connection of the
     * running JTA transaction.
     *
     * @param jdbc The value of {@link #jdbc}
     * @return This builder, so the calls chain
     */
    public B jdbc(
        final JdbcOutboxProperties jdbc) {

      this.jdbc = jdbc;
      return self();

    }

    /**
     * Configuration of the MongoDB-based default outbox.
     *
     * @param mongo The value of {@link #mongo}
     * @return This builder, so the calls chain
     */
    public B mongo(
        final MongoOutboxProperties mongo) {

      this.mongo = mongo;
      return self();

    }

    /**
     * When the housekeeping of the outbox runs and in which time zone (properties
     * section <code>vanillabp.outbox.housekeeping.*</code>).
     *
     * @param housekeeping The value of {@link #housekeeping}
     * @return This builder, so the calls chain
     */
    public B housekeeping(
        final HousekeepingProperties housekeeping) {

      this.housekeeping = housekeeping;
      return self();

    }

    /**
     * The builder itself, typed as the builder of the subclass. Every method of the
     * chain returns it, which is what keeps a chain started on a subclass builder at
     * that subclass.
     *
     * @return This builder
     */
    protected abstract B self();

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    public abstract C build();

    /**
     * What this builder holds, for a message and for a debugger.
     *
     * @return The name of this builder and every value written into it
     */
    @Override
    public String toString() {

      return "PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilder("
          + "pollInterval="
          + pollInterval
          + ", "
          + "attemptFrequency="
          + attemptFrequency
          + ", "
          + "maxAttemptFrequency="
          + maxAttemptFrequency
          + ", "
          + "blockAfterAttempts="
          + blockAfterAttempts
          + ", "
          + "waitForVisibilityAtMost="
          + waitForVisibilityAtMost
          + ", "
          + "dispatchThreads="
          + dispatchThreads
          + ", "
          + "createSchema="
          + createSchema
          + ", "
          + "retention="
          + retention
          + ", "
          + "jdbc="
          + jdbc
          + ", "
          + "mongo="
          + mongo
          + ", "
          + "housekeeping="
          + housekeeping
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link PhaseTwoOutboxProperties} itself rather than a subclass of it.
   */
  private static final class PhaseTwoOutboxPropertiesBuilderImpl extends PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilder<PhaseTwoOutboxProperties, PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilderImpl> {

    /**
     * Nobody but {@link PhaseTwoOutboxProperties#builder()} builds one.
     */
    private PhaseTwoOutboxPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public PhaseTwoOutboxProperties build() {

      return new PhaseTwoOutboxProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected PhaseTwoOutboxProperties(
      final PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilder<?, ?> b) {

    this.pollInterval = b.pollInterval;
    this.attemptFrequency = b.attemptFrequency;
    this.maxAttemptFrequency = b.maxAttemptFrequency;
    this.blockAfterAttempts = b.blockAfterAttempts;
    this.waitForVisibilityAtMost = b.waitForVisibilityAtMost;
    this.dispatchThreads = b.dispatchThreads;
    this.createSchema = b.createSchema;
    this.retention = b.retention;
    this.jdbc = b.jdbc;
    this.mongo = b.mongo;
    this.housekeeping = b.housekeeping;

  }

  /**
   * A builder of {@link PhaseTwoOutboxProperties}, empty except for the values which
   * have a default.
   *
   * @return The builder
   */
  public static PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilder<?, ?> builder() {

    return new PhaseTwoOutboxProperties.PhaseTwoOutboxPropertiesBuilderImpl();

  }

  /**
   * The longest a store's background poller sleeps while it owes nothing.
   *
   * @return The value of {@link #pollInterval}
   */
  public Duration getPollInterval() {

    return pollInterval;

  }

  /**
   * The distance to the FIRST retry after a failed dispatch.
   *
   * @return The value of {@link #attemptFrequency}
   */
  public Duration getAttemptFrequency() {

    return attemptFrequency;

  }

  /**
   * The longest distance the growing backoff reaches.
   *
   * @return The value of {@link #maxAttemptFrequency}
   */
  public Duration getMaxAttemptFrequency() {

    return maxAttemptFrequency;

  }

  /**
   * After how many failed attempts an entry is blocked (not retried any longer).
   *
   * @return The value of {@link #blockAfterAttempts}
   */
  public int getBlockAfterAttempts() {

    return blockAfterAttempts;

  }

  /**
   * How long an entry may wait for a BPMS which does not report its workflow yet, as it was
   * configured. Read {@link #waitForVisibilityAtMost()} for the time which applies.
   *
   * @return The value of {@link #waitForVisibilityAtMost}, <code>null</code> where it is not
   *         set
   */
  public Duration getWaitForVisibilityAtMost() {

    return waitForVisibilityAtMost;

  }

  /**
   * How many entries an outbox of VanillaBP's own dispatches at the same time - the JDBC
   * one and the MongoDB one of each platform.
   *
   * @return The value of {@link #dispatchThreads}
   */
  public int getDispatchThreads() {

    return dispatchThreads;

  }

  /**
   * Whether the schema (table/collection) used to store outbox entries is created
   * automatically.
   *
   * @return The value of {@link #createSchema}
   */
  public boolean isCreateSchema() {

    return createSchema;

  }

  /**
   * How long successfully dispatched entries (marked as DONE) are retained before they
   * are deleted asynchronously - what a retained entry buys is a dispatched operation
   * somebody can still look at during support, not a longer deduplication window: that
   * one ends with the dispatch (see
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
   *
   * @return The value of {@link #retention}
   */
  public Duration getRetention() {

    return retention;

  }

  /**
   * Configuration of the JDBC default outbox, which both platforms run: Spring Boot on
   * the connection of its transaction manager, Quarkus on an Agroal connection of the
   * running JTA transaction.
   *
   * @return The value of {@link #jdbc}
   */
  public JdbcOutboxProperties getJdbc() {

    return jdbc;

  }

  /**
   * Configuration of the MongoDB-based default outbox.
   *
   * @return The value of {@link #mongo}
   */
  public MongoOutboxProperties getMongo() {

    return mongo;

  }

  /**
   * When the housekeeping of the outbox runs and in which time zone (properties section
   * <code>vanillabp.outbox.housekeeping.*</code>).
   *
   * @return The value of {@link #housekeeping}
   */
  public HousekeepingProperties getHousekeeping() {

    return housekeeping;

  }

  /**
   * The longest a store's background poller sleeps while it owes nothing.
   *
   * @param pollInterval The value of {@link #pollInterval}
   */
  public void setPollInterval(
      final Duration pollInterval) {

    this.pollInterval = pollInterval;

  }

  /**
   * The distance to the FIRST retry after a failed dispatch.
   *
   * @param attemptFrequency The value of {@link #attemptFrequency}
   */
  public void setAttemptFrequency(
      final Duration attemptFrequency) {

    this.attemptFrequency = attemptFrequency;

  }

  /**
   * The longest distance the growing backoff reaches.
   *
   * @param maxAttemptFrequency The value of {@link #maxAttemptFrequency}
   */
  public void setMaxAttemptFrequency(
      final Duration maxAttemptFrequency) {

    this.maxAttemptFrequency = maxAttemptFrequency;

  }

  /**
   * After how many failed attempts an entry is blocked (not retried any longer).
   *
   * @param blockAfterAttempts The value of {@link #blockAfterAttempts}
   */
  public void setBlockAfterAttempts(
      final int blockAfterAttempts) {

    this.blockAfterAttempts = blockAfterAttempts;

  }

  /**
   * How long an entry may wait for a BPMS which does not report its workflow yet.
   *
   * @param waitForVisibilityAtMost The value of {@link #waitForVisibilityAtMost},
   *          <code>null</code> for the time the attempts take
   */
  public void setWaitForVisibilityAtMost(
      final Duration waitForVisibilityAtMost) {

    this.waitForVisibilityAtMost = waitForVisibilityAtMost;

  }

  /**
   * How many entries an outbox of VanillaBP's own dispatches at the same time - the JDBC
   * one and the MongoDB one of each platform.
   *
   * @param dispatchThreads The value of {@link #dispatchThreads}
   */
  public void setDispatchThreads(
      final int dispatchThreads) {

    this.dispatchThreads = dispatchThreads;

  }

  /**
   * Whether the schema (table/collection) used to store outbox entries is created
   * automatically.
   *
   * @param createSchema The value of {@link #createSchema}
   */
  public void setCreateSchema(
      final boolean createSchema) {

    this.createSchema = createSchema;

  }

  /**
   * How long successfully dispatched entries (marked as DONE) are retained before they
   * are deleted asynchronously - what a retained entry buys is a dispatched operation
   * somebody can still look at during support, not a longer deduplication window: that
   * one ends with the dispatch (see
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
   *
   * @param retention The value of {@link #retention}
   */
  public void setRetention(
      final Duration retention) {

    this.retention = retention;

  }

  /**
   * Configuration of the JDBC default outbox, which both platforms run: Spring Boot on
   * the connection of its transaction manager, Quarkus on an Agroal connection of the
   * running JTA transaction.
   *
   * @param jdbc The value of {@link #jdbc}
   */
  public void setJdbc(
      final JdbcOutboxProperties jdbc) {

    this.jdbc = jdbc;

  }

  /**
   * Configuration of the MongoDB-based default outbox.
   *
   * @param mongo The value of {@link #mongo}
   */
  public void setMongo(
      final MongoOutboxProperties mongo) {

    this.mongo = mongo;

  }

  /**
   * When the housekeeping of the outbox runs and in which time zone (properties section
   * <code>vanillabp.outbox.housekeeping.*</code>).
   *
   * @param housekeeping The value of {@link #housekeeping}
   */
  public void setHousekeeping(
      final HousekeepingProperties housekeeping) {

    this.housekeeping = housekeeping;

  }

}
