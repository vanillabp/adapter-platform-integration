package io.vanillabp.integration.runtime.config;

import java.util.List;
import java.util.Optional;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;

/**
 * Purely mechanical, GENERATED copy of the Quarkus {@code @ConfigMapping} interface
 * onto the platform-neutral core model - zero validation, zero defaulting (both live
 * in the core: {@code validateProperties()}/{@code normalize()}).
 * <p>
 * The mapping is pinned at COMPILE TIME: {@code unmappedSourcePolicy} and
 * {@code unmappedTargetPolicy} are {@code ERROR}, so adding a property to only one
 * side (interface or core model) fails the build until the mapping (or an explicit
 * ignore) is updated. The fluent accessors of the SmallRye interface are made
 * visible to MapStruct by the {@code vanillabp-mapstruct-fluent-accessors} SPI on
 * the annotation-processor path.
 * <p>
 * Explicit ignores: {@code outbox} (platform-owned, not part of the core model -
 * consolidated by its own story) and the core's back-references
 * ({@code workflowModuleId}, {@code bpmnProcessId}, {@code workflowModule}) which
 * are linked by {@code MigrationAdapterProperties#validateAndLink()}.
 */
@Mapper(
    unmappedSourcePolicy = ReportingPolicy.ERROR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface QuarkusMigrationAdapterPropertiesMapper {

  QuarkusMigrationAdapterPropertiesMapper INSTANCE = Mappers.getMapper(QuarkusMigrationAdapterPropertiesMapper.class);

  @BeanMapping(ignoreUnmappedSourceProperties = {
      "outbox"
  })
  @Mapping(target = "prioritizedAdapters", qualifiedByName = "unwrapStringList")
  MigrationAdapterProperties toCore(
      QuarkusMigrationAdapterProperties properties);

  AdapterConfigProperties toCore(
      QuarkusMigrationAdapterProperties.AdapterConfiguration adapterConfiguration);

  AdapterProperties toCore(
      QuarkusMigrationAdapterProperties.AdapterProperties adapterProperties);

  @Mapping(target = "workflowModuleId", ignore = true)
  @Mapping(target = "prioritizedAdapters", qualifiedByName = "unwrapStringList")
  WorkflowModuleAdapterProperties toCore(
      QuarkusMigrationAdapterProperties.WorkflowModuleProperties workflowModuleProperties);

  @Mapping(target = "bpmnProcessId", ignore = true)
  @Mapping(target = "workflowModule", ignore = true)
  @Mapping(target = "prioritizedAdapters", qualifiedByName = "unwrapStringList")
  WorkflowAdapterProperties toCore(
      QuarkusMigrationAdapterProperties.WorkflowProperties workflowProperties);

  /**
   * Unwraps optional scalar values ({@code Optional.empty()} becomes {@code null},
   * matching an unset property of the core model).
   *
   * @param <T> The value type
   * @param value The optional value
   * @return The unwrapped value or {@code null}
   */
  default <T> T unwrap(
      final Optional<T> value) {

    return value.orElse(null);

  }

  /**
   * Unwraps optional lists ({@code Optional.empty()} becomes an empty list,
   * matching the core model's default).
   *
   * @param value The optional list
   * @return The unwrapped list or an empty list
   */
  @Named("unwrapStringList")
  default List<String> unwrapList(
      final Optional<List<String>> value) {

    return value
        .map(List::copyOf)
        .orElse(List.of());

  }

}
