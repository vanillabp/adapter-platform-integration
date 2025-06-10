package io.vanillabp.integration.deployment.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Properties common to all adapters.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface MigrationAdapterProperties {

  /**
   * Return the list of adapters, ordered by priority. New workflows will be started
   * using the first adapter. Other action will target the BPMS the workflow is running in.
   * <p>
   * Each item has to be a key in the map of {@link MigrationAdapterProperties#adapters}.
   *
   * @return Ordered list of adapters to be used
   */
  Optional<List<String>> defaultAdapter();

  /**
   * The configuration of all adapters known. The key can be an adapter's identifier
   * or a custom identifier. In case of a custom identifier the {@link AdapterConfiguration#type()}
   * property has to point to the adapters identifier the custom adapter is derived from.
   * In case of a non-custom adapter identifier the {@link AdapterConfiguration#type()}
   * property has to be undefined.
   *
   * @return All adapters configured.
   */
  Map<String, AdapterConfiguration> adapters();

  /**
   * The adapter configuration. The properties in detail are defined by the adapter's
   * Quarkus extension.
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

}
