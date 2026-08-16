package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.vanillabp.spi.service.MultiInstanceElementResolver;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A resolver named by <code>&#64;MultiInstanceElement(resolverBean = ...)</code> and
 * injected NOWHERE - which is the point of the test (story 71): Quarkus used to remove
 * it while building the application, and the lookup at task time found nothing.
 */
@ApplicationScoped
public class IterationResolver implements MultiInstanceElementResolver<ResolverAggregate, String> {

  @Override
  public Collection<String> getNames() {

    return List.of("items");

  }

  @Override
  public String resolve(
      final ResolverAggregate workflowAggregate,
      final Map<String, MultiInstance<Object>> multiInstances) {

    final var iteration = multiInstances.get("items");
    return "%s@%d/%d".formatted(iteration.getElement(), iteration.getIndex(), iteration.getTotal());

  }

}
