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
 * used at runtime — not even if this module is turned into a bean archive (e.g. by
 * configuring <code>quarkus.index-dependency</code> for it).
 */
@ApplicationScoped
@Alternative
public class ProcessServiceIdeProducer {

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
