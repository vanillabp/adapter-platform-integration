package io.vanillabp.integration.runtime.processservice;

import java.lang.reflect.ParameterizedType;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.InjectionPoint;

/**
 * This producer is only taken into account if there is no synthetic bean matching the given injection point.
 * <p>
 * It helps to provide meaningful errors and also to suppress warnings in IDE because this producer is recognized
 * by IDE's code analyzers. The producer is not included into the final application.
 */
@ApplicationScoped
public class ProcessServiceIdeProducer {

  @Produces
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
