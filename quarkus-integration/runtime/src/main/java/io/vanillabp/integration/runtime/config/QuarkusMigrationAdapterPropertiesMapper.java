package io.vanillabp.integration.runtime.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.ElectionProperties;
import io.vanillabp.integration.adapter.migration.config.MessageAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MetricsProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TransactionsProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;

/**
 * Copies the Quarkus {@code @ConfigMapping} interface onto the core model, which does not
 * depend on a platform. It only copies. It does no checks and sets no defaults. Both happen
 * in the core ({@code validateProperties()} and {@code normalize()}).
 * <p>
 * MapStruct used to generate this class. Now it is written by hand. The annotation pulled
 * {@code org.mapstruct:mapstruct} onto the compile classpath of every application which uses
 * our Quarkus integration. And no published artifact makes an application fetch MapStruct
 * (see decision 81 in the repository's DECISIONS.md).
 * <p>
 * The generator gave us a compile error for a property which only one side knows.
 * Hand-written code cannot give us that. {@code QuarkusMigrationAdapterPropertiesMapperGuardTest}
 * takes its place. It walks both sides by reflection. It fails as soon as a value is read
 * nowhere or written nowhere.
 * <p>
 * Five fields of the core are set by nobody here. {@code conventionalResourcesLocations}
 * is derived from the classpath by {@code MigrationAdapterProperties#normalize}, and
 * {@code startupFindings} is where the startup checks leave what they found. The
 * back-references {@code workflowModuleId}, {@code bpmnProcessId} and
 * {@code workflowModule} are linked by {@code MigrationAdapterProperties#validateAndLink()}
 * once the whole tree stands.
 * <p>
 * The outbox defaults are declared TWICE by necessity (SmallRye requires
 * {@code @WithDefault} on the interface, the core carries them as field initializers).
 * {@code QuarkusMigrationAdapterPropertiesMapperTest} compares most of them. It does not
 * compare the housekeeping window ({@code housekeeping.start} and {@code housekeeping.end})
 * and {@code mongo.housekeepingCollection}.
 */
public final class QuarkusMigrationAdapterPropertiesMapper {

  /**
   * The mapper in use. Nobody has to inject anything to reach it. The recorder which builds
   * the runtime configuration uses the same instance as a store which reads its own section
   * lazily.
   */
  public static final QuarkusMigrationAdapterPropertiesMapper INSTANCE = new QuarkusMigrationAdapterPropertiesMapper();

  /**
   * Nobody creates a second one. The mapper holds no state. So {@link #INSTANCE} is the only
   * one to hand out.
   */
  private QuarkusMigrationAdapterPropertiesMapper() {
  }

  /**
   * Copies everything which an application wrote below <code>vanillabp.</code> onto the
   * core model. This is where the two halves meet. Quarkus binds configuration through a
   * SmallRye <code>&#64;ConfigMapping</code> interface. Checks, defaults and the resolution
   * of a setting happen in the core, and Spring Boot shares them.
   * <p>
   * What comes back cannot be used yet. It is not checked, it has no defaults, and its
   * back-references are still unset. {@link QuarkusMigrationAdapterTransformer} copies first
   * and lets the core check afterwards. The result becomes the
   * {@link MigrationAdapterProperties} bean of the application.
   *
   * @param properties What SmallRye bound from the keys below <code>vanillabp.</code>
   * @return The same settings as the platform-neutral model of the core
   */
  public MigrationAdapterProperties toCore(
      final QuarkusMigrationAdapterProperties properties) {

    if (properties == null) {
      return null;
    }

    final var core = MigrationAdapterProperties.builder();

    core.prioritizedAdapters(unwrapListOrEmpty(properties.prioritizedAdapters()));
    core.retiredAdapters(unwrapListOrEmpty(properties.retiredAdapters()));
    core.allowFullSyncWithBpms(unwrap(properties.allowFullSyncWithBpms()));
    core.acceptExpressionsInTheModel(unwrap(properties.acceptExpressionsInTheModel()));
    core.declaredTaskParams(unwrapListOrNull(properties.declaredTaskParams()));
    core.messageTimeToLive(unwrap(properties.messageTimeToLive()));
    core.implementedExternally(unwrap(properties.implementedExternally()));
    core.adapters(copyValues(properties.adapters(), this::toCore));
    core.resourcesLocation(unwrap(properties.resourcesLocation()));
    core.workflowModules(copyValues(properties.workflowModules(), this::toCore));
    final var extensions = properties.extensions();
    if (extensions != null) {
      core.extensions(new LinkedHashMap<>(extensions));
    }
    core.outbox(toCore(properties.outbox()));
    core.workflowAdapterCache(toCore(properties.workflowAdapterCache()));
    core.transactions(toCore(properties.transactions()));
    core.election(toCore(properties.election()));
    core.delivery(toCore(properties.delivery()));
    core.metrics(toCore(properties.metrics()));

    return core.build();

  }

