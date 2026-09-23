package io.vanillabp.integration.runtime.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * The config root uses phase {@link ConfigPhase#RUN_TIME} because parts of the
 * configuration originate from workflow-module-specific config files which are
 * added by generated config builders to the static-init/runtime config only —
 * they are not visible to the build-time configuration. Additionally, VanillaBP
 * configuration (e.g. adapter endpoints) must be overridable per environment.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = QuarkusMigrationAdapterProperties.PREFIX)
public interface QuarkusMigrationAdapterProperties {

  /**
   * The root of every key VanillaBP reads. It is the prefix of this config mapping, so
   * an application writes <code>vanillabp.adapters.&lt;id&gt;.*</code> for one adapter,
   * and it is spelled into the messages which name the key a reader would write, so the
   * message and the mapping cannot drift apart.
   */
  String PREFIX = "vanillabp";

  /**
   * Return the list of adapters, ordered by priority. New workflows will be started
   * using the first adapter. Other actions will target the BPMS the workflow is running in.
   * <p>
   * Each item has to be a key in the map of {@link QuarkusMigrationAdapterProperties#adapters}.
   *
   * @return Ordered list of adapters to be used
   */
  Optional<List<String>> prioritizedAdapters();

  /**
   * Adapter ids this application USED to have and deliberately does not configure any
   * more - the last step of a BPMS migration. VanillaBP persists the adapter
   * id of an outbox entry and of every delivery record, so an id which such an entry
   * still waits for and which nobody serves is reported at startup: it means the id was
   * RENAMED, which loses workflows, or that it was removed too early. Naming it here says
   * that it is the second case, and turns the message into a DEBUG line.
   *
   * @return The adapter ids retired deliberately
   */
  Optional<List<String>> retiredAdapters();

  /**
   * Bound although it is refused here: the permission to share a whole workflow
   * aggregate belongs to the single workflow, and a line written at this level is
   * answered by the core with a message saying where it belongs.
   *
   * @return The misplaced permission, if somebody wrote one
   */
  Optional<Boolean> allowFullSyncWithBpms();

  /**
   * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
   * An empty Optional arrives as <code>null</code> in the core model, which then derives
   * the location from what the classpath says rather than asking for it (see decision 8
   * in the repository's DECISIONS.md).
   *
   * @return The location, or an empty Optional to leave it to the convention
   */
  Optional<String> resourcesLocation();

  /**
   * The configuration of all adapters known. The key can be an adapter's identifier
   * or a custom identifier. In case of a custom identifier the {@link AdapterConfiguration#type()}
   * property has to point to the adapter identifier the custom adapter is derived from.
   * In case of a non-custom adapter identifier the {@link AdapterConfiguration#type()}
   * property has to be undefined.
   *
   * @return All adapters configured.
   */
  Map<String, AdapterConfiguration> adapters();

  /**
   * The properties of all workflow modules. The key is the workflow module's id.
   *
   * @return The section per workflow module, empty where the application configures
   *         none - the core adds a section for every module the build found on the
   *         classpath, so a module which needs nothing configured needs no section
   */
  Map<String, WorkflowModuleProperties> workflowModules();

  /**
   * The settings of the extensions of this application, keyed by extension id. What the
   * keys below an extension mean is that extension's own business - VanillaBP owns the
   * location and the resolution against the overrides per workflow module
   * ({@link WorkflowModuleProperties#extensions()}), per workflow
   * ({@link WorkflowProperties#extensions()}) and per task
   * ({@link TaskProperties#extensions()}), each of which may be written for one adapter
   * as well ({@link AdapterConfiguration#extensions()} and
   * {@link AdapterProperties#extensions()}). That makes the eight positions of decision 53
   * in the repository's DECISIONS.md.
   *
   * @return The settings per extension
   */
  Map<String, Map<String, String>> extensions();

  /**
   * The configuration of the phase-two outbox used for two-phase workflow starts
   * (see {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
   *
   * @return The outbox configuration
   */
  PhaseTwoOutboxProperties outbox();

