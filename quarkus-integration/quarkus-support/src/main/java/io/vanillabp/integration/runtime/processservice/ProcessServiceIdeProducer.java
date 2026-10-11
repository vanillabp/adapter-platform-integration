package io.vanillabp.integration.runtime.processservice;

import java.lang.reflect.ParameterizedType;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * This producer only exists to silence IDE warnings for injection points of type
 * {@link ProcessService}, like &quot;Unsatisfied dependency: no bean matches the injection
 * point&quot;. The VanillaBP Quarkus extension generates the real beans at build time. So the
 * code analyzers of an IDE do not know them, but they do know this producer.
 * <p>
 * The producer is part of the JAR, but it is an unselected {@link Alternative}. Bean resolution
 * ignores unselected alternatives. So this producer is never used at runtime. This also holds
 * if this module becomes a bean archive (e.g. by configuring
 * <code>quarkus.index-dependency</code> for it).
 */
@ApplicationScoped
@Alternative
public class ProcessServiceIdeProducer {

  /**
   * The CDI container could build this bean like any other, but it never does. An unselected
   * {@link Alternative} takes part in no resolution. The class comes with
   * <code>vanillabp-quarkus-support</code>, the one dependency a workflow module needs. The IDE
   * has this artifact in front of it when it analyzes the injection points of the application.
   */
  public ProcessServiceIdeProducer() {
  }

  /**
   * Always throws. The message is the reason why this method exists. It names what is missing
   * for the requested {@link ProcessService}: the generic parameter, or a class annotated with
   * <code>&#64;WorkflowService</code> which declares that workflow aggregate. A running
   * application never gets here. The VanillaBP Quarkus extension generated a bean for each
   * workflow aggregate, and this alternative stays unselected.
   *
   * @param <A> The workflow aggregate class of the injection point
   * @param ip The injection point asking for a {@link ProcessService}
   * @return Nothing
   */
  @Produces
  @Alternative
  public <A> ProcessService<A> ideProcessService(
      final InjectionPoint ip) {

    final var rawType = ip.getType();
    if (!(rawType instanceof ParameterizedType type)) {
      throw new UnsatisfiedResolutionException(
          ProcessService.class.getName()
              + " needs to be used with a generic parameter pointing to a workflow aggregate class");
    }

    throw new UnsatisfiedResolutionException(
        "There is no class found annotated with @WorkflowService having annotation parameter workflowAggregateClass='"
            + type.getActualTypeArguments()[0]
            + "'");

  }

}