  /**
   * Copies the outbox section (<code>vanillabp.outbox.*</code>) alone. The stores call it
   * directly. The JDBC outbox, the dispatcher of the MongoDB outbox and both delivery logs
   * have to know their table or their collection before the startup event is observed. For
   * that, they need only their own section.
   *
   * @param outboxProperties What SmallRye bound below <code>vanillabp.outbox</code>
   * @return The outbox settings of the core: the intervals of the dispatcher, the
   *         retention, and the names of the table or of the collections
   */
  public PhaseTwoOutboxProperties toCore(
      final QuarkusMigrationAdapterProperties.PhaseTwoOutboxProperties outboxProperties) {

    if (outboxProperties == null) {
      return null;
    }

    final var core = PhaseTwoOutboxProperties.builder();

    core.pollInterval(outboxProperties.pollInterval());
    core.attemptFrequency(outboxProperties.attemptFrequency());
    core.maxAttemptFrequency(outboxProperties.maxAttemptFrequency());
    core.blockAfterAttempts(outboxProperties.blockAfterAttempts());
    core.waitForVisibilityAtMost(outboxProperties
        .waitForVisibilityAtMost()
        .orElse(null));
    core.dispatchThreads(outboxProperties.dispatchThreads());
    core.createSchema(outboxProperties.createSchema());
    core.retention(outboxProperties.retention());
    core.jdbc(toCore(outboxProperties.jdbc()));
    core.mongo(toCore(outboxProperties.mongo()));
    core.housekeeping(toCore(outboxProperties.housekeeping()));

    return core.build();

  }

  /**
   * Copies the section of the JDBC outbox (<code>vanillabp.outbox.jdbc.*</code>).
   * Unlike the MongoDB section next to it, the table names have no
   * <code>&#64;WithDefault</code>. An unset name arrives as <code>null</code>, and the core
   * reads that as "use the default name". So the name is decided in one place for both
   * platforms.
   *
   * @param jdbcOutboxProperties What SmallRye bound below
   *          <code>vanillabp.outbox.jdbc</code>
   * @return The settings of the outbox which stores into a relational database
   */
  public PhaseTwoOutboxProperties.JdbcOutboxProperties toCore(
      final QuarkusMigrationAdapterProperties.JdbcOutboxProperties jdbcOutboxProperties) {

    if (jdbcOutboxProperties == null) {
      return null;
    }

    final var core = PhaseTwoOutboxProperties.JdbcOutboxProperties.builder();

    core.table(unwrap(jdbcOutboxProperties.table()));
    core.housekeepingTable(unwrap(jdbcOutboxProperties.housekeepingTable()));
    core.enabled(jdbcOutboxProperties.enabled());
    core.payloadTable(unwrap(jdbcOutboxProperties.payloadTable()));
    core.deliveryTable(unwrap(jdbcOutboxProperties.deliveryTable()));

    return core.build();

  }