  /**
   * The configuration of the election cache consulted for operations on existing
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
   * The adapter configuration. The properties in detail are defined by the
   * respective VanillaBP adapter Quarkus extension.
   */
  interface AdapterConfiguration {

    /**
     * The adapter's type in case of a custom adapter identifier or an empty Optional in case
     * of a non-custom adapter identifier.
     *
     * @return The adapter's type
     */
    Optional<String> type();

    /**
     * How to treat a failing deployment of BPMS resources for this adapter:
     * <code>fail</code> (default) aborts booting of the application;
     * <code>warn</code> logs the failure of a NON-first-priority adapter and the
     * application still starts (a failure of the first-priority adapter always
     * fails the boot). The value is converted case-insensitively; an invalid value
     * fails the configuration naming the allowed values.
     *
     * @return The deployment-failure policy
     */
    Optional<DeploymentFailurePolicy> deploymentFailure();

    /**
     * Bound although it is refused here: the permission to share a whole workflow
     * aggregate belongs to the single workflow, and a line written at this level is
     * answered by the core with a message saying where it belongs.
     *
     * @return The misplaced permission, if somebody wrote one
     */
    Optional<Boolean> allowFullSyncWithBpms();

    /**
     * Where to load BPMN files from, which are specific to the adapter. This
     * section is the least specific level of the most-specific-wins resolution of
     * adapter-scoped properties, so it carries the same per-level keys as the
     * workflow-module, workflow and task levels.
     *
     * @return The location, or an empty Optional to leave it to the convention (see
     *         decision 8 in the repository's DECISIONS.md). Which locations a
     *         deployment searches, and in which order, is answered by
     *         {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#getAdapterResourcesLocationsFor(String, String)}
     */
    Optional<String> resourcesLocation();

    /**
     * How the identifiers of a workflow module are kept apart from those of other
     * workflow modules (<code>none</code>, <code>by-adapter</code>,
     * <code>use-prefix</code>; see
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}). Adapter-scoped:
     * the most specific configured value wins (workflow &gt; workflow module &gt;
     * adapter), the default is <code>by-adapter</code>.
     *
     * @return The name-clash-avoidance mode
     */
    Optional<io.vanillabp.integration.adapter.spi.NameClashAvoidance> nameClashAvoidance();

    /**
     * Whether a task definition is scoped by the BPMN process ID in addition to the
     * workflow module ID (only relevant for <code>use-prefix</code>). Defaults to
     * <code>true</code>.
     *
     * @return Whether task definitions are scoped per BPMN process
     */
    Optional<Boolean> prefixTaskDefinitionsPerProcess();

    /**
     * Whether VanillaBP remembers the task deliveries of this BPMS, so a repeated
     * delivery reports the recorded outcome again instead of running the
     * <code>&#64;WorkflowTask</code> method a second time (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). Defaults to
     * <code>true</code> and takes effect only for a BPMS which may repeat a delivery
     * at all.
     *
     * @return Whether deliveries are deduplicated
     */
    Optional<Boolean> deduplicateDeliveries();

    /**
     * The versions of a BPMN process this application does not serve any more, each
     * written in the grammar of the <code>version</code> attribute of
     * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>,
     * <code>1-3</code>, <code>v1.0..v2.0</code>, a version tag). Adapter-scoped: the
     * most specific configured level wins (workflow &gt; workflow module &gt; adapter).
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
     * What an extension is configured with FOR THIS ADAPTER, keyed by extension id. An
     * extension hangs on every configured adapter separately, so two adapters of the same
     * BPMS type may be told different things, and what an adapter is told beats what the
     * same level says in general.
     *
     * @return The extension settings of this adapter
     */
    Map<String, Map<String, String>> extensions();

  }

  /**
   * The adapter properties of a level of the most-specific-wins resolution of
   * adapter-scoped properties (workflow module, workflow or task).
   */
  interface AdapterProperties {

