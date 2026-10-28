package io.vanillabp.integration.runtime.config;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.OutfadedVersionsInUsePolicy;

/**
 * Properties common to all adapters.
 * <p>
 * The config root uses the phase {@link ConfigPhase#RUN_TIME}. Parts of the configuration
 * come from the config files of the workflow modules. Generated config builders add them
 * only to the static-init and runtime configuration. The build-time configuration does not
 * see them. Also, it must be possible to override VanillaBP configuration (e.g. adapter
 * endpoints) per environment.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = QuarkusMigrationAdapterProperties.PREFIX)
public interface QuarkusMigrationAdapterProperties {

  /**
   * The root of every key which VanillaBP reads. It is the prefix of this config mapping. So
   * an application writes <code>vanillabp.adapters.&lt;id&gt;.*</code> for one adapter. The
   * messages which name a key use this constant too. So the messages and the mapping cannot
   * drift apart.
   */
  String PREFIX = "vanillabp";

  /**
   * The list of adapters, ordered by priority. New workflows are started with the first
   * adapter. Other actions go to the BPMS which the workflow is running in.
   * <p>
   * Each item has to be a key in the map of {@link QuarkusMigrationAdapterProperties#adapters}.
   *
   * @return Ordered list of adapters to be used
   */
  Optional<List<String>> prioritizedAdapters();

  /**
   * Adapter ids which this application USED to have and on purpose does not configure any
   * more. This is the last step of a BPMS migration. VanillaBP stores the adapter id of an
   * outbox entry and of every delivery record. An entry may still wait for an id which
   * nobody serves. This is reported at startup. It means that the id was RENAMED, which
   * loses workflows, or that it was removed too early. Naming the id here says that it is
   * the second case. Then the message becomes a DEBUG line.
   *
   * @return The adapter ids retired deliberately
   */
  Optional<List<String>> retiredAdapters();

  /**
   * Bound, although it is refused here. The permission to share a whole workflow aggregate
   * belongs to the single workflow. If a line is written at this level, the core answers
   * with a message which says where it belongs.
   *
   * @return The misplaced permission, if somebody wrote one
   */
  Optional<Boolean> allowFullSyncWithBpms();

  /**
   * Whether the expressions of every BPMN model of this application are meant as they are.
   * This is the least specific of the three levels where the core reads this.
   *
   * @return The answer of this level, an empty Optional leaving it to the level above
   */
  Optional<Boolean> acceptExpressionsInTheModel();

  /**
   * The <code>&#64;TaskParam</code> parameters whose type the developer declared, by the
   * name the input mapping gives the value.
   *
   * @return The declared parameter names
   */
  Optional<List<String>> declaredTaskParams();

  /**
   * Whether something other than this application serves the tasks which this level covers
   * (<code>implemented-externally</code>). Then they need no <code>&#64;WorkflowTask</code>
   * method. It is resolved over eight positions, and the most specific one wins (see
   * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#implementedExternally}).
   *
   * @return The value written at this level
   */
  Optional<Boolean> implementedExternally();

  /**
   * Where to load the VanillaBP BPMN files from which are NOT specific to any adapter. An
   * empty Optional arrives as <code>null</code> in the core model. The core then takes the
   * location from what the classpath says and does not ask for it (see decision 8 in the
   * repository's DECISIONS.md).
   *
   * @return The location, or an empty Optional to leave it to the convention
   */
  Optional<String> resourcesLocation();

  /**
   * The configuration of all known adapters. The key can be an adapter's identifier or a
   * custom identifier. For a custom identifier, the {@link AdapterConfiguration#type()}
   * property has to name the adapter identifier which the custom adapter is derived from.
   * For an adapter's own identifier, the {@link AdapterConfiguration#type()} property has
   * to stay unset.
   *
   * @return All adapters configured.
   */
  @ConfigDocMapKey("adapter-id")
  Map<String, AdapterConfiguration> adapters();

  /**
   * The properties of all workflow modules. The key is the workflow module's id.
   *
   * @return The section per workflow module, empty where the application configures none.
   *         The core adds a section for every module which the build found on the
   *         classpath. So a module which needs no configuration needs no section
   */
  @ConfigDocMapKey("workflow-module-id")
  Map<String, WorkflowModuleProperties> workflowModules();

  /**
   * The settings of the extensions of this application, keyed by extension id. What the
   * keys below an extension mean is up to that extension. VanillaBP owns the place and the
   * resolution against the overrides per workflow module
   * ({@link WorkflowModuleProperties#extensions()}), per workflow
   * ({@link WorkflowProperties#extensions()}) and per task
   * ({@link TaskProperties#extensions()}). Each of them may also be written for one adapter
   * ({@link AdapterConfiguration#extensions()} and {@link AdapterProperties#extensions()}).
   * That makes the eight positions of decision 7 in the repository's DECISIONS.md.
   *
   * @return The settings per extension
   */
  @ConfigDocMapKey("extension-id")
  Map<String, Map<String, String>> extensions();

  /**
   * The configuration of the phase-two outbox, which two-phase workflow starts use
   * (see {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
   *
   * @return The outbox configuration
   */
  PhaseTwoOutboxProperties outbox();

  /**
   * The configuration of the election cache. It is asked for operations on existing
   * workflows (see {@link io.vanillabp.integration.spi.WorkflowAdapterCache}).
   *
   * @return The election cache's configuration
   */
  WorkflowAdapterCacheProperties workflowAdapterCache();

  /**
   * What VanillaBP does about a workflow aggregate whose store is not covered by the
   * transaction it opens.
   *
   * @return The transaction configuration
   */
  TransactionsProperties transactions();

  /**
   * What VanillaBP does about a prioritized adapter which cannot locate workflows.
   *
   * @return The election configuration
   */
  ElectionProperties election();

  /**
   * What VanillaBP does with the records of processed task deliveries.
   *
   * @return The delivery configuration
   */
  DeliveryProperties delivery();

  /**
   * What VanillaBP publishes as metrics.
   *
   * @return The metrics configuration
   */
  MetricsProperties metrics();

  /**
   * The adapter configuration. The VanillaBP Quarkus extension of each adapter defines the
   * properties in detail.
   */
  interface AdapterConfiguration {

    /**
     * The adapter's type for a custom adapter identifier, or an empty Optional for the
     * adapter's own identifier.
     *
     * @return The adapter's type
     */
    Optional<String> type();

    /**
     * What to do when the deployment of BPMS resources fails for this adapter.
     * <code>fail</code> (the default) stops the boot of the application. <code>warn</code>
     * logs the failure of an adapter which is NOT the first priority, and the application
     * still starts. A failure of the first-priority adapter always fails the boot. Upper and
     * lower case do not matter. An invalid value fails the configuration with a message
     * which names the allowed values.
     *
     * @return The deployment-failure policy
     */
    Optional<DeploymentFailurePolicy> deploymentFailure();

    /**
     * Bound, although it is refused here. The permission to share a whole workflow aggregate
     * belongs to the single workflow. If a line is written at this level, the core answers
     * with a message which says where it belongs.
     *
     * @return The misplaced permission, if somebody wrote one
     */
    Optional<Boolean> allowFullSyncWithBpms();

    /**
     * Where to load the BPMN files from which are specific to the adapter. Adapter-scoped
     * properties are resolved by "the most specific wins". This section is the least
     * specific level of it. So it has the same keys per level as the workflow module, the
     * workflow and the task levels.
     *
     * @return The location, or an empty Optional to leave it to the convention (see
     *         decision 8 in the repository's DECISIONS.md). Which locations a deployment
     *         searches, and in which order, is answered by
     *         {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#getAdapterResourcesLocationsFor(String, String)}
     */
    Optional<String> resourcesLocation();

    /**
     * How the identifiers of a workflow module are kept apart from those of other workflow
     * modules (<code>none</code>, <code>by-adapter</code>, <code>use-prefix</code>; see
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}). It is scoped per
     * adapter. The most specific configured value wins (workflow &gt; workflow module &gt;
     * adapter). The default is <code>by-adapter</code>.
     *
     * @return The name-clash-avoidance mode
     */
    Optional<io.vanillabp.integration.adapter.spi.NameClashAvoidance> nameClashAvoidance();

    /**
     * Whether a task definition is scoped by the BPMN process ID as well as by the workflow
     * module ID. This matters only for <code>use-prefix</code>. Defaults to
     * <code>true</code>.
     *
     * @return Whether task definitions are scoped per BPMN process
     */
    Optional<Boolean> prefixTaskDefinitionsPerProcess();

    /**
     * Whether VanillaBP remembers the task deliveries of this BPMS. Then a repeated delivery
     * reports the recorded outcome again and does not run the <code>&#64;WorkflowTask</code>
     * method a second time (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     * Defaults to <code>true</code>. It has an effect only for a BPMS which may repeat a
     * delivery at all.
     *
     * @return Whether deliveries are deduplicated
     */
    Optional<Boolean> deduplicateDeliveries();

    /**
     * Whether something other than this application serves the tasks which this level covers
     * (<code>implemented-externally</code>). Then they need no <code>&#64;WorkflowTask</code>
     * method. It is resolved over eight positions, and the most specific one wins (see
     * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#implementedExternally}).
     *
     * @return The value written at this level
     */
    Optional<Boolean> implementedExternally();

    /**
     * The versions of a BPMN process which this application does not serve any more. Each is
     * written like the <code>version</code> attribute of <code>&#64;WorkflowTask</code> and
     * its sibling annotations (<code>&lt;4</code>, <code>1-3</code>, <code>v1.0..v2.0</code>,
     * a version tag). It is scoped per adapter. The most specific configured level wins
     * (workflow &gt; workflow module &gt; adapter).
     *
     * @return The outfaded versions
     */
    Optional<List<String>> outfadedVersions();

    /**
     * What happens when workflows still run on an outfaded version: <code>log</code>
     * (the default, a FATAL message) or <code>fail</code> (the boot aborts).
     *
     * @return The policy
     */
    Optional<OutfadedVersionsInUsePolicy> outfadedVersionsInUse();

    /**
     * The settings of an extension FOR THIS ADAPTER, keyed by extension id. An extension is
     * configured for every configured adapter on its own. So two adapters of the same BPMS
     * type may get different settings. And what an adapter gets wins over what the same
     * level says in general.
     *
     * @return The extension settings of this adapter
     */
    @ConfigDocMapKey("extension-id")
    Map<String, Map<String, String>> extensions();

  }

  /**
   * The adapter properties of one level (workflow module, workflow or task). Adapter-scoped
   * properties are resolved over these levels, and the most specific one wins.
   */
  interface AdapterProperties {

    /**
     * Bound, although it is refused here. The permission to share a whole workflow aggregate
     * belongs to the single workflow. If a line is written at this level, the core answers
     * with a message which says where it belongs.
     *
     * @return The misplaced permission, if somebody wrote one
     */
    Optional<Boolean> allowFullSyncWithBpms();

    /**
     * Where to load the BPMN files from which are specific to the adapter. It is optional,
     * because an adapter section of a level may have only adapter-specific keys. Where the
     * resources location is really needed, the core checks it and gives a guiding message.
     * This interface serves the workflow module, the workflow and the task alike. If a
     * location is written below the workflow module, the core answers with a message which
     * names the keys where it belongs (see decision 80 in the repository's DECISIONS.md).
     *
     * @return The location which this level sets, or an empty Optional where it says
     *         nothing about it. The deployment reads it at the workflow module, see
     *         {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#getAdapterResourcesLocationsFor(String, String)}
     */
    Optional<String> resourcesLocation();

    /**
     * How the identifiers of a workflow module are kept apart from those of other workflow
     * modules (<code>none</code>, <code>by-adapter</code>, <code>use-prefix</code>; see
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}). It is scoped per
     * adapter. The most specific configured value wins (workflow &gt; workflow module &gt;
     * adapter). The default is <code>by-adapter</code>.
     *
     * @return The name-clash-avoidance mode
     */
    Optional<io.vanillabp.integration.adapter.spi.NameClashAvoidance> nameClashAvoidance();

    /**
     * Whether a task definition is scoped by the BPMN process ID as well as by the workflow
     * module ID. This matters only for <code>use-prefix</code>. Defaults to
     * <code>true</code>.
     *
     * @return Whether task definitions are scoped per BPMN process
     */
    Optional<Boolean> prefixTaskDefinitionsPerProcess();

    /**
     * Whether VanillaBP remembers the task deliveries of this BPMS. Then a repeated delivery
     * reports the recorded outcome again and does not run the <code>&#64;WorkflowTask</code>
     * method a second time (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     * Defaults to <code>true</code>. It has an effect only for a BPMS which may repeat a
     * delivery at all.
     *
     * @return Whether deliveries are deduplicated
     */
    Optional<Boolean> deduplicateDeliveries();

    /**
     * Whether something other than this application serves the tasks which this level covers
     * (<code>implemented-externally</code>). Then they need no <code>&#64;WorkflowTask</code>
     * method. It is resolved over eight positions, and the most specific one wins (see
     * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#implementedExternally}).
     *
     * @return The value written at this level
     */
    Optional<Boolean> implementedExternally();

    /**
     * The versions of a BPMN process which this application does not serve any more. Each is
     * written like the <code>version</code> attribute of <code>&#64;WorkflowTask</code> and
     * its sibling annotations (<code>&lt;4</code>, <code>1-3</code>, <code>v1.0..v2.0</code>,
     * a version tag). It is scoped per adapter. The most specific configured level wins
     * (workflow &gt; workflow module &gt; adapter).
     *
     * @return The outfaded versions
     */
    Optional<List<String>> outfadedVersions();

    /**
     * What happens when workflows still run on an outfaded version: <code>log</code>
     * (the default, a FATAL message) or <code>fail</code> (the boot aborts).
     *
     * @return The policy
     */
    Optional<OutfadedVersionsInUsePolicy> outfadedVersionsInUse();

    /**
     * The settings of an extension FOR THIS ADAPTER, keyed by extension id. An extension is
     * configured for every configured adapter on its own. So two adapters of the same BPMS
     * type may get different settings. And what an adapter gets wins over what the same
     * level says in general.
     *
     * @return The extension settings of this adapter
     */
    @ConfigDocMapKey("extension-id")
    Map<String, Map<String, String>> extensions();

  }

  /**
   * The configuration of the phase-two outbox.
   */
  interface PhaseTwoOutboxProperties {

    /**
     * The fixed delay between two polls in the background for outbox entries which are
     * committed but not processed. Polling is needed for crash recovery and retries. Right
     * after a commit, the entry is dispatched at once, whatever this delay is.
     *
     * @return The poll interval
     */
    @WithDefault("PT10S")
    Duration pollInterval();

    /**
     * The time until the FIRST retry after a failed dispatch. Every further attempt doubles
     * it until it reaches <code>max-attempt-frequency</code>.
     *
     * @return The retry backoff
     */
    @WithDefault("PT30S")
    Duration attemptFrequency();

    /**
     * The longest time which the growing backoff reaches. So a BPMS which comes back is
     * noticed within this time, however long it was away.
     *
     * @return The cap of the retry backoff
     */
    @WithDefault("PT5M")
    Duration maxAttemptFrequency();

    /**
     * After how many failed attempts an entry is blocked and not retried any more. With the
     * two defaults above, fifty attempts cover an outage of about four hours. So an entry
     * which ends up blocked is broken. It is not one whose BPMS was away for a while.
     * Blocked entries have to be fixed by hand (e.g. by cleaning up the outbox table).
     *
     * @return The maximum number of attempts
     */
    @WithDefault("50")
    int blockAfterAttempts();

    /**
     * How long an entry may wait for a BPMS which does not report its workflow yet. It is
     * counted from the moment the entry was written. Waiting like this uses no attempts. The
     * entry is blocked once this time has passed. It is not set by default. Then it is the
     * time which the attempts above take with the growing backoff, about four hours with the
     * defaults.
     *
     * @return How long an entry may wait for its BPMS, empty for the time the attempts take
     */
    Optional<Duration> waitForVisibilityAtMost();

    /**
     * How many entries the JDBC outbox dispatches at the same time. The workflow aggregate
     * decides which thread takes an entry. So two operations of one workflow keep the order
     * in which they were written. Operations of different workflows travel at the same time.
     * The default is four, which is small on purpose. Every dispatch costs a database
     * connection and a call to the BPMS.
     *
     * @return The number of dispatch threads
     */
    @WithDefault("4")
    int dispatchThreads();

    /**
     * Whether the table for outbox entries is created automatically. Switch this off if the
     * database schema is managed by hand or by a tool (e.g. Flyway or Liquibase).
     *
     * @return Whether to create the outbox table automatically
     */
    @WithDefault("true")
    boolean createSchema();

    /**
     * How long entries which were dispatched successfully (marked as DONE) are kept before
     * they are deleted in the background. Kept entries hold the deduplication window of the
     * idempotency contract open after the dispatch.
     *
     * @return The retention period of DONE entries
     */
    @WithDefault("P7D")
    Duration retention();

    /**
     * When the outbox does its housekeeping, and in which time zone.
     *
     * @return The housekeeping configuration
     */
    HousekeepingProperties housekeeping();

    /**
     * The configuration of the JDBC/Agroal-based default outbox.
     *
     * @return The JDBC outbox configuration
     */
    JdbcOutboxProperties jdbc();

    /**
     * The configuration of the MongoDB-based default outbox.
     *
     * @return The MongoDB outbox configuration
     */
    MongoOutboxProperties mongo();

  }

  /**
   * When the outbox removes the entries whose retention has passed, and the payloads which
   * no entry names any more (<code>vanillabp.outbox.housekeeping.*</code>). Both used to run
   * at the end of every poll. Now they run in a window at night. Inside that window, the
   * outbox does as much as fits.
   */
  interface HousekeepingProperties {

    /**
     * When the window opens, in the zone below. A window whose end lies before its start
     * crosses midnight. So <code>23:00</code> to <code>01:00</code> is two hours.
     *
     * @return The local time the window opens at
     */
    @WithDefault("04:00")
    LocalTime start();

    /**
     * When the window closes. It is a deadline and not a promise. A batch which is running
     * when the window closes runs to its end. The next one does not start.
     *
     * @return The local time the window closes at
     */
    @WithDefault("05:00")
    LocalTime end();

    /**
     * The zone in which the two times above are read. Write it the way <code>ZoneId</code>
     * spells one, for example <code>Europe/Vienna</code>. Unset means the zone of the JVM.
     * If the JVM runs on UTC and this key is missing, the startup warns. Then "four in the
     * morning" would mean four UTC.
     *
     * @return The zone of the window
     */
    Optional<String> zone();

  }

  /**
   * The configuration of the default outbox based on JDBC and Agroal. Both default outboxes
   * (JDBC and MongoDB) may be active in the same application. Each workflow aggregate is
   * served by the outbox which matches its persistence.
   */
  interface JdbcOutboxProperties {

    /**
     * Whether the default outbox based on JDBC is active when a datasource is there. Switch
     * it off if the application defines its own PhaseTwoOutbox bean and does not want the
     * default, with its table and its dispatcher in the background.
     *
     * @return Whether the JDBC default outbox is active
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The name of the table storing outbox entries (default
     * <code>VANILLABP_PHASE_TWO_OUTBOX</code>). Every outbox instance needs its own table.
     * Two dispatchers which poll the same table would compete and dispatch twice.
     *
     * @return The outbox table name
     */
    Optional<String> table();

    /**
     * The name of the table which stores the payloads of phase-two calls that have one.
     * There is one table per outbox, for the same reason as the outbox has its own. Unset
     * means the name of the outbox table plus <code>_PAYLOAD</code>. So an application which
     * renames the outbox renames the payloads with it.
     *
     * @return The payload table name
     */
    Optional<String> payloadTable();

    /**
     * The name of the table which stores the records of processed task deliveries (default
     * <code>VANILLABP_TASK_DELIVERY</code>). The name is in this section, because the
     * delivery log uses the store settings of the outbox. Unlike the payload table, it does
     * not follow a renamed outbox, because it is a store of its own. An application which
     * sets it uses the same name for <code>io.vanillabp:vanillabp-schema</code>.
     *
     * @return The delivery table name
     */
    Optional<String> deliveryTable();

    /**
     * The name of the table in which the nightly housekeeping takes its lease (default
     * <code>VANILLABP_HOUSEKEEPING</code>). It has one row per store. The row says who does
     * the housekeeping of that store and until when. So only one node of a cluster measures
     * its own work. Like the delivery table, it does not follow a renamed outbox. It has a
     * row per store and does not belong to one. An application which sets it uses the same
     * name for <code>io.vanillabp:vanillabp-schema</code>.
     *
     * @return The housekeeping table name
     */
    Optional<String> housekeepingTable();

  }

  /**
   * The configuration of the default outbox based on MongoDB. Both default outboxes (JDBC
   * and MongoDB) may be active in the same application. Each workflow aggregate is served by
   * the outbox which matches its persistence.
   */
  interface MongoOutboxProperties {

    /**
     * Whether the default outbox based on MongoDB is active when a MongoDB client is there.
     * Switch it off if the application defines its own PhaseTwoOutbox bean and does not
     * want the default, with its collection and its dispatcher in the background.
     *
     * @return Whether the MongoDB default outbox is active
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The name of the collection which stores outbox entries. Every outbox instance needs
     * its own collection. Two dispatchers which poll the same collection would compete and
     * dispatch twice.
     *
     * @return The outbox collection name
     */
    @WithDefault("vanillabp-phase-two-outbox")
    String collection();

    /**
     * The name of the collection which stores the payloads of phase-two calls that have one.
     * There is one collection per outbox, for the same reason as the outbox has its own.
     * Unset means the name of the outbox collection plus <code>-payloads</code>. So an
     * application which renames the outbox renames the payloads with it. The core resolves
     * the name. That is why there is no <code>@WithDefault</code> here.
     *
     * @return The payload collection name
     */
    Optional<String> payloadCollection();

    /**
     * The name of the collection which stores the records of processed task deliveries. It
     * is in this section, because the delivery log uses the store settings of the outbox.
     * The only setting which the log owns alone is <code>vanillabp.delivery.retention</code>.
     * Keep it apart from the two collections above. Three stores which share one collection
     * would read each other's documents.
     *
     * @return The delivery-log collection name
     */
    @WithDefault("vanillabp-task-deliveries")
    String deliveryCollection();

    /**
     * The name of the collection in which the nightly housekeeping takes its lease. It has
     * one document per store. The document says who does the housekeeping of that store and
     * until when. So only one node of a cluster measures its own work. Keep it apart from
     * the three collections above.
     *
     * @return The housekeeping collection name
     */
    @WithDefault("vanillabp-housekeeping")
    String housekeepingCollection();

  }

  /**
   * The configuration of the default implementation of the election cache, which lives in
   * memory. Both limits are hard on purpose. Entries are hints. So losing one costs an extra
   * round of probing, but it never makes a result wrong.
   */
  interface WorkflowAdapterCacheProperties {

    /**
     * The maximum number of entries. Beyond that, the entry which was used least recently is
     * dropped. Raise it if the application keeps more workflows busy than the cache holds.
     * VanillaBP warns about that.
     *
     * @return The maximum number of entries
     */
    @WithDefault("10000")
    int maxEntries();

    /**
     * How long an entry is kept, counted from the moment it was stored.
     *
     * @return The time-to-live of an entry
     */
    @WithDefault("PT1H")
    Duration timeToLive();

    /**
     * How long the entry of a workflow which ENDED is kept. It is much shorter than
     * {@link #timeToLive()}, because such an entry cannot become useful again.
     *
     * @return The time-to-live of the entry of an ended workflow
     */
    @WithDefault("PT5M")
    Duration endedTimeToLive();

    /**
     * Whether the end of a workflow is reported for this cache. Then an ended workflow lets
     * go of its entry after {@link #endedTimeToLive()}. Switching it on adds a listener or a
     * worker, depending on the BPMS, to every deployed process.
     *
     * @return Whether an ended workflow releases its entry early
     */
    @WithDefault("false")
    boolean releaseOnWorkflowEnd();

  }

  /**
   * What VanillaBP publishes as metrics. There is one setting. It exists because reading a
   * metric must not cost anything worth noticing.
   */
  interface MetricsProperties {

    /**
     * Some gauges have to ask somebody for their value, for example the number of waiting
     * outbox entries. This is how long such a measurement is reused before it is taken
     * again. Ten seconds is one collection interval, a little under the default scrape of
     * Prometheus. <code>PT0S</code> measures on every collection.
     *
     * @return The duration one measurement is held for
     */
    @WithDefault("PT10S")
    Duration gaugeCache();

  }

  /**
   * The properties of a workflow module.
   */
  interface WorkflowModuleProperties {

    /**
     * The priorities of adapters specific to a workflow module.
     *
     * @return The order in which the adapters of this module are asked, or an empty
     *         Optional to keep the order which the application configured for all modules.
     *         The core reads an empty list as "this level says nothing"
     * @see QuarkusMigrationAdapterProperties#prioritizedAdapters()
     */
    Optional<List<String>> prioritizedAdapters();

    /**
     * The properties of adapters specific to this workflow module.
     *
     * @return The section per adapter id. It is the second least specific level where an
     *         adapter setting may be written
     */
    @ConfigDocMapKey("adapter-id")
    Map<String, AdapterProperties> adapters();

    /**
     * The properties of workflows specific to this workflow module. The key is the
     * BPMN process ID.
     *
     * @return The section per workflow of this module. A process ID which no deployed BPMN
     *         has does not stop the boot. It gives a warning which names the key and the
     *         process IDs which the module does have
     */
    @ConfigDocMapKey("bpmn-process-id")
    Map<String, WorkflowProperties> workflows();

    /**
     * Bound, although it is refused here. The permission to share a whole workflow aggregate
     * belongs to the single workflow. If a line is written at this level, the core answers
     * with a message which says where it belongs.
     *
     * @return The misplaced permission, if somebody wrote one
     */
    Optional<Boolean> allowFullSyncWithBpms();

    /**
     * Whether the expressions of this module's BPMN models are meant as they are. A workflow
     * which says nothing keeps this answer.
     *
     * @return The answer of this level, an empty Optional leaving it to the level above
     */
    Optional<Boolean> acceptExpressionsInTheModel();

    /**
     * The <code>&#64;TaskParam</code> parameters whose type the developer declared, by the
     * name the input mapping gives the value.
     *
     * @return The declared parameter names
     */
    Optional<List<String>> declaredTaskParams();

    /**
     * Whether something other than this application serves the tasks which this level covers
     * (<code>implemented-externally</code>). Then they need no <code>&#64;WorkflowTask</code>
     * method. It is resolved over eight positions, and the most specific one wins (see
     * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#implementedExternally}).
     *
     * @return The value written at this level
     */
    Optional<Boolean> implementedExternally();

    /**
     * Overrides <code>vanillabp.transactions</code> for this workflow module.
     *
     * @return The transaction configuration of this workflow module
     */
    TransactionsProperties transactions();

    /**
     * Overrides <code>vanillabp.election</code> for this workflow module.
     *
     * @return The election configuration of this workflow module
     */
    ElectionProperties election();

    /**
     * Overrides <code>vanillabp.delivery</code> for this workflow module.
     *
     * @return The delivery configuration of this workflow module
     */
    DeliveryProperties delivery();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
     * module, keyed by extension id.
     *
     * @return The extension settings of this workflow module
     */
    @ConfigDocMapKey("extension-id")
    Map<String, Map<String, String>> extensions();

  }

  /**
   * What VanillaBP does about a workflow aggregate whose store is provably not covered by the
   * transaction which VanillaBP opens. It refuses to start, or it accepts this and keeps the
   * warning.
   */
  interface TransactionsProperties {

    /**
     * Whether writes to a store outside VanillaBP's transaction are accepted. By default they
     * are refused. Then the startup ends with a message which names the fix.
     *
     * @return The setting, an empty Optional meaning "whatever applies globally"
     */
    Optional<io.vanillabp.integration.adapter.migration.config.TransactionsProperties.UnguardedAggregateWrites> unguardedAggregateWrites();

  }

  /**
   * What VanillaBP does about a prioritized adapter which cannot ask its BPMS whether it
   * holds a workflow. It refuses to start, or it accepts the routing by the order of the
   * list.
   */
  interface ElectionProperties {

    /**
     * Whether an adapter which has to guess is accepted next to other adapters. By default it
     * is refused. Then the startup ends with a message which names the fix.
     *
     * @return The setting, an empty Optional meaning "whatever applies globally"
     */
    Optional<io.vanillabp.integration.adapter.migration.config.ElectionProperties.GuessingAdapters> guessingAdapters();

  }

  /**
   * What VanillaBP does with the records of processed task deliveries. It keeps them until
   * the retention has passed, or it deletes them as soon as their workflow ends.
   */
  interface DeliveryProperties {

    /**
     * Whether the records of a workflow are deleted when it ends. The default is
     * <code>false</code>. Switching it on makes every deployed model pay for the message
     * about the end of a workflow.
     *
     * @return The setting, an empty Optional meaning "whatever applies globally"
     */
    Optional<Boolean> releaseOnWorkflowEnd();

    /**
     * How long a task may stay open before VanillaBP reports it. The default is thirty days,
     * and VanillaBP only reports it. See the core's
     * {@link io.vanillabp.integration.adapter.migration.config.DeliveryProperties#getMaxTaskAge()}.
     *
     * @return The setting, an empty Optional meaning "whatever the next less specific
     *         level says"
     */
    Optional<Duration> maxTaskAge();

    /**
     * Whether a delivery looks at the other tasks which VanillaBP believes are open in the
     * same workflow of the BPMS. It reports the ones which are gone as canceled. The default
     * is <code>true</code>, see the core's
     * {@link io.vanillabp.integration.adapter.migration.config.DeliveryProperties#getCheckOpenTasksOnDelivery()}.
     *
     * @return The setting, an empty Optional meaning "whatever the next less specific
     *         level says"
     */
    Optional<Boolean> checkOpenTasksOnDelivery();

    /**
     * How many other open tasks one delivery probes at most. The default is ten, see the
     * core's
     * {@link io.vanillabp.integration.adapter.migration.config.DeliveryProperties#getMaxOpenTasksChecked()}.
     *
     * @return The setting, an empty Optional meaning "whatever the next less specific
     *         level says"
     */
    Optional<Integer> maxOpenTasksChecked();

    /**
     * How long the record of a processed task delivery is kept. It is read GLOBALLY only,
     * under <code>vanillabp.delivery.retention</code>. Where it is not set, it is the same as
     * <code>vanillabp.outbox.retention</code>.
     *
     * @return The setting, an empty Optional meaning "whatever the outbox retention says"
     */
    Optional<Duration> retention();

    /**
     * How long the row about a started workflow is kept, counted from the start. It is read
     * GLOBALLY only, under <code>vanillabp.delivery.workflow-start-retention</code>. The
     * default is thirty days, see the core's
     * {@link io.vanillabp.integration.adapter.migration.config.DeliveryProperties#getWorkflowStartRetention()}.
     *
     * @return The setting, an empty Optional meaning "thirty days"
     */
    Optional<Duration> workflowStartRetention();

    /**
     * Whether that row is kept after its period while the workflow aggregate it names still
     * exists. It is read GLOBALLY only, under
     * <code>vanillabp.delivery.keep-workflow-start-while-aggregate-exists</code>. The default
     * is <code>false</code>, because it costs one read of the application's own database per
     * expired row.
     *
     * @return The setting, an empty Optional meaning "no, the period decides alone"
     */
    Optional<Boolean> keepWorkflowStartWhileAggregateExists();

  }

  /**
   * The properties of a workflow of a workflow module.
   */
  interface WorkflowProperties {

    /**
     * The priorities of adapters specific to a workflow.
     *
     * @return The order in which the adapters of this workflow are asked, or an empty
     *         Optional to keep the order of the workflow module, or else of the application
     * @see QuarkusMigrationAdapterProperties#prioritizedAdapters()
     */
    Optional<List<String>> prioritizedAdapters();

    /**
     * The properties of adapters specific to this workflow.
     *
     * @return The section per adapter id. It wins over what the workflow module and the
     *         adapter itself say about the same key
     */
    @ConfigDocMapKey("adapter-id")
    Map<String, AdapterProperties> adapters();

    /**
     * The properties of the workflow's BPMN tasks. The key is the task ID (task definition).
     * This is the most specific of the four levels which a setting is resolved from. The
     * core asks it first
     * ({@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#resolveForAdapter},
     * see decision 7 in the repository's DECISIONS.md). The delivery settings and the
     * settings of an extension are resolved over the same four levels. See
     * QuarkusMigrationAdapterPropertiesMapperTest#maxTaskAgeTravelsThroughEveryLevel.
     *
     * @return The section per task, keyed by task ID
     */
    @ConfigDocMapKey("task-definition")
    Map<String, TaskProperties> tasks();

    /**
     * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS. This is the
     * only level where this permission may be written. It is not inherited from anywhere.
     *
     * @return The permission, an empty Optional meaning "not allowed"
     */
    Optional<Boolean> allowFullSyncWithBpms();

    /**
     * Whether the expressions of this workflow's BPMN model are meant as they are. This is
     * the most specific of the three levels. A startup message names this one.
     *
     * @return The answer of this level, an empty Optional leaving it to the level above
     */
    Optional<Boolean> acceptExpressionsInTheModel();

    /**
     * The values of the workflow aggregate which this workflow declares for the BPMS. An
     * entry names a path in the aggregate. It may also name what is shared while the path
     * does not resolve.
     *
     * @return The declared values
     */
    Optional<List<String>> declaredAggregateValues();

    /**
     * The <code>&#64;TaskParam</code> parameters whose type the developer declared, by the
     * name the input mapping gives the value.
     *
     * @return The declared parameter names
     */
    Optional<List<String>> declaredTaskParams();

    /**
     * Whether something other than this application serves the tasks which this level covers
     * (<code>implemented-externally</code>). Then they need no <code>&#64;WorkflowTask</code>
     * method. It is resolved over eight positions, and the most specific one wins (see
     * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#implementedExternally}).
     *
     * @return The value written at this level
     */
    Optional<Boolean> implementedExternally();

    /**
     * Overrides <code>vanillabp.delivery</code> for this workflow.
     *
     * @return The delivery configuration of this workflow
     */
    DeliveryProperties delivery();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow,
     * keyed by extension id. This is what an extension gets for ONE BPMN process. A key
     * which the workflow says nothing about keeps what the workflow module or the global
     * section says.
     *
     * @return The extension settings of this workflow
     */
    @ConfigDocMapKey("extension-id")
    Map<String, Map<String, String>> extensions();

  }

  /**
   * The properties of a single BPMN task of a workflow. Adapter-scoped properties are
   * resolved by "the most specific wins", and this is the MOST specific level.
   */
  interface TaskProperties {

    /**
     * The properties of adapters specific to this task.
     *
     * @return The section per adapter id. It is the most specific place where a setting of
     *         one adapter may be written. So it wins over every other level
     */
    @ConfigDocMapKey("adapter-id")
    Map<String, AdapterProperties> adapters();

    /**
     * Overrides <code>vanillabp.delivery</code> for this task. This is the most specific level
     * where the maximum age of an open task may be set.
     *
     * @return The delivery configuration of this task
     */
    DeliveryProperties delivery();

    /**
     * The <code>&#64;TaskParam</code> parameters whose type the developer declared, by the
     * name the input mapping gives the value.
     *
     * @return The declared parameter names
     */
    Optional<List<String>> declaredTaskParams();

    /**
     * Whether something other than this application serves the tasks which this level covers
     * (<code>implemented-externally</code>). Then they need no <code>&#64;WorkflowTask</code>
     * method. It is resolved over eight positions, and the most specific one wins (see
     * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#implementedExternally}).
     *
     * @return The value written at this level
     */
    Optional<Boolean> implementedExternally();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task, keyed
     * by extension id. This is the most specific level where an extension setting may be
     * written. A key which the task says nothing about keeps what the three less specific
     * levels say.
     *
     * @return The extension settings of this task
     */
    @ConfigDocMapKey("extension-id")
    Map<String, Map<String, String>> extensions();

  }

}