  /**
   * Copies the section of the MongoDB outbox (<code>vanillabp.outbox.mongo.*</code>). It
   * names the collection of the outbox entries, the collection of their payloads and the
   * collection of the delivery records.
   *
   * @param mongoOutboxProperties What SmallRye bound below
   *          <code>vanillabp.outbox.mongo</code>
   * @return The settings of the outbox which stores into MongoDB
   */
  public PhaseTwoOutboxProperties.MongoOutboxProperties toCore(
      final QuarkusMigrationAdapterProperties.MongoOutboxProperties mongoOutboxProperties) {

    if (mongoOutboxProperties == null) {
      return null;
    }

    final var core = PhaseTwoOutboxProperties.MongoOutboxProperties.builder();

    core.enabled(mongoOutboxProperties.enabled());
    core.collection(mongoOutboxProperties.collection());
    core.payloadCollection(unwrap(mongoOutboxProperties.payloadCollection()));
    core.deliveryCollection(mongoOutboxProperties.deliveryCollection());
    core.housekeepingCollection(mongoOutboxProperties.housekeepingCollection());

    return core.build();

  }

  /**
   * Copies the section of the housekeeping
   * (<code>vanillabp.outbox.housekeeping.*</code>): the window and the zone in which it is
   * read.
   *
   * @param housekeepingProperties What SmallRye bound below
   *          <code>vanillabp.outbox.housekeeping</code>
   * @return When the outbox does its housekeeping
   */
  public PhaseTwoOutboxProperties.HousekeepingProperties toCore(
      final QuarkusMigrationAdapterProperties.HousekeepingProperties housekeepingProperties) {

    if (housekeepingProperties == null) {
      return null;
    }

    final var core = PhaseTwoOutboxProperties.HousekeepingProperties.builder();

    core.zone(unwrap(housekeepingProperties.zone()));
    core.start(housekeepingProperties.start());
    core.end(housekeepingProperties.end());

    return core.build();

  }

  /**
   * Copies the section of the election cache
   * (<code>vanillabp.workflow-adapter-cache.*</code>). The producer of the default cache in
   * memory calls it directly. That cache is built before anybody asks the core model for
   * anything.
   *
   * @param workflowAdapterCacheProperties What SmallRye bound below
   *          <code>vanillabp.workflow-adapter-cache</code>
   * @return The limits which the default cache is built with
   */
  public WorkflowAdapterCacheProperties toCore(
      final QuarkusMigrationAdapterProperties.WorkflowAdapterCacheProperties workflowAdapterCacheProperties) {

    if (workflowAdapterCacheProperties == null) {
      return null;
    }

    final var core = WorkflowAdapterCacheProperties.builder();

    core.maxEntries(workflowAdapterCacheProperties.maxEntries());
    core.timeToLive(workflowAdapterCacheProperties.timeToLive());
    core.endedTimeToLive(workflowAdapterCacheProperties.endedTimeToLive());
    core.releaseOnWorkflowEnd(workflowAdapterCacheProperties.releaseOnWorkflowEnd());

    return core.build();

  }

  /**
   * Copies the transaction section (<code>vanillabp.transactions.*</code>). A workflow
   * module may write it again for itself.
   *
   * @param transactionsProperties What SmallRye bound below
   *          <code>vanillabp.transactions</code> or below the same key of a workflow
   *          module
   * @return What VanillaBP does about an aggregate written outside its transaction
   */
  public TransactionsProperties toCore(
      final QuarkusMigrationAdapterProperties.TransactionsProperties transactionsProperties) {

    if (transactionsProperties == null) {
      return null;
    }

    final var core = TransactionsProperties.builder();

    core.unguardedAggregateWrites(unwrap(transactionsProperties.unguardedAggregateWrites()));

    return core.build();

  }