    /**
     * Bound although it is refused here: the permission to share a whole workflow
     * aggregate belongs to the single workflow, and a line written at this level is
     * answered by the core with a message saying where it belongs.
     *
     * @return The misplaced permission, if somebody wrote one
     */
    Optional<Boolean> allowFullSyncWithBpms();

    /**
     * Where to load BPMN files from, which are specific to the adapter. Optional:
     * an adapter section of a level may carry only adapter-specific keys (the
     * missing resources location is validated with a guiding message by the core
     * where it is actually required).
     *
     * @return The location this level sets, or an empty Optional where it says nothing
     *         about it. The deployment reads it at the workflow module, see
     *         {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#getAdapterResourcesLocationsFor(String, String)}
     */
    Optional<String> resourcesLocation();

    /**
     * How the identifiers of a workflow module are kept apart from those of other
     * workflow modules (<code>none</code>, <code>by-adapter</code>,
     * <code>use-prefix</code>; see
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}). Adapter-scoped:
     * the most specific configured value wins (workflow &gt; workflow module &gt;
     * adapter), the default is <code>by-adapter</code>.
     *
     * @return The name-clash-avoidance mode
     */
    Optional<io.vanillabp.integration.adapter.spi.NameClashAvoidance> nameClashAvoidance();

    /**
     * Whether a task definition is scoped by the BPMN process ID in addition to the
     * workflow module ID (only relevant for <code>use-prefix</code>). Defaults to
     * <code>true</code>.
     *
     * @return Whether task definitions are scoped per BPMN process
     */
    Optional<Boolean> prefixTaskDefinitionsPerProcess();

    /**
     * Whether VanillaBP remembers the task deliveries of this BPMS, so a repeated
     * delivery reports the recorded outcome again instead of running the
     * <code>&#64;WorkflowTask</code> method a second time (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). Defaults to
     * <code>true</code> and takes effect only for a BPMS which may repeat a delivery
     * at all.
     *
     * @return Whether deliveries are deduplicated
     */
    Optional<Boolean> deduplicateDeliveries();

    /**
     * The versions of a BPMN process this application does not serve any more, each
     * written in the grammar of the <code>version</code> attribute of
     * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>,
     * <code>1-3</code>, <code>v1.0..v2.0</code>, a version tag). Adapter-scoped: the
     * most specific configured level wins (workflow &gt; workflow module &gt; adapter).
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
     * What an extension is configured with FOR THIS ADAPTER, keyed by extension id. An
     * extension hangs on every configured adapter separately, so two adapters of the same
     * BPMS type may be told different things, and what an adapter is told beats what the
     * same level says in general.
     *
     * @return The extension settings of this adapter
     */
    Map<String, Map<String, String>> extensions();

  }

  /**
   * The configuration of the phase-two outbox.
   */
  interface PhaseTwoOutboxProperties {

    /**
     * The fixed delay between two background polls for committed-but-unprocessed
     * outbox entries. Polling is required for crash recovery and retries; right after
     * a commit the entry is dispatched immediately (independently of this delay).
     *
     * @return The poll interval
     */
    @WithDefault("PT10S")
    Duration pollInterval();

    /**
     * The distance to the FIRST retry after a failed dispatch. Every further attempt
     * doubles it until <code>max-attempt-frequency</code> is reached.
     *
     * @return The retry backoff
     */
    @WithDefault("PT30S")
    Duration attemptFrequency();

    /**
     * The longest distance the growing backoff reaches, so a BPMS which comes back is
     * noticed within it however long it was away.
     *
     * @return The cap of the retry backoff
     */
    @WithDefault("PT5M")
    Duration maxAttemptFrequency();

    /**
     * After how many failed attempts an entry is blocked (not retried any longer).
     * With the two defaults above, fifty attempts span an outage of about four hours,
     * so what ends up blocked is an entry which is broken rather than one whose BPMS
     * was away for a while. Blocked entries have to be fixed manually (e.g. by
     * cleaning up the outbox table).
     *
     * @return The maximum number of attempts
     */
    @WithDefault("50")
    int blockAfterAttempts();

