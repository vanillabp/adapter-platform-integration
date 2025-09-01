package io.vanillabp.integration.deployment.workflowmodule;

import java.util.List;

import io.quarkus.builder.item.SimpleBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * A build item holding all {@link io.quarkus.runtime.configuration.ConfigBuilder}
 * names of classes responsible for loading workflow module specific config files.
 */
@Builder
@Getter
public final class GeneratedConfigBuilderClassesBuildItem extends SimpleBuildItem {

  /**
   * The class names.
   */
  private final List<String> configBuilderClassnames;

}