  /**
   * Copies the election section (<code>vanillabp.election.*</code>). A workflow module may
   * write it again for itself.
   *
   * @param electionProperties What SmallRye bound below <code>vanillabp.election</code>
   *          or below the same key of a workflow module
   * @return What VanillaBP does about an adapter which cannot say whether its BPMS
   *         holds a workflow
   */
  public ElectionProperties toCore(
      final QuarkusMigrationAdapterProperties.ElectionProperties electionProperties) {

    if (electionProperties == null) {
      return null;
    }

    final var core = ElectionProperties.builder();

    core.guessingAdapters(unwrap(electionProperties.guessingAdapters()));

    return core.build();

  }

  /**
   * Copies a delivery section (<code>vanillabp.delivery.*</code>). It may be written at all
   * four levels, down to the single task. Both default delivery logs call it directly for
   * the global section. The reason: the retention of a record is read there and nowhere
   * else.
   *
   * @param deliveryProperties What SmallRye bound below <code>vanillabp.delivery</code>
   *          of one level
   * @return What that level says about the records of processed task deliveries
   */
  public DeliveryProperties toCore(
      final QuarkusMigrationAdapterProperties.DeliveryProperties deliveryProperties) {

    if (deliveryProperties == null) {
      return null;
    }

    final var core = DeliveryProperties.builder();

    core.releaseOnWorkflowEnd(unwrap(deliveryProperties.releaseOnWorkflowEnd()));
    core.maxTaskAge(unwrap(deliveryProperties.maxTaskAge()));
    core.retention(unwrap(deliveryProperties.retention()));
    core.workflowStartRetention(unwrap(deliveryProperties.workflowStartRetention()));
    core
        .keepWorkflowStartWhileAggregateExists(
            unwrap(deliveryProperties.keepWorkflowStartWhileAggregateExists()));
    core.checkOpenTasksOnDelivery(unwrap(deliveryProperties.checkOpenTasksOnDelivery()));
    core.maxOpenTasksChecked(unwrap(deliveryProperties.maxOpenTasksChecked()));

    return core.build();

  }

  /**
   * Copies the metrics section (<code>vanillabp.metrics.*</code>). It holds the one number
   * which keeps a gauge cheap to read (see decision 18 in the repository's DECISIONS.md).
   *
   * @param metricsProperties What SmallRye bound below <code>vanillabp.metrics</code>
   * @return How long one measurement is reused
   */
  public MetricsProperties toCore(
      final QuarkusMigrationAdapterProperties.MetricsProperties metricsProperties) {

    if (metricsProperties == null) {
      return null;
    }

    final var core = MetricsProperties.builder();

    core.gaugeCache(metricsProperties.gaugeCache());

    return core.build();

  }

  /**
   * Copies the section of one configured adapter
   * (<code>vanillabp.adapters.&lt;id&gt;.*</code>). The id is the identity of that adapter
   * instance. The type names the BPMS behind it. So two adapters of the same type may stand
   * next to each other. A migration from one BPMS to another needs exactly that (see
   * decision 17 in the repository's DECISIONS.md). This section is also the least specific
   * level where a setting of that adapter may be written.
   *
   * @param adapterConfiguration What SmallRye bound below
   *          <code>vanillabp.adapters.&lt;id&gt;</code>
   * @return The core's section of that adapter instance
   */
  public AdapterConfigProperties toCore(
      final QuarkusMigrationAdapterProperties.AdapterConfiguration adapterConfiguration) {

    if (adapterConfiguration == null) {
      return null;
    }

    final var core = AdapterConfigProperties.builder();

    core.outfadedVersions(unwrapOutfadedVersions(adapterConfiguration.outfadedVersions()));
    core.allowFullSyncWithBpms(unwrap(adapterConfiguration.allowFullSyncWithBpms()));
    core.resourcesLocation(unwrap(adapterConfiguration.resourcesLocation()));
    core.nameClashAvoidance(unwrap(adapterConfiguration.nameClashAvoidance()));
    core.prefixTaskDefinitionsPerProcess(unwrap(adapterConfiguration.prefixTaskDefinitionsPerProcess()));
    core.deduplicateDeliveries(unwrap(adapterConfiguration.deduplicateDeliveries()));
    core.implementedExternally(unwrap(adapterConfiguration.implementedExternally()));
    core.outfadedVersionsInUse(unwrap(adapterConfiguration.outfadedVersionsInUse()));
    core.messageTimeToLive(unwrap(adapterConfiguration.messageTimeToLive()));
    final var extensions = adapterConfiguration.extensions();
    if (extensions != null) {
      core.extensions(new LinkedHashMap<>(extensions));
    }
    core.type(unwrap(adapterConfiguration.type()));
    core.deploymentFailure(unwrap(adapterConfiguration.deploymentFailure()));

    return core.build();

  }