    /**
     * How many entries the JDBC outbox dispatches at the same time. The workflow
     * aggregate decides which thread takes an entry, so two operations of one workflow
     * keep the order they were written in while operations of different workflows travel
     * at the same time. Four of them, which is small on purpose: every dispatch costs a
     * database connection and a call to the BPMS.
     *
     * @return The number of dispatch threads
     */
    @WithDefault("4")
    int dispatchThreads();

    /**
     * Whether the table used to store outbox entries is created automatically.
     * Disable this if the database schema is managed manually (e.g. by Flyway or
     * Liquibase).
     *
     * @return Whether to create the outbox table automatically
     */
    @WithDefault("true")
    boolean createSchema();

    /**
     * How long successfully dispatched entries (marked as DONE) are retained before
     * they are deleted asynchronously. Retained entries keep the deduplication
     * window of the idempotency contract open beyond dispatch.
     *
     * @return The retention period of DONE entries
     */
    @WithDefault("P7D")
    Duration retention();

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
   * The configuration of the JDBC/Agroal-based default outbox. Both default
   * outboxes (JDBC and MongoDB) may be active in the same application - each
   * workflow aggregate is served by the outbox matching its persistence.
   */
  interface JdbcOutboxProperties {

    /**
     * Whether the JDBC-based default outbox is active when a datasource is
     * available. Disable it if the application defines its own PhaseTwoOutbox bean
     * and the default (including its table and background dispatcher) is unwanted.
     *
     * @return Whether the JDBC default outbox is active
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The name of the table storing outbox entries (default
     * <code>VANILLABP_PHASE_TWO_OUTBOX</code>). Every outbox instance needs its own
     * table - two dispatchers polling the same table would compete and
     * double-dispatch.
     *
     * @return The outbox table name
     */
    Optional<String> table();

    /**
     * The name of the table storing the payloads of phase-two calls which carry one.
     * One table per outbox, for the reason the outbox itself has one. Unset means the
     * name of the outbox table plus <code>_PAYLOAD</code>, so an application which
     * renames the outbox renames the payloads with it.
     *
     * @return The payload table name
     */
    Optional<String> payloadTable();

  }

  /**
   * The configuration of the MongoDB-based default outbox. Both default outboxes
   * (JDBC and MongoDB) may be active in the same application - each workflow
   * aggregate is served by the outbox matching its persistence.
   */
  interface MongoOutboxProperties {

    /**
     * Whether the MongoDB-based default outbox is active when a MongoDB client is
     * available. Disable it if the application defines its own PhaseTwoOutbox bean
     * and the default (including its collection and background dispatcher) is
     * unwanted.
     *
     * @return Whether the MongoDB default outbox is active
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The name of the collection storing outbox entries. Every outbox instance
     * needs its own collection - two dispatchers polling the same collection would
     * compete and double-dispatch.
     *
     * @return The outbox collection name
     */
    @WithDefault("vanillabp-phase-two-outbox")
    String collection();

    /**
     * The name of the collection storing the payloads of phase-two calls which carry
     * one. One collection per outbox, for the reason the outbox itself has one. Unset
     * means the name of the outbox collection plus <code>-payloads</code>, so an
     * application which renames the outbox renames the payloads with it. The core
     * resolves it, which is why there is no <code>@WithDefault</code> here.
     *
     * @return The payload collection name
     */
    Optional<String> payloadCollection();

    /**
     * The name of the collection storing the records of processed task deliveries. It
     * lives in this section because the store settings of the delivery log are the
     * outbox' ones; what the log owns alone is
     * <code>vanillabp.delivery.retention</code>. Keep it apart from the two collections
     * above - three stores sharing one collection would read each other's documents.
     *
     * @return The delivery-log collection name
     */
    @WithDefault("vanillabp-task-deliveries")
    String deliveryCollection();

  }

  /**
   * The configuration of the election cache's in-memory default implementation.
   * Both bounds are hard on purpose - entries are hints, so losing one costs an
   * extra probing walk but never correctness.
   */
  interface WorkflowAdapterCacheProperties {

