package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.vanillabp.spi.service.MultiInstanceElementResolver;

/**
 * A resolver which is NO bean (no scope annotation) - used by the test proving that
 * this is said while building instead of at the first iteration of the task.
 */
public class PlainIterationResolver implements MultiInstanceElementResolver<ResolverAggregate, String> {

  @Override
  public Collection<String> getNames() {

    return List.of("items");

  }

  @Override
  public String resolve(
      final ResolverAggregate workflowAggregate,
      final Map<String, MultiInstance<Object>> multiInstances) {

    return "never called";

  }

}
