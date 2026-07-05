package io.vanillabp.integration.runtime.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

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

  }

}
