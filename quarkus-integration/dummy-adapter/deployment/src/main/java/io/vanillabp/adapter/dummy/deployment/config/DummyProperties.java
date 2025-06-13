package io.vanillabp.adapter.dummy.deployment.config;


import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Dummy adapter properties
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface DummyProperties {

}
