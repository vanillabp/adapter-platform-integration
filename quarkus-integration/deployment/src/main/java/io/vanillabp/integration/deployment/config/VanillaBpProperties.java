package io.vanillabp.integration.deployment.config;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Properties common to all adapters.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpProperties {

  /**
   * Return the list of adapters, ordered by priority. New workflows will be started
   * using the first adapter. Other action will target the BPMS the workflow is running in.
   *
   * @return Ordered list of adapters to be used
   */
  Optional<List<String>> defaultAdapter();

}
