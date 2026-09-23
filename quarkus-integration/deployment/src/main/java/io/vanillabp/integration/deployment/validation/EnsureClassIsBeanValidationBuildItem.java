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

  /**
   * Whether a bean of a SUBCLASS satisfies the requirement. It does wherever VanillaBP
   * only needs an instance it can call the class' methods on - the aggregate persistence
   * of a workflow aggregate is that shape, and an application is free to provide it as a
   * subclass or through a producer.
   * <p>
   * A workflow service is the other shape: VanillaBP registers the class ITSELF as the one
   * serving a BPMN process, so a class which is no bean of its own serves nothing, however
   * many of its subclasses are beans.
   */
  @Builder.Default
  private final boolean aBeanOfASubclassCounts = true;

  /**
   * What to do about it, appended to the build error. Says nothing where the
   * bean-defining annotation the default message names is the whole answer.
   */
  private final String remedy;

  /**
   * Built through the builder by every step which finds a class VanillaBP will look up by
   * its name at runtime - the workflow services and their aggregate persistence
   * ({@code ProcessServiceBuildStepProcessor}), and the resolvers of multi-instance elements
   * ({@code MultiInstanceResolverBuildStepProcessor}). Both steps of
   * {@link EnsureCollectedClassesAreBeansBuildStepProcessor} read the collected items.
   *
   * @param className The class which has to be a bean
   * @param usageDescription How VanillaBP uses the class, shown in the build error
   * @param aBeanOfASubclassCounts Whether a bean of a subclass satisfies the requirement, as
   *          described at the field
   * @param remedy What to do about it, or <code>null</code> for the default advice
   */
  EnsureClassIsBeanValidationBuildItem(
      final DotName className,
      final String usageDescription,
      final boolean aBeanOfASubclassCounts,
      final String remedy) {

    this.className = className;
    this.usageDescription = usageDescription;
    this.aBeanOfASubclassCounts = aBeanOfASubclassCounts;
    this.remedy = remedy;

  }

}