  /**
   * Copies what a workflow module, a workflow or a task says about ONE adapter, for example
   * <code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.*</code>. Such a
   * section has the same keys as the adapter's own section. The most specific one which
   * sets a key wins (see decision 7 in the repository's DECISIONS.md).
   *
   * @param adapterProperties What SmallRye bound below the
   *          <code>adapters.&lt;id&gt;</code> of one level
   * @return What that level says about that adapter
   */
  public AdapterProperties toCore(
      final QuarkusMigrationAdapterProperties.AdapterProperties adapterProperties) {

    if (adapterProperties == null) {
      return null;
    }

    final var core = AdapterProperties.builder();

    core.outfadedVersions(unwrapOutfadedVersions(adapterProperties.outfadedVersions()));
    core.allowFullSyncWithBpms(unwrap(adapterProperties.allowFullSyncWithBpms()));
    core.resourcesLocation(unwrap(adapterProperties.resourcesLocation()));
    core.nameClashAvoidance(unwrap(adapterProperties.nameClashAvoidance()));
    core.prefixTaskDefinitionsPerProcess(unwrap(adapterProperties.prefixTaskDefinitionsPerProcess()));
    core.deduplicateDeliveries(unwrap(adapterProperties.deduplicateDeliveries()));
    core.implementedExternally(unwrap(adapterProperties.implementedExternally()));
    core.outfadedVersionsInUse(unwrap(adapterProperties.outfadedVersionsInUse()));
    core.messageTimeToLive(unwrap(adapterProperties.messageTimeToLive()));
    final var extensions = adapterProperties.extensions();
    if (extensions != null) {
      core.extensions(new LinkedHashMap<>(extensions));
    }

    return core.build();

  }

  /**
   * Copies the section of one workflow module
   * (<code>vanillabp.workflow-modules.&lt;id&gt;.*</code>). Its id stays unset here. The
   * core links it afterwards.
   *
   * @param workflowModuleProperties What SmallRye bound below
   *          <code>vanillabp.workflow-modules.&lt;id&gt;</code>
   * @return The core's section of that workflow module
   */
  public WorkflowModuleAdapterProperties toCore(
      final QuarkusMigrationAdapterProperties.WorkflowModuleProperties workflowModuleProperties) {

    if (workflowModuleProperties == null) {
      return null;
    }

    final var core = WorkflowModuleAdapterProperties.builder();

    core.prioritizedAdapters(unwrapListOrEmpty(workflowModuleProperties.prioritizedAdapters()));
    core.allowFullSyncWithBpms(unwrap(workflowModuleProperties.allowFullSyncWithBpms()));
    core.acceptExpressionsInTheModel(unwrap(workflowModuleProperties.acceptExpressionsInTheModel()));
    core.declaredTaskParams(unwrapListOrNull(workflowModuleProperties.declaredTaskParams()));
    core.messageTimeToLive(unwrap(workflowModuleProperties.messageTimeToLive()));
    core.implementedExternally(unwrap(workflowModuleProperties.implementedExternally()));
    core.adapters(copyValues(workflowModuleProperties.adapters(), this::toCore));
    core.workflows(copyValues(workflowModuleProperties.workflows(), this::toCore));
    core.transactions(toCore(workflowModuleProperties.transactions()));
    core.election(toCore(workflowModuleProperties.election()));
    core.delivery(toCore(workflowModuleProperties.delivery()));
    final var extensions = workflowModuleProperties.extensions();
    if (extensions != null) {
      core.extensions(new LinkedHashMap<>(extensions));
    }

    return core.build();

  }