    /**
     * The maximum number of entries held - the least recently used entry is dropped
     * beyond that. Raise it if the application keeps more workflows hot than the
     * cache holds (VanillaBP warns about it).
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
     * How long the entry of a workflow which ENDED is kept - much shorter than
     * {@link #timeToLive()}, since such an entry cannot become useful again.
     *
     * @return The time-to-live of the entry of an ended workflow
     */
    @WithDefault("PT5M")
    Duration endedTimeToLive();

    /**
     * Whether the end of a workflow is reported for this cache's sake, so an ended
     * workflow lets go of its entry after {@link #endedTimeToLive()}. Switching it on
     * attaches a listener respectively a worker to every deployed process.
     *
     * @return Whether an ended workflow releases its entry early
     */
    @WithDefault("false")
    boolean releaseOnWorkflowEnd();

  }

  /**
   * What VanillaBP publishes as metrics. There is one setting, and it exists because
   * reading a metric must not cost anything worth noticing.
   */
  interface MetricsProperties {

    /**
     * How long the measurement of a gauge which has to ask somebody - the number of
     * waiting outbox entries, for instance - is reused before it is taken again. Ten
     * seconds is one collection interval, a little under Prometheus' default scrape;
     * <code>PT0S</code> measures on every collection.
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
     * @return The order the adapters of this module are asked in, or an empty Optional
     *         to keep the order the application configured for all modules - an empty
     *         list is what the core reads as "this level says nothing"
     * @see QuarkusMigrationAdapterProperties#prioritizedAdapters()
     */
    Optional<List<String>> prioritizedAdapters();

    /**
     * The properties of adapters specific to this workflow module.
     *
     * @return The section per adapter id, the second least specific level an adapter
     *         setting may be written at
     */
    Map<String, AdapterProperties> adapters();

    /**
     * The properties of workflows specific to this workflow module. The key is the
     * BPMN process ID.
     *
     * @return The section per workflow of this module. A process ID which no deployed
     *         BPMN carries does not stop the boot - it warns, naming the key and the
     *         process IDs the module does have
     */
    Map<String, WorkflowProperties> workflows();

    /**
     * Bound although it is refused here: the permission to share a whole workflow
     * aggregate belongs to the single workflow, and a line written at this level is
     * answered by the core with a message saying where it belongs.
     *
     * @return The misplaced permission, if somebody wrote one
     */
    Optional<Boolean> allowFullSyncWithBpms();

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
    Map<String, Map<String, String>> extensions();

  }

  /**
   * What VanillaBP does about a workflow aggregate whose store is demonstrably not covered
   * by the transaction it opens: refuse to start, or accept it and keep the warning.
   */
  interface TransactionsProperties {

    /**
     * Whether writes to a store outside VanillaBP's transaction are accepted. The default
     * is to reject them, which ends the startup with a message naming the fix.
     *
     * @return The setting, an empty Optional meaning "whatever applies globally"
     */
    Optional<io.vanillabp.integration.adapter.migration.config.TransactionsProperties.UnguardedAggregateWrites> unguardedAggregateWrites();

  }

  /**
   * What VanillaBP does about a prioritized adapter which cannot ask its BPMS whether it
   * holds a workflow: refuse to start, or accept the routing by list order.
   */
  interface ElectionProperties {

    /**
     * Whether an adapter which has to guess is accepted next to other adapters. The
     * default is to reject it, which ends the startup with a message naming the fix.
     *
     * @return The setting, an empty Optional meaning "whatever applies globally"
     */
    Optional<io.vanillabp.integration.adapter.migration.config.ElectionProperties.GuessingAdapters> guessingAdapters();

  }

  /**
   * What VanillaBP does with the records of processed task deliveries: keep them until the
   * retention passed, or delete them the moment their workflow ends.
   */
  interface DeliveryProperties {

