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
   * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
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
   */
  Map<String, WorkflowModuleProperties> workflowModules();

  /**
   * The configuration of the phase-two outbox used for two-phase workflow starts
   * (see {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
   *
   * @return The outbox configuration
   */
  PhaseTwoOutboxProperties outbox();

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
     * Where to load BPMN files from, which are specific to the adapter. This
     * section is the least specific level of the most-specific-wins resolution of
     * adapter-scoped properties, so it carries the same per-level keys as the
     * workflow-module, workflow and task levels.
     */
    Optional<String> resourcesLocation();

  }

  /**
   * The adapter properties of a level of the most-specific-wins resolution of
   * adapter-scoped properties (workflow module, workflow or task).
   */
  interface AdapterProperties {

    /**
     * Where to load BPMN files from, which are specific to the adapter. Optional:
     * an adapter section of a level may carry only adapter-specific keys (the
     * missing resources location is validated with a guiding message by the core
     * where it is actually required).
     */
    Optional<String> resourcesLocation();

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
     * How long to wait after a failed dispatch until the entry is retried.
     *
     * @return The retry backoff
     */
    @WithDefault("PT30S")
    Duration attemptFrequency();

    /**
     * After how many failed attempts an entry is blocked (not retried any longer).
     * Blocked entries have to be fixed manually (e.g. by cleaning up the outbox
     * table).
     *
     * @return The maximum number of attempts
     */
    @WithDefault("10")
    int blockAfterAttempts();

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

  }

  /**
   * The properties of a workflow module.
   */
  interface WorkflowModuleProperties {

    /**
     * The priorities of adapters specific to a workflow module.
     *
     * @see QuarkusMigrationAdapterProperties#prioritizedAdapters()
     */
    Optional<List<String>> prioritizedAdapters();

    /**
     * The properties of adapters specific to this workflow module.
     */
    Map<String, AdapterProperties> adapters();

    /**
     * The properties of workflows specific to this workflow module. The key is the
     * BPMN process ID.
     * <p>
     * <b>Attention:</b> Workflow-level configuration is not yet supported! It is only
     * bound so the core validation can detect and reject such configuration on
     * startup instead of silently ignoring it.
     */
    Map<String, WorkflowProperties> workflows();

  }

  /**
   * The properties of a workflow of a workflow module.
   */
  interface WorkflowProperties {

    /**
     * The priorities of adapters specific to a workflow.
     *
     * @see QuarkusMigrationAdapterProperties#prioritizedAdapters()
     */
    Optional<List<String>> prioritizedAdapters();

    /**
     * The properties of adapters specific to this workflow.
     */
    Map<String, AdapterProperties> adapters();

    /**
     * The properties of the workflow's BPMN tasks. The key is the task ID (task
     * definition). Structural preparation for task-scoped adapter configuration -
     * no consumer yet.
     */
    Map<String, TaskProperties> tasks();

  }

  /**
   * The properties of a single BPMN task of a workflow - the MOST specific level of
   * the most-specific-wins resolution of adapter-scoped properties.
   */
  interface TaskProperties {

    /**
     * The properties of adapters specific to this task.
     */
    Map<String, AdapterProperties> adapters();

  }

}
