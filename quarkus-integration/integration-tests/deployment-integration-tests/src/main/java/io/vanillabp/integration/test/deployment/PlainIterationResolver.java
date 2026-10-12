package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import io.vanillabp.spi.service.MultiInstanceElementResolver;

/**
 * A resolver which is NO bean, because it has no scope annotation. A test uses it to
 * prove that the build reports this, and not the first iteration of the task.
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
