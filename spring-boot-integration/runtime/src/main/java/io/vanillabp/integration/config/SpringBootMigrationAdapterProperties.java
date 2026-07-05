package io.vanillabp.integration.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Properties common to all adapters.
 */
@ConfigurationProperties(prefix = SpringBootMigrationAdapterProperties.PREFIX)
@Getter
@Setter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class SpringBootMigrationAdapterProperties {

  public static final String PREFIX = "vanillabp";

  /**
   * Return the list of adapters, ordered by priority. New workflows will be started
   * using the first adapter. Other actions will target the BPMS the workflow is running in.
   * <p>
   * Each item has to be a key in the map of {@link SpringBootMigrationAdapterProperties#adapters}.
   */
  @Builder.Default
  private List<String> prioritizedAdapters = List.of();

  /**
   * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
   */
  private String resourcesLocation;

  /**
   * The resilience settings used when talking to BPMSs providing eventual consistency.
   * May be overridden per workflow module (and, once supported, per workflow) - the
   * most specific block configured wins as a whole.
   */
  private ResilienceProperties resilience;

  /**
   * The configuration of all adapters known. The key can be an adapter's identifier
   * or a custom identifier. In case of a custom identifier the {@link AdapterConfiguration#type}
   * property has to point to the adapters identifier the custom adapter is derived from.
   * In case of a non-custom adapter identifier the {@link AdapterConfiguration#type}
   * property has to be undefined.
   */
  @Builder.Default
  private Map<String, AdapterConfiguration> adapters = Map.of();

  /**
   * The properties of all workflow modules. The key is the workflow module's id.
   */
  @Builder.Default
  private Map<String, WorkflowModuleProperties> workflowModules = Map.of();

  /**
   * The adapter configuration. The properties in detail are defined by the
   * respective VanillaBP Spring Boot adapter.
   */
  @Getter
  @Setter
  @SuperBuilder(toBuilder = true)
  @NoArgsConstructor
  public static class AdapterConfiguration {

    /**
     * The adapter's type in case of a custom adapter identifier or null in case
     * of a non-custom adapter identifier.
     */
    private String type;

    /**
     * How to treat a failing deployment of BPMS resources for this adapter:
     * <code>fail</code> (default) aborts booting of the application;
     * <code>warn</code> logs the failure of a NON-first-priority adapter and the
     * application still starts (a failure of the first-priority adapter always
     * fails the boot).
     */
    private String deploymentFailure;

  }

  /**
   * The resilience settings used when talking to BPMSs providing eventual consistency.
   */
  @Getter
  @Setter
  @SuperBuilder(toBuilder = true)
  @NoArgsConstructor
  public static class ResilienceProperties {

    /**
     * The maximum number of retries after the initial attempt failed.
     */
    private Integer maxRetries;

    /**
     * The backoff interval before the first retry. Subsequent retries multiply the
     * previous interval by {@link #multiplier}.
     */
    private Duration initialInterval;

    /**
     * The multiplier applied to the backoff interval for each subsequent retry.
     */
    private Double multiplier;

    /**
     * The timeout per adapter call.
     */
    private Duration timeout;

  }

  /**
   * The adapter properties.
   */
  @Getter
  @Setter
  @SuperBuilder(toBuilder = true)
  @NoArgsConstructor
  public static class AdapterProperties {

    /**
     * Where to load BPMN files from, which are specific to the adapter
     */
    private String resourcesLocation;

  }

  /**
   * The adapters configuration properties.
   */
  @Getter
  @Setter
  @SuperBuilder(toBuilder = true)
  @NoArgsConstructor
  public static class AdaptersConfigurationProperties {

    /**
     * Return the list of adapters, ordered by priority. New workflows will be started
     * using the first adapter. Other actions will target the BPMS the workflow is running in.
     * <p>
     * Each item has to be a key in the map of {@link SpringBootMigrationAdapterProperties#adapters}.
     */
    @Builder.Default
    private List<String> prioritizedAdapters = List.of();

    /**
     * The resilience settings overriding less specific configuration levels as a
     * whole block.
     */
    private ResilienceProperties resilience;

  }

  /**
   * The properties of a workflow module.
   */
  @Getter
  @Setter
  @SuperBuilder(toBuilder = true)
  @NoArgsConstructor
  public static class WorkflowModuleProperties extends AdaptersConfigurationProperties {

    /**
     * The properties of adapters specific to this workflow module.
     */
    @Builder.Default
    private Map<String, AdapterProperties> adapters = Map.of();

    /**
     * The properties of workflows specific to this workflow module. The key is the BPMN process ID.
     * <p>
     * <b>Attention:</b> Workflow-level configuration is not yet supported! It is only bound to
     * detect and reject such configuration on startup instead of silently ignoring it.
     */
    @Builder.Default
    private Map<String, WorkflowProperties> workflows = Map.of();

  }

  /**
   * The properties of a workflow of a workflow module.
   */
  @Getter
  @Setter
  @SuperBuilder(toBuilder = true)
  @NoArgsConstructor
  public static class WorkflowProperties extends AdaptersConfigurationProperties {

    /**
     * The properties of adapters specific to this workflow.
     */
    @Builder.Default
    private Map<String, AdapterProperties> adapters = Map.of();

  }

}
