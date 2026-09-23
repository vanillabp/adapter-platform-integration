package io.vanillabp.integration.runtime.processservice;

import java.lang.reflect.ParameterizedType;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * This producer exists only to suppress IDE warnings like &quot;Unsatisfied dependency:
 * no bean matches the injection point&quot; for injection points of type
 * {@link ProcessService}: the actual beans are generated at build time by the VanillaBP
 * Quarkus extension and are therefore unknown to the IDE's code analyzers, whereas this
 * producer is recognized by them.
 * <p>
 * The producer is part of the JAR but declared as an unselected {@link Alternative}:
 * unselected alternatives are ignored for bean resolution, so this producer is never
 * used at runtime - not even if this module is turned into a bean archive (e.g. by
 * configuring <code>quarkus.index-dependency</code> for it).
 */
@ApplicationScoped
@Alternative
public class ProcessServiceIdeProducer {

  /**
   * The CDI container would build this bean like any other, and never does: an unselected
   * {@link Alternative} takes part in no resolution. The class is shipped by
   * <code>vanillabp-quarkus-support</code>, the one dependency a workflow module needs,
   * because that artifact is what an IDE has in front of it while it analyzes the injection
   * points of the application.
   */
  public ProcessServiceIdeProducer() {
  }

  /**
   * Throws, always, and the message is the reason this method exists: it names what is
   * missing for the {@link ProcessService} somebody asked for - the generic parameter, or a
   * class annotated by <code>&#64;WorkflowService</code> declaring that workflow aggregate.
   * A running application never gets here, because the VanillaBP Quarkus extension
   * generated a bean per workflow aggregate and this alternative stays unselected.
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
