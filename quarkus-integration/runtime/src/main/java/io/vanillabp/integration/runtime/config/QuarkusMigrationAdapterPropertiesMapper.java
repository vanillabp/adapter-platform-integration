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
import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.ElectionProperties;
import io.vanillabp.integration.adapter.migration.config.MetricsProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TransactionsProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
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

  /**
   * The mapper in use. MapStruct writes the implementation at compile time and this
   * field looks it up once, so nobody has to inject anything to reach it: the recorder
   * which builds the runtime configuration uses the same instance as a store which
   * reads its own section lazily.
   */
  QuarkusMigrationAdapterPropertiesMapper INSTANCE = Mappers.getMapper(QuarkusMigrationAdapterPropertiesMapper.class);

  /**
   * Copies everything an application wrote below <code>vanillabp.</code> onto the core
   * model. This is where the two halves meet: Quarkus binds configuration through a
   * SmallRye <code>&#64;ConfigMapping</code> interface, while validating, defaulting and
   * resolving a setting happens in the core and is shared with Spring Boot.
   * <p>
   * What comes back is not usable yet - it is neither validated nor defaulted, and its
   * back-references are still unset. {@link QuarkusMigrationAdapterTransformer} copies
   * first and lets the core validate afterwards, and the result of that becomes the
   * {@link MigrationAdapterProperties} bean of the application.
   *
   * @param properties What SmallRye bound from the keys below <code>vanillabp.</code>
   * @return The same settings as the platform-neutral model of the core
   */
  @Mapping(target = "prioritizedAdapters", qualifiedByName = "unwrapStringList")
  @Mapping(target = "retiredAdapters", qualifiedByName = "unwrapStringList")
  @Mapping(target = "allowFullSyncWithBpms", qualifiedByName = "unwrapBoolean")
  // derived from the classpath facts by MigrationAdapterProperties#normalize, not
  // bound from properties (the resources-location convention)
  @Mapping(target = "conventionalResourcesLocations", ignore = true)
  MigrationAdapterProperties toCore(
      QuarkusMigrationAdapterProperties properties);

  /**
   * Copies the outbox section (<code>vanillabp.outbox.*</code>) alone. MapStruct needs
   * it while it copies the whole tree, and the stores call it directly: the JDBC outbox,
   * the dispatcher of the MongoDB outbox and both delivery logs have to know their table
   * respectively their collection before the startup event is observed, and their own
   * section is all they need for that.
   *
   * @param outboxProperties What SmallRye bound below <code>vanillabp.outbox</code>
   * @return The outbox settings of the core: the intervals of the dispatcher, the
   *         retention, and the names of the table respectively the collections
   */
  PhaseTwoOutboxProperties toCore(
      QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outboxProperties);

  /**
   * Copies the section of the JDBC outbox (<code>vanillabp.outbox.jdbc.*</code>).
   * Unlike the MongoDB section next to it, the table names carry no
   * <code>&#64;WithDefault</code>: an unset name arrives as <code>null</code>, which is how
   * the core reads "use the default name", so the name is decided in one place for both
   * platforms.
   *
   * @param jdbcOutboxProperties What SmallRye bound below
   *          <code>vanillabp.outbox.jdbc</code>
   * @return The settings of the outbox which stores into a relational database
   */
  @Mapping(target = "table", qualifiedByName = "unwrapString")
  PhaseTwoOutboxProperties.JdbcOutboxProperties toCore(
      QuarkusMigrationAdapterProperties.JdbcOutboxProperties jdbcOutboxProperties);

  /**
   * Copies the section of the MongoDB outbox (<code>vanillabp.outbox.mongo.*</code>),
   * which names the collection of the outbox entries, the collection of their payloads
   * and the collection of the delivery records.
   *
   * @param mongoOutboxProperties What SmallRye bound below
   *          <code>vanillabp.outbox.mongo</code>
   * @return The settings of the outbox which stores into MongoDB
   */
  PhaseTwoOutboxProperties.MongoOutboxProperties toCore(
      QuarkusMigrationAdapterProperties.MongoOutboxProperties mongoOutboxProperties);

  /**
   * Copies the section of the election cache
   * (<code>vanillabp.workflow-adapter-cache.*</code>). Called directly by the producer
   * of the in-memory default cache, which is built before anybody asks the core model
   * for anything.
   *
   * @param workflowAdapterCacheProperties What SmallRye bound below
   *          <code>vanillabp.workflow-adapter-cache</code>
   * @return The bounds the default cache is built with
   */
  WorkflowAdapterCacheProperties toCore(
      QuarkusMigrationAdapterProperties.WorkflowAdapterCacheProperties workflowAdapterCacheProperties);

  /**
   * Copies the transaction section (<code>vanillabp.transactions.*</code>), which a
   * workflow module may write again for itself.
   *
   * @param transactionsProperties What SmallRye bound below
   *          <code>vanillabp.transactions</code> or below the same key of a workflow
   *          module
   * @return What VanillaBP does about an aggregate written outside its transaction
   */
  @Mapping(target = "unguardedAggregateWrites", qualifiedByName = "unwrapUnguardedAggregateWrites")
  TransactionsProperties toCore(
      QuarkusMigrationAdapterProperties.TransactionsProperties transactionsProperties);

  /**
   * Copies the election section (<code>vanillabp.election.*</code>), which a workflow
   * module may write again for itself.
   *
   * @param electionProperties What SmallRye bound below <code>vanillabp.election</code>
   *          or below the same key of a workflow module
   * @return What VanillaBP does about an adapter which cannot say whether its BPMS
   *         holds a workflow
   */
  @Mapping(target = "guessingAdapters", qualifiedByName = "unwrapGuessingAdapters")
  ElectionProperties toCore(
      QuarkusMigrationAdapterProperties.ElectionProperties electionProperties);

  /**
   * Copies a delivery section (<code>vanillabp.delivery.*</code>), which may be written
   * at all four levels down to the single task. Both default delivery logs call it
   * directly for the global section, because the retention of a record is read there
   * and nowhere else.
   *
   * @param deliveryProperties What SmallRye bound below <code>vanillabp.delivery</code>
   *          of one level
   * @return What that level says about the records of processed task deliveries
   */
  @Mapping(target = "releaseOnWorkflowEnd", qualifiedByName = "unwrapBoolean")
  @Mapping(target = "maxTaskAge", qualifiedByName = "unwrapDuration")
  @Mapping(target = "retention", qualifiedByName = "unwrapDuration")
  DeliveryProperties toCore(
      QuarkusMigrationAdapterProperties.DeliveryProperties deliveryProperties);

  /**
   * Copies the metrics section (<code>vanillabp.metrics.*</code>), which holds the one
   * number keeping a gauge cheap to read (see decision 18 in the repository's
   * DECISIONS.md).
   *
   * @param metricsProperties What SmallRye bound below <code>vanillabp.metrics</code>
   * @return How long one measurement is reused
   */
  MetricsProperties toCore(
      QuarkusMigrationAdapterProperties.MetricsProperties metricsProperties);

  /**
   * Copies the section of one configured adapter
   * (<code>vanillabp.adapters.&lt;id&gt;.*</code>). The id is the identity of that
   * adapter instance and the type names the BPMS behind it, so two adapters of the same
   * type may stand next to each other, which is what a migration from one BPMS to
   * another runs on (see decision 17 in the repository's DECISIONS.md). This section is
   * also the least specific level a setting of that adapter may be written at.
   *
   * @param adapterConfiguration What SmallRye bound below
   *          <code>vanillabp.adapters.&lt;id&gt;</code>
   * @return The core's section of that adapter instance
   */
  @Mapping(target = "outfadedVersions", qualifiedByName = "unwrapOutfadedVersions")
  @Mapping(target = "allowFullSyncWithBpms", qualifiedByName = "unwrapBoolean")
  AdapterConfigProperties toCore(
      QuarkusMigrationAdapterProperties.AdapterConfiguration adapterConfiguration);

  /**
   * Copies what a workflow module, a workflow or a task says about ONE adapter, for
   * example
   * <code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.*</code>. Such
   * a section carries the same keys as the adapter's own section, and the most specific
   * one which sets a key wins (see decision 7 in the repository's DECISIONS.md).
   *
   * @param adapterProperties What SmallRye bound below the
   *          <code>adapters.&lt;id&gt;</code> of one level
   * @return What that level says about that adapter
   */
  @Mapping(target = "outfadedVersions", qualifiedByName = "unwrapOutfadedVersions")
  @Mapping(target = "allowFullSyncWithBpms", qualifiedByName = "unwrapBoolean")
  AdapterProperties toCore(
      QuarkusMigrationAdapterProperties.AdapterProperties adapterProperties);

  /**
   * Copies the section of one workflow module
   * (<code>vanillabp.workflow-modules.&lt;id&gt;.*</code>). Its id stays unset here and
   * is linked by the core afterwards, which is why the mapping ignores it.
   *
   * @param workflowModuleProperties What SmallRye bound below
   *          <code>vanillabp.workflow-modules.&lt;id&gt;</code>
   * @return The core's section of that workflow module
   */
  @Mapping(target = "workflowModuleId", ignore = true)
  @Mapping(target = "prioritizedAdapters", qualifiedByName = "unwrapStringList")
  @Mapping(target = "allowFullSyncWithBpms", qualifiedByName = "unwrapBoolean")
  WorkflowModuleAdapterProperties toCore(
      QuarkusMigrationAdapterProperties.WorkflowModuleProperties workflowModuleProperties);

  /**
   * Copies the section of one workflow
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;.*</code>).
   * The BPMN process id and the way back to the workflow module stay unset here and are
   * linked by the core afterwards, which is why the mapping ignores them.
   *
   * @param workflowProperties What SmallRye bound below the
   *          <code>workflows.&lt;bpmnProcessId&gt;</code> of a workflow module
   * @return The core's section of that workflow
   */
  @Mapping(target = "bpmnProcessId", ignore = true)
  @Mapping(target = "workflowModule", ignore = true)
  @Mapping(target = "prioritizedAdapters", qualifiedByName = "unwrapStringList")
  @Mapping(target = "allowFullSyncWithBpms", qualifiedByName = "unwrapBoolean")
  WorkflowAdapterProperties toCore(
      QuarkusMigrationAdapterProperties.WorkflowProperties workflowProperties);

  /**
   * Copies the section of one BPMN task
   * (<code>...workflows.&lt;bpmnProcessId&gt;.tasks.&lt;taskId&gt;.*</code>), the most
   * specific level of the resolution.
   *
   * @param taskProperties What SmallRye bound below the <code>tasks.&lt;taskId&gt;</code>
   *          of a workflow
   * @return The core's section of that task
   */
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
   * Unwraps the setting whether unguarded aggregate writes are accepted
   * ({@code Optional.empty()} becomes {@code null}: a workflow module which says nothing
   * inherits what the application configured globally).
   *
   * @param value The optional setting
   * @return The setting or {@code null}
   */
  @Named("unwrapUnguardedAggregateWrites")
  default TransactionsProperties.UnguardedAggregateWrites unwrapUnguardedAggregateWrites(
      final Optional<TransactionsProperties.UnguardedAggregateWrites> value) {

    return value.orElse(null);

  }

  /**
   * Unwraps the optional decision about adapters which cannot locate workflows
   * ({@code Optional.empty()} becomes {@code null}: a workflow module which says nothing
   * inherits what the application configured globally).
   *
   * @param value The optional setting
   * @return The setting or {@code null}
   */
  @Named("unwrapGuessingAdapters")
  default ElectionProperties.GuessingAdapters unwrapGuessingAdapters(
      final Optional<ElectionProperties.GuessingAdapters> value) {

    return value.orElse(null);

  }

  /**
   * Unwraps an optional flag ({@code Optional.empty()} becomes {@code null}: a workflow
   * module which says nothing inherits what the application configured globally).
   *
   * @param value The optional flag
   * @return The flag or {@code null}
   */
  @Named("unwrapBoolean")
  default Boolean unwrapBoolean(
      final Optional<Boolean> value) {

    return value.orElse(null);

  }

  /**
   * Unwraps an optional duration ({@code Optional.empty()} becomes {@code null}: a level
   * which says nothing inherits what the next less specific one configured).
   *
   * @param value The optional duration
   * @return The duration or {@code null}
   */
  @Named("unwrapDuration")
  default java.time.Duration unwrapDuration(
      final Optional<java.time.Duration> value) {

    return value.orElse(null);

  }

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

  /**
   * Unwraps the outfaded versions ({@code Optional.empty()} becomes {@code null}, not
   * an empty list): the core walks the levels of an adapter-scoped property and takes
   * the first one which configured something, so "nothing here" has to stay null.
   *
   * @param value The optional list
   * @return The unwrapped list or {@code null}
   */
  @Named("unwrapOutfadedVersions")
  default List<String> unwrapOutfadedVersions(
      final Optional<List<String>> value) {

    return value
        .filter(versions -> !versions.isEmpty())
        .map(List::copyOf)
        .orElse(null);

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
