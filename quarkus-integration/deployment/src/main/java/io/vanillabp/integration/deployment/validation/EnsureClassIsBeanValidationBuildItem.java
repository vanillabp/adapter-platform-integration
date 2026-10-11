package io.vanillabp.integration.deployment.validation;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * A build item which collects a class that has to be a CDI bean at runtime.
 * {@link EnsureCollectedClassesAreBeansBuildStepProcessor} checks all collected classes
 * during the validation phase of ArC, the CDI container of Quarkus. A class which no bean
 * backs fails the build.
 */
@Builder
@Getter
public final class EnsureClassIsBeanValidationBuildItem extends MultiBuildItem {

  /**
   * The class required to be a CDI bean at runtime.
   */
  private final DotName className;

  /**
   * Describes how the VanillaBP extension uses the class. The build error shows it if no
   * bean backs the class.
   */
  private final String usageDescription;

  /**
   * Whether a bean of a SUBCLASS satisfies the requirement. It does wherever VanillaBP only
   * needs an instance to call the class' methods on. The aggregate persistence of a workflow
   * aggregate is such a case. An application may provide it as a subclass or through a
   * producer.
   * <p>
   * A workflow service is the other case. VanillaBP registers the class ITSELF as the one
   * which serves a BPMN process. So a class which is no bean of its own serves nothing, even
   * if many of its subclasses are beans.
   */
  @Builder.Default
  private final boolean aBeanOfASubclassCounts = true;

  /**
   * What to do about it. It is appended to the build error. It is <code>null</code> where the
   * default message already says it all: add a bean-defining annotation.
   */
  private final String remedy;

  /**
   * Built through the builder by every step which finds a class that VanillaBP looks up by
   * its name at runtime. These are the workflow services and their aggregate persistence
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