    /**
     * Whether the records of a workflow are deleted when it ends. The default is
     * <code>false</code>: switching it on makes every deployed model pay for the
     * notification about the end of a workflow.
     *
     * @return The setting, an empty Optional meaning "whatever applies globally"
     */
    Optional<Boolean> releaseOnWorkflowEnd();

    /**
     * How long a task may stay open before VanillaBP reports it. The default is thirty
     * days and reporting only, see the core's
     * {@link io.vanillabp.integration.adapter.migration.config.DeliveryProperties#getMaxTaskAge()}.
     *
     * @return The setting, an empty Optional meaning "whatever the next less specific
     *         level says"
     */
    Optional<Duration> maxTaskAge();

    /**
     * Whether a delivery looks at the other tasks VanillaBP believes are open in the same
     * workflow of the BPMS and reports the ones which are gone as canceled. The default is
     * <code>true</code>, see the core's
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
     * How long the record of a processed task delivery is kept. Read GLOBALLY only (see
     * the core's
     * {@link io.vanillabp.integration.adapter.migration.config.DeliveryProperties#getRetention()}),
     * and defaulting to <code>vanillabp.outbox.retention</code> where it is not set.
     *
     * @return The setting, an empty Optional meaning "whatever the outbox retention says"
     */
    Optional<Duration> retention();

  }

  /**
   * The properties of a workflow of a workflow module.
   */
  interface WorkflowProperties {

    /**
     * The priorities of adapters specific to a workflow.
     *
     * @return The order the adapters of this workflow are asked in, or an empty Optional
     *         to keep the order of the workflow module respectively of the application
     * @see QuarkusMigrationAdapterProperties#prioritizedAdapters()
     */
    Optional<List<String>> prioritizedAdapters();

    /**
     * The properties of adapters specific to this workflow.
     *
     * @return The section per adapter id, beating what the workflow module and the
     *         adapter itself say about the same key
     */
    Map<String, AdapterProperties> adapters();

    /**
     * The properties of the workflow's BPMN tasks. The key is the task ID (task
     * definition). This is the most specific of the four levels a setting is resolved
     * from, and the core asks it first
     * ({@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#resolveForAdapter},
     * see decision 7 in the repository's DECISIONS.md). The delivery settings and the
     * settings of an extension are resolved over the same four levels - see
     * QuarkusMigrationAdapterPropertiesMapperTest#maxTaskAgeTravelsThroughEveryLevel.
     *
     * @return The section per task, keyed by task ID
     */
    Map<String, TaskProperties> tasks();

    /**
     * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS. The one
     * level this permission may be written at - it is not inherited from anywhere.
     *
     * @return The permission, an empty Optional meaning "not allowed"
     */
    Optional<Boolean> allowFullSyncWithBpms();

    /**
     * Overrides <code>vanillabp.delivery</code> for this workflow.
     *
     * @return The delivery configuration of this workflow
     */
    DeliveryProperties delivery();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow,
     * keyed by extension id - what an extension is told about ONE BPMN process. A key the
     * workflow says nothing about keeps what the workflow module or the global section
     * says.
     *
     * @return The extension settings of this workflow
     */
    Map<String, Map<String, String>> extensions();

  }

  /**
   * The properties of a single BPMN task of a workflow - the MOST specific level of
   * the most-specific-wins resolution of adapter-scoped properties.
   */
  interface TaskProperties {

    /**
     * The properties of adapters specific to this task.
     *
     * @return The section per adapter id - the most specific place a setting of one
     *         adapter may be written at, so it beats every other level
     */
    Map<String, AdapterProperties> adapters();

    /**
     * Overrides <code>vanillabp.delivery</code> for this task - the most specific level
     * the maximum age of an open task may be set at.
     *
     * @return The delivery configuration of this task
     */
    DeliveryProperties delivery();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task, keyed
     * by extension id - the most specific level an extension setting may be written at. A
     * key the task says nothing about keeps what the three less specific levels say.
     *
     * @return The extension settings of this task
     */
    Map<String, Map<String, String>> extensions();

  }

}
