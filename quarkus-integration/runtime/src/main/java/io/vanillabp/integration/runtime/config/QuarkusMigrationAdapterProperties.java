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
   * The resilience settings used when talking to BPMSs providing eventual consistency.
   * May be overridden per workflow module (and, once supported, per workflow) - the
   * most specific block configured wins as a whole.
   *
   * @return The resilience settings
   */
  Optional<ResilienceProperties> resilience();

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
   * (see {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox}).
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
     * fails the boot).
     *
     * @return The deployment-failure policy
     */
    Optional<String> deploymentFailure();

  }

  /**
   * The resilience settings used when talking to BPMSs providing eventual consistency.
   */
  interface ResilienceProperties {

    /**
     * The maximum number of retries after the initial attempt failed.
     *
     * @return The maximum number of retries
     */
    Optional<Integer> maxRetries();

    /**
     * The backoff interval before the first retry. Subsequent retries multiply the
     * previous interval by {@link #multiplier()}.
     *
     * @return The initial backoff interval
     */
    Optional<Duration> initialInterval();

    /**
     * The multiplier applied to the backoff interval for each subsequent retry.
     *
     * @return The backoff multiplier
     */
    Optional<Double> multiplier();

    /**
     * The timeout per adapter call.
     *
     * @return The timeout
     */
    Optional<Duration> timeout();

  }

  /**
   * The adapter properties.
   */
  interface AdapterProperties {

    /**
     * Where to load BPMN files from, which are specific to the adapter
     */
    String resourcesLocation();

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
     * The resilience settings overriding less specific configuration levels as a
     * whole block.
     *
     * @return The resilience settings
     */
    Optional<ResilienceProperties> resilience();

    /**
     * The properties of adapters specific to this workflow module.
     */
    Map<String, AdapterProperties> adapters();

  }

}
