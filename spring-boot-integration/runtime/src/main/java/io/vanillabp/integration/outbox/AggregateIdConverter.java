package io.vanillabp.integration.outbox;

import org.springframework.core.convert.support.DefaultConversionService;

import lombok.extern.slf4j.Slf4j;

/**
 * Converts workflow-aggregate IDs which were serialized as strings by a
 * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} implementation back to
 * their original type (e.g. <code>Long</code> or <code>UUID</code>).
 */
@Slf4j
public class AggregateIdConverter {

  private AggregateIdConverter() {
    // utility class
  }

  /**
   * Converts the given serialized aggregate ID to the given target type using
   * Spring's shared {@link DefaultConversionService}. If the ID already is of the
   * target type or cannot be converted, it is passed through unchanged.
   *
   * @param aggregateId The aggregate ID (possibly serialized as a string)
   * @param aggregateIdType The type the aggregate ID should be converted to
   * @return The converted aggregate ID
   */
  public static Object convert(
      final Object aggregateId,
      final Class<?> aggregateIdType) {

    if (aggregateId == null || aggregateIdType == null || aggregateIdType.isInstance(aggregateId)) {
      return aggregateId;
    }
    final var conversionService = DefaultConversionService.getSharedInstance();
    if (!conversionService.canConvert(aggregateId.getClass(), aggregateIdType)) {
      log.warn(
          "Cannot convert workflow-aggregate ID '{}' of type '{}' to '{}' - passing it through unchanged!",
          aggregateId,
          aggregateId.getClass().getName(),
          aggregateIdType.getName());
      return aggregateId;
    }
    return conversionService.convert(aggregateId, aggregateIdType);

  }

}