  /**
   * Copies the section of one workflow
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;.*</code>).
   * The BPMN process id and the way back to the workflow module stay unset here. The core
   * links them afterwards.
   *
   * @param workflowProperties What SmallRye bound below the
   *          <code>workflows.&lt;bpmnProcessId&gt;</code> of a workflow module
   * @return The core's section of that workflow
   */
  public WorkflowAdapterProperties toCore(
      final QuarkusMigrationAdapterProperties.WorkflowProperties workflowProperties) {

    if (workflowProperties == null) {
      return null;
    }

    final var core = WorkflowAdapterProperties.builder();

    core.prioritizedAdapters(unwrapListOrEmpty(workflowProperties.prioritizedAdapters()));
    core.allowFullSyncWithBpms(unwrap(workflowProperties.allowFullSyncWithBpms()));
    core.acceptExpressionsInTheModel(unwrap(workflowProperties.acceptExpressionsInTheModel()));
    core.declaredAggregateValues(unwrapListOrEmpty(workflowProperties.declaredAggregateValues()));
    core.declaredTaskParams(unwrapListOrNull(workflowProperties.declaredTaskParams()));
    core.messageTimeToLive(unwrap(workflowProperties.messageTimeToLive()));
    core.implementedExternally(unwrap(workflowProperties.implementedExternally()));
    core.adapters(copyValues(workflowProperties.adapters(), this::toCore));
    core.tasks(copyValues(workflowProperties.tasks(), this::toCore));
    core.messages(copyValues(workflowProperties.messages(), this::toCore));
    final var extensions = workflowProperties.extensions();
    if (extensions != null) {
      core.extensions(new LinkedHashMap<>(extensions));
    }
    core.delivery(toCore(workflowProperties.delivery()));

    return core.build();

  }

  /**
   * Copies the section of one BPMN task
   * (<code>...workflows.&lt;bpmnProcessId&gt;.tasks.&lt;taskId&gt;.*</code>). This is the
   * most specific level of the resolution.
   *
   * @param taskProperties What SmallRye bound below the <code>tasks.&lt;taskId&gt;</code>
   *          of a workflow
   * @return The core's section of that task
   */
  public TaskAdapterProperties toCore(
      final QuarkusMigrationAdapterProperties.TaskProperties taskProperties) {

    if (taskProperties == null) {
      return null;
    }

    final var core = TaskAdapterProperties.builder();

    core.declaredTaskParams(unwrapListOrNull(taskProperties.declaredTaskParams()));
    core.messageTimeToLive(unwrap(taskProperties.messageTimeToLive()));
    core.implementedExternally(unwrap(taskProperties.implementedExternally()));
    core.adapters(copyValues(taskProperties.adapters(), this::toCore));
    final var extensions = taskProperties.extensions();
    if (extensions != null) {
      core.extensions(new LinkedHashMap<>(extensions));
    }
    core.delivery(toCore(taskProperties.delivery()));

    return core.build();

  }

  /**
   * Copies the section of one message which a workflow waits for
   * (<code>...workflows.&lt;bpmnProcessId&gt;.messages.&lt;messageName&gt;.*</code>).
   *
   * @param messageProperties What SmallRye bound below the
   *          <code>messages.&lt;messageName&gt;</code> of a workflow
   * @return The core's section of that message
   */
  public MessageAdapterProperties toCore(
      final QuarkusMigrationAdapterProperties.MessageProperties messageProperties) {

    if (messageProperties == null) {
      return null;
    }

    final var core = new MessageAdapterProperties();
    core.setMessageTimeToLive(unwrap(messageProperties.messageTimeToLive()));
    final var adapters = copyValues(messageProperties.adapters(), this::toCore);
    if (adapters != null) {
      core.setAdapters(adapters);
    }
    return core;

  }

