package io.vanillabp.integration.deployment.validation;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * A build item collecting a class which is required to be a CDI bean at runtime. All
 * collected classes are checked during ArC's validation phase by
 * {@link EnsureCollectedClassesAreBeansBuildStepProcessor} and a build error is raised
 * for classes not backed by any bean.
 */
@Builder
@Getter
public final class EnsureClassIsBeanValidationBuildItem extends MultiBuildItem {

  /**
   * The class required to be a CDI bean at runtime.
   */
  private final DotName className;

  /**
   * A description of how the class is used by the VanillaBP extension, shown as part of
   * the build error if the class is not backed by any bean.
   */
  private final String usageDescription;

}
