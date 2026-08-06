package io.vanillabp.integration.runtime.config;

import java.util.List;
import java.util.Optional;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
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
 * Explicit ignores: the core's back-references ({@code workflowModuleId},
 * {@code bpmnProcessId}, {@code workflowModule}) which are linked by
 * {@code MigrationAdapterProperties#validateAndLink()}.
 * <p>
 * The outbox defaults are declared TWICE by necessity (SmallRye requires
 * {@code @WithDefault} on the interface, the core carries them as field
 * initializers) - their equality is pinned by
 * {@code QuarkusMigrationAdapterPropertiesMapperTest}.
 */
@Mapper(
    unmappedSourcePolicy = ReportingPolicy.ERROR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface QuarkusMigrationAdapterPropertiesMapper {

  QuarkusMigrationAdapterPropertiesMapper INSTANCE = Mappers.getMapper(QuarkusMigrationAdapterPropertiesMapper.class);

  @Mapping(target = "prioritizedAdapters", qualifiedByName = "unwrapStringList")
  // derived from the classpath facts by MigrationAdapterProperties#normalize, not
  // bound from properties (story 34's resources-location convention)
  @Mapping(target = "conventionalResourcesLocations", ignore = true)
  MigrationAdapterProperties toCore(
      QuarkusMigrationAdapterProperties properties);

  PhaseTwoOutboxProperties toCore(
      QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outboxProperties);

  @Mapping(target = "table", qualifiedByName = "unwrapString")
  PhaseTwoOutboxProperties.JdbcOutboxProperties toCore(
      QuarkusMigrationAdapterProperties.JdbcOutboxProperties jdbcOutboxProperties);

  PhaseTwoOutboxProperties.MongoOutboxProperties toCore(
      QuarkusMigrationAdapterProperties.MongoOutboxProperties mongoOutboxProperties);

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

  TaskAdapterProperties toCore(
      QuarkusMigrationAdapterProperties.TaskProperties taskProperties);

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
  /**
   * Unwraps an optional string ({@code Optional.empty()} becomes {@code null},
   * matching the core model's "platform default" semantic).
   *
   * @param value The optional string
   * @return The unwrapped string or {@code null}
   */
  @Named("unwrapString")
  default String unwrapString(
      final Optional<String> value) {

    return value.orElse(null);

  }

  @Named("unwrapStringList")
  default List<String> unwrapList(
      final Optional<List<String>> value) {

    return value
        .map(List::copyOf)
        .orElse(List.of());

  }

}
