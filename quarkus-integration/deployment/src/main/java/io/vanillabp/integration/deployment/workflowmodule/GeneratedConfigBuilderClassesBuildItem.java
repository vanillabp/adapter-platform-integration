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

  /**
   * Built by {@code WorkflowModuleBuildStepProcessor} through the builder, after it wrote
   * the classes. A step of its own reads the item and registers the classes with Quarkus,
   * because a generated class has to exist before anything may name it.
   *
   * @param configBuilderClassnames The names of the classes generated: one for the
   *          properties files of the workflow modules, and one more where the application
   *          reads YAML as well
   */
  GeneratedConfigBuilderClassesBuildItem(
      final List<String> configBuilderClassnames) {

    this.configBuilderClassnames = configBuilderClassnames;

  }

}
