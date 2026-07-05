package io.vanillabp.integration.config;

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
