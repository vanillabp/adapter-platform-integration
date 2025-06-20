package io.vanillabp.integration.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.modules.WorkflowModuleProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Properties common to all adapters.
 */
@ConfigurationProperties(prefix = SpringBootMigrationAdapterProperties.PREFIX)
@Getter
@Setter
public class SpringBootMigrationAdapterProperties {

  public static final String PREFIX = "vanillabp";

  /**
   * Return the list of adapters, ordered by priority. New workflows will be started
   * using the first adapter. Other actions will target the BPMS the workflow is running in.
   * <p>
   * Each item has to be a key in the map of {@link SpringBootMigrationAdapterProperties#adapters}.
   */
  private List<String> prioritizedAdapters = List.of();

  /**
   * The configuration of all adapters known. The key can be an adapter's identifier
   * or a custom identifier. In case of a custom identifier the {@link AdapterConfiguration#type}
   * property has to point to the adapters identifier the custom adapter is derived from.
   * In case of a non-custom adapter identifier the {@link AdapterConfiguration#type}
   * property has to be undefined.
   */
  private Map<String, AdapterConfiguration> adapters = Map.of();

  /**
   * The properties of all workflow modules. The key is the workflow module's id.
   */
  private Map<String, WorkflowModuleProperties> workflowModules = Map.of();

  /**
   * The adapter configuration. The properties in detail are defined by the adapter's
   * Spring-Boot extension.
   */
  @Getter
  @Setter
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
  public static class AdapterProperties {
    /**
     * Where to load BPMN files from, which are specific to the adapter
     */
    private String resourcesLocation;
  }

  @Getter
  @Setter
  public static class WorkflowModuleProperties {
    /**
     * Adapter properties.
     */
    private Map<String, AdapterProperties> adapters = Map.of();
  }

}