  /**
   * Copies what one message says about ONE adapter
   * (<code>...messages.&lt;messageName&gt;.adapters.&lt;id&gt;.*</code>).
   *
   * @param messageAdapterProperties What SmallRye bound below the
   *          <code>adapters.&lt;id&gt;</code> of a message
   * @return What that message says about that adapter
   */
  public MessageAdapterProperties.MessageSettings toCore(
      final QuarkusMigrationAdapterProperties.MessageAdapterProperties messageAdapterProperties) {

    if (messageAdapterProperties == null) {
      return null;
    }

    final var core = new MessageAdapterProperties.MessageSettings();
    core.setMessageTimeToLive(unwrap(messageAdapterProperties.messageTimeToLive()));
    return core;

  }

  /**
   * Copies a map of sections. It keeps the order in which SmallRye bound the keys. The
   * configured adapters, the workflow modules, the workflows and the tasks are all copied
   * this way.
   *
   * @param <S> The section type of the SmallRye interface
   * @param <T> The section type of the core model
   * @param sections The map to copy, or <code>null</code>
   * @param toCore What turns one section into its counterpart
   * @return The copied map, or <code>null</code> where the map itself was
   *         <code>null</code>. Then the core keeps its own default
   */
  private static <S, T> Map<String, T> copyValues(
      final Map<String, S> sections,
      final Function<S, T> toCore) {

    if (sections == null) {
      return null;
    }

    final Map<String, T> copy = LinkedHashMap.newLinkedHashMap(sections.size());
    sections.forEach((
        key,
        section) -> copy.put(key, toCore.apply(section)));

    return copy;

  }

  /**
   * Unwraps an optional value. {@code Optional.empty()} becomes {@code null}, like an unset
   * property of the core model. A level which says nothing inherits what the next less
   * specific one configured.
   *
   * @param <T> The value type
   * @param value The optional value
   * @return The unwrapped value or {@code null}
   */
  private static <T> T unwrap(
      final Optional<T> value) {

    return value.orElse(null);

  }

  /**
   * Unwraps the outfaded versions. {@code Optional.empty()} becomes {@code null}, not an
   * empty list. The core walks the levels of an adapter-scoped property and takes the first
   * one which configured something. So "nothing here" has to stay null.
   *
   * @param value The optional list
   * @return The unwrapped list or {@code null}
   */
  private static List<String> unwrapOutfadedVersions(
      final Optional<List<String>> value) {

    return value
        .filter(versions -> !versions.isEmpty())
        .map(List::copyOf)
        .orElse(null);

  }

  /**
   * Unwraps optional lists. {@code Optional.empty()} becomes an empty list, like the
   * default of the core model.
   *
   * @param value The optional list
   * @return The unwrapped list or an empty list
   */
  private static List<String> unwrapListOrEmpty(
      final Optional<List<String>> value) {

    return value
        .map(List::copyOf)
        .orElse(List.of());

  }

  /**
   * Unwraps optional lists of a setting which the core resolves over several levels. There,
   * <code>null</code> says "this level says nothing". An empty list would say "this level
   * declares none", and that stops the walk one level too early.
   * <p>
   * <code>vanillabp.declared-task-params</code> is such a setting. Every workflow module
   * writes a section of its own. So an empty list there would hide what the application
   * declared. The same holds for a workflow and for a task (see
   * {@code QuarkusDeclaredTaskParamsLevelsTest}).
   *
   * @param value The optional list
   * @return The unwrapped list, or <code>null</code> where the level wrote nothing
   */
  private static List<String> unwrapListOrNull(
      final Optional<List<String>> value) {

    return value
        .map(List::copyOf)
        .orElse(null);

  }

}
