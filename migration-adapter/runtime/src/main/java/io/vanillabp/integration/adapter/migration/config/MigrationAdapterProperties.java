package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.vanillabp.integration.extension.spi.settings.SettingsLevel;
import io.vanillabp.integration.extension.spi.settings.SettingsResolution;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * Everything below {@code vanillabp}, after both platforms have bound it: the adapter
 * sections, the workflow-module sections, and the settings which an adapter may get per
 * task, per workflow, per workflow module, or for itself.
 * <p>
 * Other places rely on two rules of this class. {@link #normalize(ClasspathFacts)} derives
 * what a convention can derive, before anything is checked. So an application which
 * configures nothing still boots, and its own log leads it on, not the documentation
 * (decision 8 in the repository's DECISIONS.md). {@code resolveForAdapter} is the only
 * implementation of the lookup over four levels: task before workflow before workflow
 * module before adapter. That is why a new adapter-specific setting costs a key and nothing
 * else (decision 7 in the repository's DECISIONS.md). {@code resolveForExtension} resolves
 * an EXTENSION setting over the same levels, plus the adapter section of each of them. It
 * walks them with {@link SettingsResolution}, the one implementation which every plug-in
 * uses (decision 53 in the repository's DECISIONS.md).
 */
public class MigrationAdapterProperties extends AdaptersConfigurationProperties {

  /**
   * The root of every property which VanillaBP reads: <code>vanillabp</code>. Every message
   * which names a key builds it from here. So what a developer reads in the log is spelled
   * the way it has to be written in the configuration.
   */
  public static final String PREFIX = "vanillabp";

  /**
   * The empty configuration from which a binder starts. Both platforms create this object
   * and then write everything below <code>vanillabp</code> into it, one setter per key. An
   * application which configures nothing keeps the defaults. {@link #normalize(ClasspathFacts)}
   * and the checks lead it on from there.
   * <p>
   * It asks the builder for the values, and that is not a detour. Every default of this class
   * is on the builder as well as on the field, and both ways into an object end here. A
   * constructor which set the fields itself would have to repeat every default. The first
   * default somebody forgets would leave every map of this class <code>null</code>, and every
   * sub-section missing.
   */
  public MigrationAdapterProperties() {

    this(builder());

  }

  /**
   * The location from which BPMN resources are loaded, and whether that location has
   * VanillaBP BPMN (true) or BPMN for the target BPMS (false).
   *
   * @param location The resources location
   * @param vanillaBpBpmn Whether the location contains VanillaBP BPMN
   */
  public record ResourcesLocation(String location, boolean vanillaBpBpmn) {
  }

  /**
   * The configuration of all known adapters (properties section
   * <code>vanillabp.adapters.&lt;id&gt;.*</code>). The keys are the adapter IDs. A key can be
   * an adapter's type or a custom identifier. For a custom identifier, the
   * {@link AdapterConfigProperties#getType() type} property has to name the adapter type from
   * which the custom adapter is derived. For an identifier which is an adapter type, the type
   * property may be left out.
   */
  private Map<String, AdapterConfigProperties> adapters = Map.of();

  /**
   * Where to load VanillaBP BPMN files from which are NOT specific to any adapter.
   */
  private String resourcesLocation;

  /**
   * Properties specific to workflow modules.
   */
  private Map<String, WorkflowModuleAdapterProperties> workflowModules = Map.of();

  /**
   * The settings of the extensions (properties section
   * <code>vanillabp.extensions.&lt;extension&gt;.*</code>). They can be overridden per
   * workflow module, per workflow and per task. The keys are the extension ids. The values
   * are what the application wrote below them, keyed by the rest of the path. What the keys
   * MEAN is the extension's own business (see {@link ExtensionProperties}).
   */
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * The configuration of the default {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
   * implementations (properties section <code>vanillabp.outbox</code>).
   */
  private PhaseTwoOutboxProperties outbox = new PhaseTwoOutboxProperties();

  /**
   * Refused here on purpose. The permission to share a whole workflow aggregate belongs to
   * the single workflow (see decision 66 in the repository's DECISIONS.md). It is bound at
   * this level, so that a line written here gets a message which says where it belongs,
   * instead of being ignored.
   */
  private Boolean allowFullSyncWithBpms;

  /**
   * Whether the expressions of every BPMN model of this application are meant as they are
   * (<code>vanillabp.accept-expressions-in-the-model</code>). It is the least specific of the
   * three levels, for an application which decided it once.
   */
  private Boolean acceptExpressionsInTheModel;

  /**
   * The <code>&#64;TaskParam</code> parameters of the whole application whose type the
   * developer declared (<code>vanillabp.declared-task-params</code>). It is the least specific
   * of the four levels at which the declaration may be written. Use it where every task of
   * the application reads the same value.
   */
  private java.util.List<String> declaredTaskParams;

  /**
   * Whether something other than this application serves every task which does not say
   * otherwise (<code>implemented-externally</code>, see {@link #implementedExternally}). It is
   * the least specific of the eight positions. <code>null</code> means "not configured at this
   * level".
   */
  private Boolean implementedExternally;

  /**
   * The configuration of the default election cache
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} (properties section
   * <code>vanillabp.workflow-adapter-cache</code>).
   */
  private WorkflowAdapterCacheProperties workflowAdapterCache = new WorkflowAdapterCacheProperties();

  /**
   * What VanillaBP does about a workflow aggregate whose store is not covered by the
   * transaction which it opens (properties section <code>vanillabp.transactions</code>, which
   * can be overridden per workflow module).
   */
  private TransactionsProperties transactions = new TransactionsProperties();

  /**
   * What VanillaBP does about a prioritized adapter which cannot find workflows (properties
   * section <code>vanillabp.election</code>, which can be overridden per workflow module).
   */
  private ElectionProperties election = new ElectionProperties();

  /**
   * What VanillaBP does with the records of processed task deliveries (properties section
   * <code>vanillabp.delivery</code>, which can be overridden per workflow module, except the
   * retention, see <code>vanillabp.delivery.retention</code>).
   */
  private DeliveryProperties delivery = new DeliveryProperties();

  /**
   * What VanillaBP publishes as metrics (properties section <code>vanillabp.metrics</code>).
   * Today that is only how long the measurement of a gauge which has to ask somebody is
   * reused.
   */
  private MetricsProperties metrics = new MetricsProperties();

  /**
   * Where every startup check puts what it found. So the whole start says it once, at its
   * end, instead of one line per check (see
   * {@link io.vanillabp.integration.adapter.migration.startup.StartupFindings}).
   * <p>
   * It sits on the configuration, because everything which does a startup check holds the
   * configuration already. It has no accessors which a binder would take for a property.
   * Nothing below <code>vanillabp</code> configures it.
   */
  private io.vanillabp.integration.adapter.migration.startup.StartupFindings startupFindings = new io.vanillabp.integration.adapter.migration.startup.StartupFindings();

  /**
   * Where a check reports what it found, also where it runs without a configuration.
   * <p>
   * A check built in a test has no bound <code>vanillabp.*</code> tree, and no start to close
   * either. So it gets a collection of its own and reports into it. That keeps the null check
   * out of every check which has one.
   *
   * @param properties The bound configuration, or <code>null</code>
   * @return Where to report, never <code>null</code>
   */
  public static io.vanillabp.integration.adapter.migration.startup.StartupFindings startupFindingsOf(
      final MigrationAdapterProperties properties) {

    return properties == null
        ? new io.vanillabp.integration.adapter.migration.startup.StartupFindings()
        : properties.startupFindings();

  }

  /**
   * Where a startup check reports what it found.
   *
   * @return The findings of this start, never <code>null</code>
   */
  public io.vanillabp.integration.adapter.migration.startup.StartupFindings startupFindings() {

    if (startupFindings == null) {
      // a builder of an older call site, or a binder which maps what it does not know to
      // null. Neither must cost a check its place to report to
      startupFindings = new io.vanillabp.integration.adapter.migration.startup.StartupFindings();
    }
    return startupFindings;

  }

  /**
   * Adapter ids which this application USED to have, and deliberately does not configure any
   * more. That is the last step of a BPMS migration, once nothing runs in the old BPMS any
   * more.
   * <p>
   * VanillaBP persists the adapter id of an entry of the phase-two outbox and of every
   * delivery record. An id for which such an entry still waits, and which nobody serves, is
   * worth a startup message. It means that the id was RENAMED, which loses workflows, or that
   * it was removed too early. Naming it here says that it is the second case, and that the
   * leftovers are known. It turns the message into a DEBUG line. The decision stays visible in
   * the configuration, instead of being drowned in a log filter, exactly like an adapter's
   * <code>accept-unscoped-identifiers</code>.
   */
  private List<String> retiredAdapters = List.of();

  /**
   * A view derived from {@code getAdapters()}: adapter ID mapped to the adapter's type. An
   * adapter entry without an explicit type (<code>AdapterConfigProperties.getType()</code>)
   * has its ID as its type.
   *
   * @return All available adapters (key = adapter ID, value = adapter type)
   */
  public Map<String, String> adapterTypes() {

    return adapters
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> entry.getValue().getType() != null
                ? entry.getValue().getType()
                : entry.getKey()));

  }

  /**
   * The adapter ids which one adapter TYPE serves, in a stable (sorted) order. A platform
   * needs this to register one set of beans per configured adapter id. An extension which
   * bridges to a BPMS needs it to register one bridge per engine of that BPMS. Several ids of
   * one type are the migration case.
   * <p>
   * Three things decide it, and leaving one out loses adapters silently:
   * <ol>
   * <li>every section of {@code getAdapters()} whose type (or default type) is this one,
   * see {@link #adapterTypes()};</li>
   * <li>every id in {@code prioritized-adapters} which IS this type and has no section, or a
   * section which does not contradict it. Convention over configuration says that such an id
   * needs no section of its own. And this method is also asked on properties which were bound
   * but not yet {@link #normalize(ClasspathFacts) normalized};</li>
   * <li>nothing configured at all: the single adapter dependency IS the configuration, and
   * the id which the core derives is the type.</li>
   * </ol>
   * The second rule is the one which cannot be read from the bound sections. A section may
   * consist only of keys which this model does not know, for example an adapter's own
   * {@code rest-address} or {@code webapps}. Then no entry binds for it at all. So a
   * derivation which only runs where NOTHING bound is not enough. A migration setup showed
   * that. The new BPMS was configured, the old one only named in the order. And the beans of
   * the old adapter were gone as soon as any section had one core key.
   * <p>
   * Decision 39 in the repository's DECISIONS.md says why this lives here and not per
   * platform.
   *
   * @param adapterType The adapter's type, for example <code>camunda7</code>
   * @return The adapter ids of that type, sorted, possibly none
   */
  public List<String> adapterIdsOfType(
      final String adapterType) {

    final var ids = new TreeSet<String>();

    adapterTypes()
        .entrySet()
        .stream()
        .filter(entry -> adapterType.equals(entry.getValue()))
        .map(Map.Entry::getKey)
        .forEach(ids::add);

    getPrioritizedAdapters()
        .stream()
        .filter(adapterType::equals)
        // an id which happens to have the name of this type, but says it is another type,
        // belongs to that other adapter, whatever its name suggests
        .filter(adapterId -> {
          final var section = getAdapters().get(adapterId);
          return (section == null) || (section.getType() == null) || adapterType.equals(section.getType());
        })
        .forEach(ids::add);

    if (!ids.isEmpty()) {
      return List.copyOf(ids);
    }

    // nothing is configured at all. The caller cannot see the OTHER adapter types on the
    // classpath, and it does not have to. It asks for the ids of its own type, and the id
    // which the core would derive IS the type. Where the derivation does not apply (several
    // adapter types and no order configured), the check ends the boot anyway, and whatever
    // was registered for this id is never used
    if (getAdapters().isEmpty() && getPrioritizedAdapters().isEmpty()) {
      return List.of(adapterType);
    }
    return List.of();

  }

  /**
   * The base location from which the BPMN resources of a workflow module are read, when
   * neither an adapter-specific nor the global <code>resources-location</code> is configured.
   * {@link #normalize(ClasspathFacts)} derives it from the classpath facts. The key is the
   * workflow module ID. The value is the location WITHOUT the adapter ID, which is added per
   * adapter (see {@link #getAdapterResourcesLocationsFor(String, String)}).
   * <p>
   * Spring Boot binds a map through its getter. So this one is a configuration key, whatever
   * accessors it has. That is why it is marked as unsupported in
   * <code>META-INF/additional-spring-configuration-metadata.json</code> of the Spring Boot
   * module. That keeps a development environment from offering a key whose value the next
   * derivation throws away.
   */
  private Map<String, List<String>> conventionalResourcesLocations = Map.of();

  /**
   * Applies the defaults of convention over configuration to the bound properties. If
   * exactly one adapter is configured, the property <code>vanillabp.prioritized-adapters</code>
   * may be left out, and it defaults to that adapter. {@link #validateProperties(List, List)}
   * calls this. It has to be called explicitly if properties objects are used without
   * running the checks.
   * <p>
   * The derivations from the classpath (adapter sections, workflow-module sections, resources
   * locations) need facts about the application. So only {@link #normalize(ClasspathFacts)}
   * applies them.
   */
  public void normalize() {

    if (getPrioritizedAdapters().isEmpty() && (adapters.size() == 1)) {
      setPrioritizedAdapters(List.copyOf(adapters.keySet()));
    }

  }

  /**
   * Convention over configuration: derives everything which the platform already knows from
   * the classpath. So an application only configures what DIFFERS from the convention. It runs
   * BEFORE the checks, and a derived entry is checked exactly like a hand-written one.
   * <ol>
   * <li><b>Adapter sections:</b> if nothing is configured and exactly ONE adapter type is in
   * the classpath, that type becomes the one adapter (id = type). Several types are never
   * guessed. The check asks for <code>vanillabp.prioritized-adapters</code>. An id listed in
   * <code>prioritized-adapters</code> without a section is derived, if it IS an adapter type.
   * A CUSTOM id can never be derived, because it says nothing about its type. The developer
   * has to write <code>type</code>.</li>
   * <li><b>Workflow-module sections:</b> a module found in the classpath without a section
   * gets an empty one. A module needs no configuration any more.</li>
   * <li><b>Resources locations:</b> the location from which BPMN is read follows the
   * convention described at {@link #getAdapterResourcesLocationsFor(String, String)}.</li>
   * </ol>
   *
   * @param facts What the platform knows about the application without any property (see
   *          {@link ClasspathFacts})
   */
  public void normalize(
      final ClasspathFacts facts) {

    deriveAdapterSections(facts);

    normalize();

    deriveWorkflowModuleSections(facts);
    deriveConventionalResourcesLocations(facts);

  }

  /**
   * Derives the adapter sections which say nothing beyond what the classpath already tells
   * (see {@link #normalize(ClasspathFacts)}).
   */
  private void deriveAdapterSections(
      final ClasspathFacts facts) {

    final var adapterTypes = facts.adapterTypes();

    if (adapters.isEmpty() && (adapterTypes.size() == 1)) {
      // the single adapter dependency IS the configuration
      final var adapterType = adapterTypes.getFirst();
      adapters = new LinkedHashMap<>(Map.of(adapterType, AdapterConfigProperties.ofType(adapterType)));
      return;
    }

    // ids named in 'prioritized-adapters' which ARE adapter types need no section
    final var derivableIds = getPrioritizedAdapters()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .filter(adapterTypes::contains)
        .distinct()
        .toList();
    if (derivableIds.isEmpty()) {
      return;
    }
    final var derived = new LinkedHashMap<>(adapters);
    derivableIds.forEach(adapterId -> derived.put(adapterId, AdapterConfigProperties.ofType(adapterId)));
    adapters = derived;

  }

  /**
   * Derives an empty section for every workflow module found in the classpath which has none
   * (see {@link #normalize(ClasspathFacts)}).
   */
  private void deriveWorkflowModuleSections(
      final ClasspathFacts facts) {

    final var missingSections = facts
        .workflowModuleIds()
        .stream()
        .filter(workflowModuleId -> !workflowModules.containsKey(workflowModuleId))
        .toList();
    if (missingSections.isEmpty()) {
      return;
    }
    final var derived = new LinkedHashMap<>(workflowModules);
    missingSections.forEach(
        workflowModuleId -> derived.put(workflowModuleId, new WorkflowModuleAdapterProperties()));
    workflowModules = derived;

  }

  /**
   * Derives the conventional resources location of every workflow module found in the
   * classpath (see {@link #getAdapterResourcesLocationsFor(String, String)} for the
   * convention itself).
   */
  private void deriveConventionalResourcesLocations(
      final ClasspathFacts facts) {

    final var modules = facts.workflowModules();
    final var singleModuleOfMainArtifact = (modules.size() == 1) && modules
        .getFirst()
        .fromMainArtifact();

    final var locations = new LinkedHashMap<String, List<String>>();
    modules.forEach(
        module -> locations.put(
            module.id(),
            singleModuleOfMainArtifact
                // the application IS the workflow module. But a module tested inside its own
                // Maven module is the main artifact as well, and its files are where the
                // packaged application needs them: below the module ID. Both are searched, the
                // module's own location first. The module's configuration files are found in
                // both places for the same reason
                ? List.of(
                    "classpath*:%s/processes".formatted(module.id()),
                    "classpath*:processes")
                : List.of("classpath*:%s/processes".formatted(module.id()))));
    conventionalResourcesLocations = locations;

  }

  /**
   * How long the records of processed task deliveries are kept:
   * <code>vanillabp.delivery.retention</code> where it is set, and otherwise
   * <code>vanillabp.outbox.retention</code>. The number lived there before the two were told
   * apart.
   *
   * @return The period, never <code>null</code>
   */
  public java.time.Duration resolvedDeliveryRetention() {

    return DeliveryProperties.resolveRetention(delivery, resolvedOutboxRetention());

  }

  /**
   * How long the row about a started workflow is kept, counted from the start. That is what
   * <code>vanillabp.delivery.workflow-start-retention</code> says, or
   * {@value DeliveryProperties#DEFAULT_WORKFLOW_START_RETENTION_ISO} where nobody says
   * anything.
   * <p>
   * It is a period of its own and not the retention of a delivery. Changes to a workflow
   * aggregate keep arriving after its workflow ended, and each of them may want to name the
   * workflow.
   *
   * @return The period a workflow-start row is kept
   */
  public java.time.Duration resolvedWorkflowStartRetention() {

    return DeliveryProperties.resolveWorkflowStartRetention(delivery);

  }

  /**
   * Whether the row about a started workflow is kept past its period while the workflow
   * aggregate which it names still exists
   * (<code>vanillabp.delivery.keep-workflow-start-while-aggregate-exists</code>, off unless
   * the application asks for it).
   *
   * @return Whether the cleanup asks the aggregate before it deletes such a row
   */
  public boolean keepsWorkflowStartWhileAggregateExists() {

    return DeliveryProperties.resolveKeepWorkflowStartWhileAggregateExists(delivery);

  }

  private java.time.Duration resolvedOutboxRetention() {

    return outbox == null
        ? PhaseTwoOutboxProperties.DEFAULT_RETENTION
        : outbox.getRetention();

  }

  /**
   * Says which of the two retentions apply, where the application moved exactly one of them
   * away from the default. Both used to be one property, and they mean different things now.
   * An installation which lowered the old number to keep its outbox table small has to learn
   * that the half which matters for correctness followed along.
   * <p>
   * The trigger is "differs from the default", not "was written down". A bound property cannot
   * tell the two apart, and the interesting case is exactly the one where a number was moved.
   * An application which sets both, or which sets one of them to the default it already had,
   * learns nothing and is told nothing.
   */
  private void reportRetentionSplit() {

    final var deliveryRetention = delivery == null
        ? null
        : delivery.getRetention();
    final var outboxRetention = resolvedOutboxRetention();
    final var outboxMoved = !PhaseTwoOutboxProperties.DEFAULT_RETENTION.equals(outboxRetention);
    if ((deliveryRetention != null) == outboxMoved) {
      return;
    }
    if (deliveryRetention == null) {
      startupFindings()
          .notice(
              StartupTopic.CONFIGURATION,
              PhaseTwoOutboxProperties.RETENTION_PROPERTY,
              RETENTION_FOLLOWS_THE_OUTBOX
                  .formatted(
                      PhaseTwoOutboxProperties.RETENTION_PROPERTY,
                      outboxRetention,
                      DeliveryProperties.RETENTION_PROPERTY,
                      outboxRetention,
                      DeliveryProperties.RETENTION_PROPERTY));
      return;
    }
    startupFindings()
        .notice(
            StartupTopic.CONFIGURATION,
            DeliveryProperties.RETENTION_PROPERTY,
            RETENTION_STANDS_ON_ITS_OWN
                .formatted(
                    DeliveryProperties.RETENTION_PROPERTY,
                    deliveryRetention,
                    PhaseTwoOutboxProperties.RETENTION_PROPERTY,
                    outboxRetention));

  }

  /**
   * Says what still keeps the database awake, for an application which moved
   * <code>vanillabp.outbox.poll-interval</code> away from its default, and so asked the outbox
   * to sleep. Promising silence would be the easy message, and the wrong one. A developer who
   * switches this on watches the database next. Every poller which they do not hear about
   * from here they have to find themselves.
   * <p>
   * The trigger is "differs from the default", not "was written down". A bound property cannot
   * tell those apart, and the case worth a message is the one where somebody moved the
   * number.
   */
  private void reportWhatStaysAwake() {

    final var pollInterval = outbox == null
        ? PhaseTwoOutboxProperties.DEFAULT_POLL_INTERVAL
        : outbox.getPollInterval();
    if (PhaseTwoOutboxProperties.DEFAULT_POLL_INTERVAL.equals(pollInterval)) {
      return;
    }
    final var stillAwake = new StringBuilder(
        WHAT_STAYS_AWAKE_ALWAYS.formatted(PREFIX, MetricsProperties.GAUGE_CACHE_PROPERTY));
    adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> CAMUNDA_7_ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .forEach(adapterId -> stillAwake.append(WHAT_STAYS_AWAKE_ON_CAMUNDA_7.formatted(adapterId)));
    startupFindings()
        .notice(
            io.vanillabp.integration.spi.startup.StartupTopic.CONFIGURATION,
            "%s.outbox.poll-interval".formatted(PREFIX),
            """
                '%s.outbox.poll-interval' is %s instead of the default %s, so an outbox sleeps until \
                its next entry is due and an application with nothing to do sends it no statement at \
                all. What is still awake:%s"""
                .formatted(
                    PREFIX,
                    pollInterval,
                    PhaseTwoOutboxProperties.DEFAULT_POLL_INTERVAL,
                    stillAwake));

  }

  /**
   * The type of the Camunda 7 adapter. It is named here, because the engine behind that
   * adapter brings timers of its own, which VanillaBP neither starts nor stops. A message
   * about a quiet database which leaves them out is worse than no message.
   */
  private static final String CAMUNDA_7_ADAPTER_TYPE = "camunda7";

  /**
   * The pollers which are there, whatever BPMS an application runs against.
   */
  private static final String WHAT_STAYS_AWAKE_ALWAYS = """

      - the retention cleanup of the task-delivery records looks once per hour, and only \
      after a delivery was recorded, so an application which records nothing does not wake it
      - the gauge of the entries waiting in an outbox ('%s.outbox.pending') counts them while \
      your monitoring scrapes, at most once per '%s', so a dashboard somebody left open keeps \
      that one statement going
      - what an adapter does to find work for your handlers is that adapter's own business, \
      and its documentation is where the remaining traffic is explained""";

  /**
   * What an application with a Camunda 7 adapter is told in addition, with the numbers which
   * the engine uses by default.
   */
  private static final String WHAT_STAYS_AWAKE_ON_CAMUNDA_7 = """

      - the Camunda 7 engine of adapter '%s' acquires jobs every 5 to 60 seconds and writes \
      its own metrics every 900 seconds; both are the engine's timers, and that adapter's \
      documentation says what to set to stop them""";

  /**
   * What an installation is told which moved the outbox retention and nothing else. That is
   * the upgrade case, because this number used to govern both windows.
   */
  private static final String RETENTION_FOLLOWS_THE_OUTBOX = """
      '%s' is set to %s while '%s' is not, so the records of \
      processed task deliveries are kept for %s as well. Those two numbers used to be one and are no \
      longer the same kind of setting: the outbox one decides how long a dispatched entry stays \
      readable during support, the delivery one decides whether a late redelivery runs your \
      @WorkflowTask method a second time. Set '%s' explicitly where the second \
      one has to outlive the first.""";

  /**
   * What an installation is told which set only the new property, so that nobody assumes the
   * outbox followed the other way round.
   */
  private static final String RETENTION_STANDS_ON_ITS_OWN = """
      '%s' is set to %s, so the records of processed task deliveries are kept for \
      that long, while dispatched outbox entries keep '%s' (%s).""";

  /**
   * Links child properties back to their parents (for example the workflow module ID into
   * the properties object of the module). {@link #validateProperties(List, List)} calls it.
   * It has to be called explicitly if properties objects are built without running the checks
   * (for example in tests).
   * <p>
   * A binder keys a section by its map key, and writes nothing else into it. So a module
   * section does not know its own id. And a workflow section knows neither its BPMN process id
   * nor its module. Everything which builds a message about a workflow needs both. That is why
   * this runs before the first check.
   */
  public void validateAndLink() {

    workflowModules.forEach((
        workflowModuleId,
        moduleProperties) -> {
      moduleProperties.workflowModuleId = workflowModuleId;
      moduleProperties
          .getWorkflows()
          .forEach((
              bpmnProcessId,
              workflowProperties) -> {
            workflowProperties.bpmnProcessId = bpmnProcessId;
            workflowProperties.workflowModule = moduleProperties;
          });
    });

  }

  /**
   * A workflow module id must not contain the separator which VanillaBP uses to scope
   * identifiers ({@link io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport#SEPARATOR}).
   * Otherwise a prefixed identifier could no longer be read back reliably. It is only checked
   * when prefixing is configured ANYWHERE. So existing applications which do not use it are
   * not affected.
   *
   * @throws IllegalStateException Naming the offending module ids and the separator
   */
  public void validateWorkflowModuleIdsAgainstPrefixing() {

    if (!isPrefixingConfiguredAnywhere()) {
      return;
    }
    final var separator = io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.SEPARATOR;
    final var offending = workflowModules
        .keySet()
        .stream()
        .filter(workflowModuleId -> workflowModuleId.contains(separator))
        .sorted()
        .collect(Collectors.joining("', '", "'", "'"));
    if (offending.equals("''")) {
      return;
    }
    throw new IllegalStateException(
        ("The workflow module id(s) %s contain '%s'! VanillaBP uses it to separate a prefix from "
            + "the identifier it scopes (name-clash-avoidance 'use-prefix' is configured), so a "
            + "prefixed identifier could no longer be read back. Rename the workflow module(s) or "
            + "choose another name-clash-avoidance mode.")
            .formatted(offending, separator));

  }

  /**
   * Whether {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}
   * is configured at any level for any adapter.
   */
  private boolean isPrefixingConfiguredAnywhere() {

    final var usePrefix = io.vanillabp.integration.adapter.spi.NameClashAvoidance.USE_PREFIX;
    if (adapters
        .values()
        .stream()
        .anyMatch(adapter -> adapter.getNameClashAvoidance() == usePrefix)) {
      return true;
    }
    return workflowModules
        .values()
        .stream()
        .anyMatch(module -> anyUsesPrefix(module.getAdapters()) || module
            .getWorkflows()
            .values()
            .stream()
            .anyMatch(workflow -> anyUsesPrefix(workflow.getAdapters())));

  }

  private static boolean anyUsesPrefix(
      final Map<String, ? extends AdapterProperties> adaptersOfLevel) {

    return (adaptersOfLevel != null) && adaptersOfLevel
        .values()
        .stream()
        .anyMatch(
            adapter -> adapter
                .getNameClashAvoidance() == io.vanillabp.integration.adapter.spi.NameClashAvoidance.USE_PREFIX);

  }

  /**
   * The adapters which serve a whole workflow module, for everything which is about the
   * module and not about one of its processes, for example a deployment.
   *
   * @param workflowModuleId The workflow module, or <code>null</code> to ask what the
   *          application says in general
   * @return The adapter ids in the order in which they are asked, empty where no level names
   *         one
   */
  public List<String> getPrioritizedAdaptersFor(
      final String workflowModuleId) {

    return getPrioritizedAdaptersFor(
        workflowModuleId,
        null);

  }

  /**
   * The adapters which serve one BPMN process, in the order in which they are asked. The first
   * one which says it holds the workflow runs the operation. A new workflow starts in the
   * first one of the list.
   * <p>
   * Three levels may name adapters: the workflow, its module, and the application. The most
   * specific level which names ANY wins as a whole. A list is not merged with the one above
   * it. An empty list is how a level says nothing. So a workflow which names one adapter has
   * exactly that one, whatever its module says.
   *
   * @param workflowModuleId The workflow module, or <code>null</code> to ask what the
   *          application says in general
   * @param bpmnProcessId The plain BPMN process id, or <code>null</code> to stop at the
   *          workflow module
   * @return The adapter ids in the order in which they are asked, empty where no level names
   *         one. The check of the configuration turns that into a message which names the keys
   *         to write, see {@link #validateProperties(ClasspathFacts, String)}
   */
  public List<String> getPrioritizedAdaptersFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    var prioritizedAdapters = getPrioritizedAdapters();
    if (workflowModuleId == null) {
      return prioritizedAdapters;
    }
    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule == null) {
      return prioritizedAdapters;
    }
    if (!workflowModule.getPrioritizedAdapters().isEmpty()) {
      prioritizedAdapters = workflowModule.getPrioritizedAdapters();
    }
    if (bpmnProcessId == null) {
      return prioritizedAdapters;
    }
    final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
    if (workflow == null) {
      return prioritizedAdapters;
    }
    if (!workflow.getPrioritizedAdapters().isEmpty()) {
      prioritizedAdapters = workflow.getPrioritizedAdapters();
    }
    return prioritizedAdapters;

  }

  /**
   * Resolves an adapter-scoped property, where the most specific of the four possible levels
   * wins:
   *
   * <pre>
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.adapters.&lt;id&gt;.&lt;key&gt;  (most specific)
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;id&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.&lt;key&gt;
   * vanillabp.adapters.&lt;id&gt;.&lt;key&gt;                                                              (least specific)
   * </pre>
   *
   * The levels are walked from the most to the least specific. The first value which the
   * extractor returns that is not null wins (the same rule as in
   * {@link #getPrioritizedAdaptersFor(String, String)}). Levels whose scope ID is
   * <code>null</code> or not configured are skipped. So the resolver can be used for lookups
   * on adapter, module, workflow and task level alike.
   * <p>
   * A scope which is "present but empty" counts exactly like a missing scope (the extractor
   * returns <code>null</code> for both). Each binder creates scopes differently, and that
   * must not change the resolution.
   *
   * @param <T> The type of the resolved value
   * @param workflowModuleId The workflow module ID or <code>null</code>
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskId The task ID (task definition) or <code>null</code>
   * @param adapterId The adapter ID
   * @param valueExtractor Takes the value from the adapter-scoped properties of a level. It
   *          has to return <code>null</code> if the value is not set at that level
   * @return The most specific value configured or <code>null</code>
   */
  public <T> T resolveForAdapter(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId,
      final Function<AdapterProperties, T> valueExtractor) {

    final var workflowModule = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (workflowModule != null) && (bpmnProcessId != null)
        ? workflowModule.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskId != null)
        ? workflow.getTasks().get(taskId)
        : null;

    if (task != null) {
      final var value = extractForAdapter(task.getAdapters(), adapterId, valueExtractor);
      if (value != null) {
        return value;
      }
    }
    if (workflow != null) {
      final var value = extractForAdapter(workflow.getAdapters(), adapterId, valueExtractor);
      if (value != null) {
        return value;
      }
    }
    if (workflowModule != null) {
      final var value = extractForAdapter(workflowModule.getAdapters(), adapterId, valueExtractor);
      if (value != null) {
        return value;
      }
    }
    return extractForAdapter(adapters, adapterId, valueExtractor);

  }

  /**
   * The settings of an extension as they apply to a workflow module. That is what
   * {@link #extensionProperties(String, String, String, String, String)} answers for the
   * module alone, without a workflow, a task or an adapter.
   *
   * @param workflowModuleId The workflow module, or <code>null</code> for the global
   *          settings alone
   * @param extension The extension's id
   * @return The settings, never <code>null</code>
   */
  public Map<String, String> extensionProperties(
      final String workflowModuleId,
      final String extension) {

    return extensionProperties(workflowModuleId, null, null, null, extension);

  }

  /**
   * The settings of an extension as they apply to one task of one workflow of one workflow
   * module, read without naming an adapter.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param extension The extension's id
   * @return The settings, never <code>null</code>
   */
  public Map<String, String> extensionProperties(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String extension) {

    return extensionProperties(workflowModuleId, bpmnProcessId, taskId, null, extension);

  }

  /**
   * The settings of an extension as they apply to one task of one workflow of one workflow
   * module on one adapter: the eight positions which {@link SettingsResolution} walks,
   * merged KEY BY KEY. So a workflow may change one value without repeating the rest. That is
   * the same rule and the same levels as for an adapter setting (see decision 7 in the
   * repository's DECISIONS.md and {@link #resolveForAdapter}).
   *
   * <pre>
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;  (most specific)
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.extensions.&lt;extension&gt;.&lt;key&gt;                                                                                 (least specific)
   * </pre>
   *
   * A level whose id is <code>null</code>, or which is not configured, is skipped. So the same
   * method answers the global, the module, the workflow and the task question, with an adapter
   * and without one. What the keys below an extension MEAN is the extension's own business.
   * The core owns the location and this resolution.
   *
   * @param workflowModuleId The workflow module, or <code>null</code> for the global
   *          settings alone
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param adapterId The adapter, or <code>null</code> to read the general sections alone
   * @param extension The extension's id
   * @return The settings, never <code>null</code>
   */
  public Map<String, String> extensionProperties(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId,
      final String extension) {

    return ExtensionProperties
        .merge(
            SettingsResolution
                .positions(
                    extensionSettings(extension),
                    workflowModuleId,
                    bpmnProcessId,
                    taskId,
                    adapterId));

  }

  /**
   * One value of an extension's settings, resolved the same way as
   * {@link #extensionProperties(String, String)} resolves all of them.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param extension The extension's id
   * @param key The key below the extension's section, for example
   *          <code>rest.base-url</code>
   * @return The value, or <code>null</code> where nothing is configured there
   */
  public String extensionProperty(
      final String workflowModuleId,
      final String extension,
      final String key) {

    return resolveForExtension(workflowModuleId, null, null, null, extension, key);

  }

  /**
   * One value of an extension's settings, resolved over the positions without an adapter.
   * This is the counterpart of {@link #resolveForAdapter}, for an extension which asks about
   * no adapter in particular.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param extension The extension's id
   * @param key The key below the extension's section, for example
   *          <code>rest.base-url</code>
   * @return The most specific value configured, or <code>null</code>
   */
  public String resolveForExtension(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String extension,
      final String key) {

    return resolveForExtension(workflowModuleId, bpmnProcessId, taskId, null, extension, key);

  }

  /**
   * One value of an extension's settings, resolved over the eight positions which
   * {@link #extensionProperties(String, String, String, String, String)} describes. This is
   * the counterpart of {@link #resolveForAdapter} for an extension.
   * <p>
   * The extension id is a parameter, and not bound to a view of these properties. This object
   * is one bean of the platform, but an extension is not a bean of the platform at all. It
   * asks with its own id, the same way as an adapter asks with its adapter id.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param adapterId The adapter, or <code>null</code> to read the general sections alone
   * @param extension The extension's id
   * @param key The key below the extension's section, for example
   *          <code>rest.base-url</code>
   * @return The most specific value configured, or <code>null</code>
   */
  public String resolveForExtension(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId,
      final String extension,
      final String key) {

    return SettingsResolution
        .resolve(
            extensionSettings(extension),
            workflowModuleId,
            bpmnProcessId,
            taskId,
            adapterId,
            settings -> settings.get(key));

  }

  /**
   * The configuration tree of an extension as VanillaBP binds it: the
   * <code>extensions</code> maps of the four levels, and the ones below the adapter sections
   * of those levels. An extension with a section of its own hands in its own tree instead.
   * The walk is the same either way (see decision 53 in the repository's DECISIONS.md).
   *
   * @param extension The extension's id
   * @return The most general level of that extension's settings
   */
  private SettingsLevel<Map<String, String>> extensionSettings(
      final String extension) {

    return new ExtensionSettingsLevel(
        extension, extensions, adapters, workflowModuleId -> {
          final var workflowModule = workflowModules.get(workflowModuleId);
          return workflowModule == null
              ? null
              : settingsOfWorkflowModule(workflowModule, extension);
        });

  }

  private static SettingsLevel<Map<String, String>> settingsOfWorkflowModule(
      final WorkflowModuleAdapterProperties workflowModule,
      final String extension) {

    return new ExtensionSettingsLevel(
        extension, workflowModule.getExtensions(), workflowModule.getAdapters(), bpmnProcessId -> {
          final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
          return workflow == null
              ? null
              : settingsOfWorkflow(workflow, extension);
        });

  }

  private static SettingsLevel<Map<String, String>> settingsOfWorkflow(
      final WorkflowAdapterProperties workflow,
      final String extension) {

    return new ExtensionSettingsLevel(
        extension, workflow.getExtensions(), workflow.getAdapters(), taskId -> {
          final var task = workflow.getTasks().get(taskId);
          return task == null
              ? null
              : settingsOfTask(task, extension);
        });

  }

  private static SettingsLevel<Map<String, String>> settingsOfTask(
      final TaskAdapterProperties task,
      final String extension) {

    return new ExtensionSettingsLevel(
        extension, task.getExtensions(), task.getAdapters(), nothingIsBelowATask -> null);

  }

  /**
   * One level of the extension settings which the core itself binds: the section of the
   * level, the section of one of its adapters, and the way to the level below.
   *
   * @param extension The extension asked about
   * @param extensionsOfLevel What this level says about all extensions
   * @param adaptersOfLevel The adapter sections of this level
   * @param levelsBelow Answers the next more specific level for an id
   */
  private record ExtensionSettingsLevel(
                                        String extension,
                                        Map<String, Map<String, String>> extensionsOfLevel,
                                        Map<String, ? extends AdapterProperties> adaptersOfLevel,
                                        Function<String, SettingsLevel<Map<String, String>>> levelsBelow) implements SettingsLevel<Map<String, String>> {

    @Override
    public Map<String, String> settings() {

      return extensionsOfLevel.get(extension);

    }

    @Override
    public Map<String, String> settingsOfAdapter(
        final String adapterId) {

      final var adapter = adaptersOfLevel.get(adapterId);
      return adapter == null
          ? null
          : adapter.getExtensions().get(extension);

    }

    @Override
    public SettingsLevel<Map<String, String>> levelBelow(
        final String id) {

      return levelsBelow.apply(id);

    }

  }

  private static <T> T extractForAdapter(
      final Map<String, ? extends AdapterProperties> adaptersOfLevel,
      final String adapterId,
      final Function<AdapterProperties, T> valueExtractor) {

    final var adapter = adaptersOfLevel.get(adapterId);
    return adapter != null
        ? valueExtractor.apply(adapter)
        : null;

  }

  /**
   * Whether the given adapter is the FIRST entry of the prioritized-adapters list at ANY
   * level (globally, of any workflow module, or of any workflow). In other words, whether new
   * workflows may be started through this adapter anywhere. This decides how strictly an
   * adapter's own startup check may treat configuration defects. An adapter which is nowhere
   * first may honor its <code>deployment-failure: warn</code> policy and keep the application
   * booting (migration case: the OLD BPMS is degraded on purpose). An adapter which is first
   * anywhere always fails the boot on a real defect. Otherwise new workflows could not be
   * started.
   *
   * @param adapterId The adapter ID
   * @return Whether the adapter is first priority at any level
   */
  public boolean isFirstPriorityAnywhere(
      final String adapterId) {

    if (isFirstOf(getPrioritizedAdapters(), adapterId)) {
      return true;
    }
    return workflowModules
        .values()
        .stream()
        .anyMatch(workflowModule -> isFirstOf(workflowModule.getPrioritizedAdapters(), adapterId) || workflowModule
            .getWorkflows()
            .values()
            .stream()
            .anyMatch(workflow -> isFirstOf(workflow.getPrioritizedAdapters(), adapterId)));

  }

  private static boolean isFirstOf(
      final List<String> prioritizedAdapters,
      final String adapterId) {

    return !prioritizedAdapters.isEmpty() && prioritizedAdapters.getFirst().equals(adapterId);

  }

  /**
   * Whether the given adapter is the FIRST entry of the prioritized-adapters list which
   * applies to the given workflow module, or to ANY workflow of that module. In other words,
   * whether new workflows of this module may be started through this adapter. The policy for
   * deployment failures uses this. A failed deployment of an adapter which is first priority
   * for at least one workflow always fails the boot, even if the adapter is not the module's
   * first-priority adapter. Otherwise starting that workflow would fail at runtime.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @return Whether the adapter is first priority for the module or any of its
   *         workflows
   */
  public boolean isFirstPriorityFor(
      final String workflowModuleId,
      final String adapterId) {

    if (isFirstOf(getPrioritizedAdaptersFor(workflowModuleId), adapterId)) {
      return true;
    }
    final var workflowModule = workflowModules.get(workflowModuleId);
    if (workflowModule == null) {
      return false;
    }
    return workflowModule
        .getWorkflows()
        .keySet()
        .stream()
        .anyMatch(bpmnProcessId -> isFirstOf(
            getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId),
            adapterId));

  }

  /**
   * The adapters which have to receive the deployment of the given workflow module. That is
   * the union of the module's effective prioritized-adapters list and every adapter named in
   * a workflow-level <code>prioritized-adapters</code> override of that module. The BPMS
   * election works per process, but the deployment works per file. So an adapter which is
   * prioritized for one single workflow still needs the module's resources deployed.
   * Otherwise starting that workflow fails at runtime. The order is: the list of the module
   * first, then the adapters which are only named at the workflow level.
   *
   * @param workflowModuleId The workflow module ID
   * @return The adapter IDs to deploy the module's resources to
   */
  public List<String> getDeploymentAdaptersFor(
      final String workflowModuleId) {

    final var deploymentAdapters = new LinkedList<>(getPrioritizedAdaptersFor(workflowModuleId));
    final var workflowModule = workflowModules.get(workflowModuleId);
    if (workflowModule != null) {
      workflowModule
          .getWorkflows()
          .values()
          .stream()
          .flatMap(workflow -> workflow.getPrioritizedAdapters().stream())
          .distinct()
          .filter(adapterId -> !deploymentAdapters.contains(adapterId))
          .forEach(deploymentAdapters::add);
    }
    return deploymentAdapters;

  }

  /**
   * The configured adapter ids, sorted, for a message which lists them. The sections come from
   * a binder and keep no order. So the same configuration would otherwise name them
   * differently from one boot to the next.
   *
   * @param separator What goes between two ids
   * @return The ids, joined
   */
  private String configuredAdapterIdsJoinedBy(
      final String separator) {

    return adapters
        .keySet()
        .stream()
        .sorted()
        .collect(Collectors.joining(separator));

  }

  private Stream<String> unknownAdapterKeys(
      final Map<String, ? extends AdapterProperties> adaptersOfLevel,
      final String keyPrefixOfLevel) {

    return adaptersOfLevel
        .keySet()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .map(adapterId -> "%s.adapters.%s".formatted(keyPrefixOfLevel, adapterId));

  }

  /**
   * The policy for how to treat a failed deployment of BPMS resources for the given
   * adapter.
   *
   * @param adapterId The adapter ID
   * @return The policy, defaulting to {@link DeploymentFailurePolicy#FAIL}
   */
  public DeploymentFailurePolicy getDeploymentFailureFor(
      final String adapterId) {

    final var adapter = adapters.get(adapterId);
    return (adapter != null) && (adapter.getDeploymentFailure() != null)
        ? adapter.getDeploymentFailure()
        : DeploymentFailurePolicy.FAIL;

  }

  /**
   * Whether the given workflow module accepts writes to a store of workflow aggregates which
   * the transaction of VanillaBP does not cover
   * (<code>vanillabp.transactions.unguarded-aggregate-writes</code>, which can be overridden
   * as
   * <code>vanillabp.workflow-modules.&lt;id&gt;.transactions.unguarded-aggregate-writes</code>).
   * The module's setting wins where it is defined. Otherwise the global one applies, and the
   * default is to reject.
   *
   * @param workflowModuleId The workflow module ID
   * @return Whether unguarded aggregate writes are accepted for this module
   */
  public boolean acceptsUnguardedAggregateWrites(
      final String workflowModuleId) {

    final var module = workflowModules.get(workflowModuleId);
    final var moduleSetting = (module != null) && (module.getTransactions() != null)
        ? module
            .getTransactions()
            .getUnguardedAggregateWrites()
        : null;
    final var effective = moduleSetting != null
        ? moduleSetting
        : transactions != null
            ? transactions.getUnguardedAggregateWrites()
            : null;
    return effective == TransactionsProperties.UnguardedAggregateWrites.ACCEPTED;

  }

  /**
   * The property which names the decision of {@link #acceptsUnguardedAggregateWrites(String)},
   * for the guiding messages which have to tell how to accept what they refuse.
   *
   * @param workflowModuleId The workflow module ID
   * @return The property key of the module-scoped override
   */
  public static String unguardedAggregateWritesProperty(
      final String workflowModuleId) {

    return "%s.workflow-modules.%s.transactions.unguarded-aggregate-writes"
        .formatted(PREFIX, workflowModuleId);

  }

  /**
   * Whether that workflow may hand its entire workflow aggregate to the BPMS
   * (<code>vanillabp.workflow-modules.&lt;id&gt;.workflows.&lt;bpmn-process-id&gt;.allow-full-sync-with-bpms</code>).
   * <p>
   * It is read at the workflow and nowhere else. Every other property of a workflow falls back
   * to the levels above it, but this one does not. A permission inherited from a workflow
   * module would cover the workflow which somebody adds next week, and nobody looked at that
   * workflow. See decision 66 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return Whether this workflow allows sharing its aggregate as a whole
   */
  public boolean allowsFullSyncWithBpms(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    if (module == null) {
      return false;
    }
    final var workflow = bpmnProcessId != null
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    return (workflow != null) && Boolean.TRUE.equals(workflow.getAllowFullSyncWithBpms());

  }

  /**
   * The values of its workflow aggregate which that workflow declares for the BPMS
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.declared-aggregate-values</code>).
   * <p>
   * It is read at the workflow, because the values belong to the aggregate of one
   * workflow.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The entries as written, never <code>null</code>
   */
  public List<String> declaredAggregateValues(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    if (module == null) {
      return List.of();
    }
    final var workflow = bpmnProcessId != null
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    if ((workflow == null) || (workflow.getDeclaredAggregateValues() == null)) {
      return List.of();
    }
    return List.copyOf(workflow.getDeclaredAggregateValues());

  }

  /**
   * The property which declares what {@link #declaredAggregateValues(String, String)} reads,
   * for the message which has to give the developer a line to copy.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The property key at the workflow
   */
  public static String declaredAggregateValuesProperty(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "%s.workflow-modules.%s.workflows.%s.declared-aggregate-values"
        .formatted(PREFIX, workflowModuleId, bpmnProcessId);

  }

  /**
   * The <code>&#64;TaskParam</code> names which the application declared for that task. The
   * most specific level wins: the task, then the workflow, then the workflow module, then the
   * application. A level which says nothing is skipped. So a declaration at the application
   * covers every task which does not name its own.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskId The task ID (task definition) or <code>null</code>
   * @return The declared parameter names, never <code>null</code>
   */
  public List<String> declaredTaskParams(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskId != null)
        ? workflow
            .getTasks()
            .get(taskId)
        : null;
    if ((task != null) && (task.getDeclaredTaskParams() != null)) {
      return List.copyOf(task.getDeclaredTaskParams());
    }
    if ((workflow != null) && (workflow.getDeclaredTaskParams() != null)) {
      return List.copyOf(workflow.getDeclaredTaskParams());
    }
    if ((module != null) && (module.getDeclaredTaskParams() != null)) {
      return List.copyOf(module.getDeclaredTaskParams());
    }
    return declaredTaskParams != null
        ? List.copyOf(declaredTaskParams)
        : List.of();

  }

  /**
   * The property which declares what {@link #declaredTaskParams(String, String, String)}
   * reads, at the level where it belongs: the single task.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskId The task ID (task definition)
   * @return The property key at the task
   */
  public static String declaredTaskParamsProperty(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId) {

    return "%s.workflow-modules.%s.workflows.%s.tasks.%s.declared-task-params"
        .formatted(PREFIX, workflowModuleId, bpmnProcessId, taskId);

  }

  /**
   * Whether the application says that something other than itself serves a task
   * (<code>implemented-externally</code>), over the eight positions at which every task
   * setting may be written:
   *
   * <pre>
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.adapters.&lt;id&gt;.implemented-externally  (most specific)
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.implemented-externally
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;id&gt;.implemented-externally
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.implemented-externally
   * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.implemented-externally
   * vanillabp.workflow-modules.&lt;module&gt;.implemented-externally
   * vanillabp.adapters.&lt;id&gt;.implemented-externally
   * vanillabp.implemented-externally                                                                         (least specific)
   * </pre>
   *
   * The most specific position which writes the key wins. What one adapter is told wins over
   * what the same level says in general. At the task level a task has more than one name:
   * its element id and its task definition. The names are tried in the given order. The
   * caller passes the element id first. So a line written for the element id wins over a
   * line written for the task definition. Both task names are tried before the workflow,
   * because a line about the task is more specific than any line about the workflow.
   * <p>
   * {@link #implementedExternallyAtTheTask(String, String, List, String)} says whether the
   * value came from the task itself. A line above the task only covers the tasks without a
   * method, and the caller decides that.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskNames The names of the task at the task level, most important first.
   *          <code>null</code> entries are skipped
   * @param adapterId The adapter ID, or <code>null</code> to read no adapter position
   * @return The value of the most specific position which writes one, or <code>null</code>
   *         where none does
   */
  public Boolean implementedExternally(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> taskNames,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    final var atTheTask = implementedExternallyAtTheTask(workflowModuleId, bpmnProcessId, taskNames, adapterId);
    if (atTheTask != null) {
      return atTheTask;
    }
    if (workflow != null) {
      final var value = firstWritten(
          implementedExternallyOf(workflow.getAdapters(), adapterId),
          workflow.getImplementedExternally());
      if (value != null) {
        return value;
      }
    }
    if (module != null) {
      final var value = firstWritten(
          implementedExternallyOf(module.getAdapters(), adapterId),
          module.getImplementedExternally());
      if (value != null) {
        return value;
      }
    }
    return firstWritten(
        implementedExternallyOf(adapters, adapterId),
        implementedExternally);

  }

  /**
   * What the task itself says about <code>implemented-externally</code>, at the two positions
   * of the task level and nowhere else: for one adapter, and in general. The names are tried
   * in the given order, the element id first.
   * <p>
   * A line at the task says something about THIS task. A line above it covers every task
   * below it which has no method. Because of that difference, a method next to a line ends a
   * start: only a line at the task contradicts a method (see decision 119 in the
   * repository's DECISIONS.md).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskNames The names of the task, most important first. <code>null</code> entries
   *          are skipped
   * @param adapterId The adapter ID, or <code>null</code> to read no adapter position
   * @return The value written at the task, or <code>null</code> where the task says nothing
   */
  public Boolean implementedExternallyAtTheTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> taskNames,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    if (workflow == null) {
      return null;
    }
    for (final var taskName : taskNames) {
      final var task = taskName != null
          ? workflow
              .getTasks()
              .get(taskName)
          : null;
      if (task == null) {
        continue;
      }
      final var value = firstWritten(
          implementedExternallyOf(task.getAdapters(), adapterId),
          task.getImplementedExternally());
      if (value != null) {
        return value;
      }
    }
    return null;

  }

  /**
   * What the workflow itself says about <code>implemented-externally</code>, at the two
   * positions of the workflow level and nowhere else: for one adapter, and in general.
   * <p>
   * For a process which no <code>&#64;WorkflowService</code> claims, this is the line which
   * says that the process belongs to somebody else. It was only deployed because it shares a
   * file with a claimed one. A line at the workflow module or at the application does not say
   * that. It is written for many processes at once, and cannot mean this one. See decision
   * 120 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param adapterId The adapter ID, or <code>null</code> to read no adapter position
   * @return The value written at the workflow, or <code>null</code> where the workflow says
   *         nothing
   */
  public Boolean implementedExternallyAtTheWorkflow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    if (workflow == null) {
      return null;
    }
    return firstWritten(
        implementedExternallyOf(workflow.getAdapters(), adapterId),
        workflow.getImplementedExternally());

  }

  /**
   * What one adapter section of a level says about <code>implemented-externally</code>.
   *
   * @param adaptersOfLevel The adapter sections of the level
   * @param adapterId The adapter ID, or <code>null</code> for none
   * @return The value, or <code>null</code> where the section says nothing
   */
  private static Boolean implementedExternallyOf(
      final Map<String, ? extends AdapterProperties> adaptersOfLevel,
      final String adapterId) {

    if ((adapterId == null) || (adaptersOfLevel == null)) {
      return null;
    }
    return extractForAdapter(adaptersOfLevel, adapterId, AdapterProperties::getImplementedExternally);

  }

  /**
   * The first of two values which somebody wrote.
   *
   * @param moreSpecific The value of the more specific position
   * @param lessSpecific The value of the less specific position
   * @return The more specific value where it is written, otherwise the other one
   */
  private static Boolean firstWritten(
      final Boolean moreSpecific,
      final Boolean lessSpecific) {

    return moreSpecific != null
        ? moreSpecific
        : lessSpecific;

  }

  /**
   * The task names of one workflow for which a task-level line of
   * <code>implemented-externally</code> is written, at the task itself or for one of its
   * adapters. The startup checks them against the names which the deployed models know. A
   * line for a task which no model has any more says nothing, and should not stay.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The task names, sorted, never <code>null</code>
   */
  public List<String> tasksMarkedImplementedExternally(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    if (workflow == null) {
      return List.of();
    }
    return workflow
        .getTasks()
        .entrySet()
        .stream()
        .filter(task -> (task.getValue() != null) && ((task.getValue().getImplementedExternally() != null) || task
            .getValue()
            .getAdapters()
            .values()
            .stream()
            .anyMatch(adapter -> (adapter != null) && (adapter.getImplementedExternally() != null))))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();

  }

  /**
   * The property which allows what {@link #allowsFullSyncWithBpms(String, String)} decides,
   * for the message which has to give the developer a line to copy.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The property key at the workflow
   */
  public static String allowFullSyncWithBpmsProperty(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "%s.workflow-modules.%s.workflows.%s.allow-full-sync-with-bpms"
        .formatted(PREFIX, workflowModuleId, bpmnProcessId);

  }

  /**
   * Whether the expressions of that workflow's BPMN model are meant as they are
   * (<code>accept-expressions-in-the-model</code>). Then the startup stops naming them.
   * <p>
   * It is read at three levels, and the most specific wins: the workflow, the workflow
   * module, the application. Unlike the permission above, this is no permission at all.
   * Nothing leaves the application because of it, and nothing behaves differently. It says
   * that somebody looked at the expressions of a model and meant them. A team decides that
   * for a whole application as easily as for one process.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return Whether the expressions of that process are accepted as they are
   */
  public boolean acceptsExpressionsInTheModel(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    if (module != null) {
      final var workflow = bpmnProcessId != null
          ? module
              .getWorkflows()
              .get(bpmnProcessId)
          : null;
      if ((workflow != null) && (workflow.getAcceptExpressionsInTheModel() != null)) {
        return workflow.getAcceptExpressionsInTheModel();
      }
      if (module.getAcceptExpressionsInTheModel() != null) {
        return module.getAcceptExpressionsInTheModel();
      }
    }
    return Boolean.TRUE.equals(acceptExpressionsInTheModel);

  }

  /**
   * The property which accepts what {@link #acceptsExpressionsInTheModel(String, String)}
   * decides, for the message which has to give the developer a line to copy. It is the key at
   * the WORKFLOW, the most specific of the three. A line written there is about the model of
   * the message. The same line at the workflow module would also cover the process which
   * somebody adds next week.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The property key at the workflow
   */
  public static String acceptExpressionsInTheModelProperty(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "%s.workflow-modules.%s.workflows.%s.accept-expressions-in-the-model"
        .formatted(PREFIX, workflowModuleId, bpmnProcessId);

  }

  /**
   * Refuses the permission of {@link #allowsFullSyncWithBpms(String, String)} wherever it
   * stands somewhere else than at a workflow. Every level at which it can be written is bound.
   * So the line gets an answer and is not ignored, and the answer says where it belongs. It
   * reads like every other misplaced setting, see {@link MisplacedSettings}.
   *
   * @throws IllegalStateException Naming the place where the permission was written, and the
   *           key under which it belongs
   */
  private void refuseFullSyncPermissionsOutsideAWorkflow() {

    final var misplaced = new java.util.LinkedList<String>();
    if (allowFullSyncWithBpms != null) {
      misplaced.add("%s.allow-full-sync-with-bpms".formatted(PREFIX));
    }
    adapters
        .entrySet()
        .stream()
        .filter(adapter -> adapter.getValue().getAllowFullSyncWithBpms() != null)
        .forEach(adapter -> misplaced
            .add("%s.adapters.%s.allow-full-sync-with-bpms".formatted(PREFIX, adapter.getKey())));
    workflowModules.forEach((
        moduleId,
        module) -> {
      if (module.getAllowFullSyncWithBpms() != null) {
        misplaced.add("%s.workflow-modules.%s.allow-full-sync-with-bpms".formatted(PREFIX, moduleId));
      }
      module
          .getAdapters()
          .entrySet()
          .stream()
          .filter(adapter -> adapter.getValue().getAllowFullSyncWithBpms() != null)
          .forEach(adapter -> misplaced
              .add("%s.workflow-modules.%s.adapters.%s.allow-full-sync-with-bpms"
                  .formatted(PREFIX, moduleId, adapter.getKey())));
      module
          .getWorkflows()
          .forEach((
              processId,
              workflow) -> workflow
                  .getAdapters()
                  .entrySet()
                  .stream()
                  .filter(adapter -> adapter.getValue().getAllowFullSyncWithBpms() != null)
                  .forEach(adapter -> misplaced
                      .add("%s.workflow-modules.%s.workflows.%s.adapters.%s.allow-full-sync-with-bpms"
                          .formatted(PREFIX, moduleId, processId, adapter.getKey()))));
    });
    if (misplaced.isEmpty()) {
      return;
    }
    throw MisplacedSettings.refuse(
        "Sharing a whole workflow aggregate is allowed at the workflow and nowhere else",
        misplaced,
        "the workflow it is meant for",
        List.of(
            "%s.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.allow-full-sync-with-bpms: true"
                .formatted(PREFIX)),
        """
            An inherited permission would cover the next workflow somebody adds to the module \
            as well, and that is the workflow nobody looked at.""");

  }

  /**
   * Refuses a resources location written below the workflow module. The key is bound at all
   * four levels like every other adapter setting. But it is only read at the workflow module
   * and at the adapter. VanillaBP loads the BPMN files before it knows which process or which
   * task is in them (see decision 80 in the repository's DECISIONS.md). A line written at the
   * workflow or at a task would do nothing at all. So it gets an answer and is not ignored.
   * It reads like every other misplaced setting, see {@link MisplacedSettings}.
   *
   * @throws IllegalStateException Naming every place where a location was written, and the
   *           two keys at which it may be written
   */
  private void refuseResourcesLocationsBelowTheWorkflowModule() {

    final var misplaced = new LinkedList<String>();
    workflowModules.forEach((
        moduleId,
        module) -> module
            .getWorkflows()
            .forEach((
                processId,
                workflow) -> {
              workflow
                  .getAdapters()
                  .entrySet()
                  .stream()
                  .filter(adapter -> adapter.getValue().getResourcesLocation() != null)
                  .forEach(adapter -> misplaced
                      .add("%s.workflow-modules.%s.workflows.%s.adapters.%s.resources-location"
                          .formatted(PREFIX, moduleId, processId, adapter.getKey())));
              workflow
                  .getTasks()
                  .forEach((
                      taskId,
                      task) -> task
                          .getAdapters()
                          .entrySet()
                          .stream()
                          .filter(adapter -> adapter.getValue().getResourcesLocation() != null)
                          .forEach(adapter -> misplaced
                              .add(
                                  "%s.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.resources-location"
                                      .formatted(PREFIX, moduleId, processId, taskId, adapter.getKey()))));
            }));
    if (misplaced.isEmpty()) {
      return;
    }
    throw MisplacedSettings.refuse(
        "The location of an adapter's BPMN files is read at the workflow module and at the adapter",
        misplaced,
        "one of these two keys",
        List.of(
            "%s.workflow-modules.<workflow-module>.adapters.<adapter>.resources-location: classpath*:<location>"
                .formatted(PREFIX),
            "%s.adapters.<adapter>.resources-location: classpath*:<location>".formatted(PREFIX)),
        """
            VanillaBP loads the BPMN files before it knows which process or which task is in \
            them, so a location below the workflow module is never read.""");

  }

  /**
   * Refuses the settings about what the housekeeping of the delivery log deletes, where they
   * were written below the application. The delivery section binds at all four levels, and
   * everything in it except these three keys is read at the level where it stands. These
   * three are not. One sweep of the housekeeping removes the rows of every workflow module.
   * So there is one set of settings, and it is read from the application's own section only
   * (see {@link #resolvedDeliveryRetention()}, {@link #resolvedWorkflowStartRetention()} and
   * {@link #keepsWorkflowStartWhileAggregateExists()}). A line at a workflow module, a
   * workflow or a task would do nothing at all. So it gets an answer and is not ignored. It
   * reads like every other misplaced setting, see {@link MisplacedSettings}.
   *
   * @throws IllegalStateException Naming every place where such a setting was written, and
   *           the keys at which it may be written
   */
  private void refuseDeliveryRetentionsBelowTheApplication() {

    final var misplaced = new LinkedList<String>();
    workflowModules.forEach((
        moduleId,
        module) -> {
      globalOnlyDeliveryKeys(module.getDelivery())
          .forEach(key -> misplaced.add("%s.workflow-modules.%s.delivery.%s".formatted(PREFIX, moduleId, key)));
      module
          .getWorkflows()
          .forEach((
              processId,
              workflow) -> {
            globalOnlyDeliveryKeys(workflow.getDelivery())
                .forEach(key -> misplaced
                    .add("%s.workflow-modules.%s.workflows.%s.delivery.%s"
                        .formatted(PREFIX, moduleId, processId, key)));
            workflow
                .getTasks()
                .forEach((
                    taskId,
                    task) -> {
                  globalOnlyDeliveryKeys(task.getDelivery())
                      .forEach(key -> misplaced
                          .add("%s.workflow-modules.%s.workflows.%s.tasks.%s.delivery.%s"
                              .formatted(PREFIX, moduleId, processId, taskId, key)));
                });
          });
    });
    if (misplaced.isEmpty()) {
      return;
    }
    throw MisplacedSettings.refuse(
        "What the housekeeping of the delivery log deletes is read for the whole application",
        misplaced,
        "the section of the whole application",
        List
            .of(
                "%s: 7d".formatted(DeliveryProperties.RETENTION_PROPERTY),
                "%s: %s".formatted(
                    DeliveryProperties.WORKFLOW_START_RETENTION_PROPERTY,
                    DeliveryProperties.DEFAULT_WORKFLOW_START_RETENTION_ISO),
                "%s: false"
                    .formatted(DeliveryProperties.KEEP_WORKFLOW_START_WHILE_AGGREGATE_EXISTS_PROPERTY)),
        """
            One sweep of the housekeeping removes the rows of every workflow module, and it \
            asks for its settings before it knows whose rows it is about.""");

  }

  /**
   * The keys of the delivery section which are only read for the whole application, as far as
   * the given section writes one. All three decide what the housekeeping of the log DELETES.
   * The housekeeping runs once per store with one set of settings. So a value further down
   * would do nothing at all.
   *
   * @param delivery The delivery section of a workflow module, a workflow or a task, or
   *          <code>null</code>
   * @return The keys which were written there, empty where none was
   */
  private static List<String> globalOnlyDeliveryKeys(
      final DeliveryProperties delivery) {

    if (delivery == null) {
      return List.of();
    }
    final var written = new LinkedList<String>();
    if (delivery.getRetention() != null) {
      written.add("retention");
    }
    if (delivery.getWorkflowStartRetention() != null) {
      written.add("workflow-start-retention");
    }
    if (delivery.getKeepWorkflowStartWhileAggregateExists() != null) {
      written.add("keep-workflow-start-while-aggregate-exists");
    }
    return written;

  }

  /**
   * Refuses the release of delivery records written below the workflow module. The key is
   * read at the workflow module and, where the module says nothing, at the application (see
   * {@link #releasesDeliveryRecordsOnWorkflowEnd(String)}). A workflow or a task cannot be
   * asked, because the setting decides about a listener which an adapter attaches to a whole
   * deployed process. A line further down would do nothing at all. So it gets an answer and
   * is not ignored. It reads like every other misplaced setting, see
   * {@link MisplacedSettings}.
   *
   * @throws IllegalStateException Naming every place where the release was written, and the
   *           two keys at which it may be written
   */
  private void refuseDeliveryReleasesBelowTheWorkflowModule() {

    final var misplaced = new LinkedList<String>();
    workflowModules.forEach((
        moduleId,
        module) -> module
            .getWorkflows()
            .forEach((
                processId,
                workflow) -> {
              if ((workflow.getDelivery() != null) && (workflow.getDelivery().getReleaseOnWorkflowEnd() != null)) {
                misplaced
                    .add("%s.workflow-modules.%s.workflows.%s.delivery.release-on-workflow-end"
                        .formatted(PREFIX, moduleId, processId));
              }
              workflow
                  .getTasks()
                  .forEach((
                      taskId,
                      task) -> {
                    if ((task.getDelivery() != null) && (task.getDelivery().getReleaseOnWorkflowEnd() != null)) {
                      misplaced
                          .add("%s.workflow-modules.%s.workflows.%s.tasks.%s.delivery.release-on-workflow-end"
                              .formatted(PREFIX, moduleId, processId, taskId));
                    }
                  });
            }));
    if (misplaced.isEmpty()) {
      return;
    }
    throw MisplacedSettings.refuse(
        "Whether an ended workflow releases its delivery records is read at the workflow module "
            + "and for the whole application",
        misplaced,
        "the workflow module the workflows belong to, or to the whole application",
        List.of(
            "%s.workflow-modules.<workflow-module>.delivery.release-on-workflow-end: true".formatted(PREFIX),
            "%s.delivery.release-on-workflow-end: true".formatted(PREFIX)),
        """
            What this decides is a listener an adapter attaches to a whole deployed process, \
            and a process is deployed for its workflow module rather than for one workflow or \
            one task.""");

  }

  /**
   * An adapter setting which is about a whole BPMN process: the name under which it is
   * written, and how to tell that a level wrote it.
   *
   * @param key The last segment of the property key
   * @param writtenHere Whether the given level says anything about the setting
   */
  private record SettingOfAWholeProcess(
                                        String key,
                                        Predicate<AdapterProperties> writtenHere) {
  }

  /**
   * The adapter settings which are about a whole BPMN process. Everything which reads one of
   * them asks for a workflow and never for a task. So
   * {@link #refuseSettingsOfAWholeProcessWrittenAtATask()} refuses a line at a task.
   */
  private static final List<SettingOfAWholeProcess> SETTINGS_OF_A_WHOLE_PROCESS = List.of(
      new SettingOfAWholeProcess(
          "name-clash-avoidance", adapter -> adapter.getNameClashAvoidance() != null),
      new SettingOfAWholeProcess(
          "prefix-task-definitions-per-process", adapter -> adapter.getPrefixTaskDefinitionsPerProcess() != null),
      new SettingOfAWholeProcess(
          "outfaded-versions", adapter -> (adapter.getOutfadedVersions() != null) && !adapter.getOutfadedVersions()
              .isEmpty()),
      new SettingOfAWholeProcess(
          "outfaded-versions-in-use", adapter -> adapter.getOutfadedVersionsInUse() != null));

  /**
   * Refuses a setting of a whole BPMN process written at a single task. The keys bind at all
   * four levels like every other adapter setting, because one class holds what an adapter may
   * be told. But nobody reads the task level.
   * {@link io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService} and
   * {@code OutfadedProcessVersions} both ask
   * {@link #resolveForAdapter(String, String, String, String, Function)} without a task. A
   * line there would do nothing at all. So it gets an answer and is not ignored (see decision
   * 89 in the repository's DECISIONS.md). It reads like every other misplaced setting, see
   * {@link MisplacedSettings}.
   *
   * @throws IllegalStateException Naming every task at which a setting was written, and the
   *           three levels at which it may be written
   */
  private void refuseSettingsOfAWholeProcessWrittenAtATask() {

    final var misplaced = new LinkedList<String>();
    workflowModules.forEach((
        moduleId,
        module) -> module
            .getWorkflows()
            .forEach((
                processId,
                workflow) -> workflow
                    .getTasks()
                    .forEach((
                        taskId,
                        task) -> task
                            .getAdapters()
                            .forEach((
                                adapterId,
                                adapter) -> SETTINGS_OF_A_WHOLE_PROCESS
                                    .stream()
                                    .filter(setting -> setting.writtenHere().test(adapter))
                                    .forEach(setting -> misplaced
                                        .add("%s.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                                            .formatted(
                                                PREFIX,
                                                moduleId,
                                                processId,
                                                taskId,
                                                adapterId,
                                                setting.key())))))));
    if (misplaced.isEmpty()) {
      return;
    }
    throw MisplacedSettings.refuse(
        "A setting about a whole BPMN process is read at the workflow, at the workflow module "
            + "and at the adapter",
        misplaced,
        "the workflow, to its workflow module or to the adapter",
        List.of(
            "%s.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.adapters.<adapter>.<setting>"
                .formatted(PREFIX),
            "%s.workflow-modules.<workflow-module>.adapters.<adapter>.<setting>".formatted(PREFIX),
            "%s.adapters.<adapter>.<setting>".formatted(PREFIX)),
        """
            VanillaBP scopes the identifiers of a whole process at once, and a version \
            belongs to a process as well, so neither is ever asked for a single task.""");

  }

  /**
   * Whether the given workflow module accepts a prioritized adapter which cannot find
   * workflows, next to other adapters (<code>vanillabp.election.guessing-adapters</code>,
   * which can be overridden as
   * <code>vanillabp.workflow-modules.&lt;id&gt;.election.guessing-adapters</code>). The
   * module's setting wins where it is defined. Otherwise the global one applies, and the
   * default is to reject.
   *
   * @param workflowModuleId The workflow module ID
   * @return Whether an adapter which has to guess is accepted for this module
   */
  public boolean acceptsGuessingAdapters(
      final String workflowModuleId) {

    final var module = workflowModules.get(workflowModuleId);
    final var moduleSetting = (module != null) && (module.getElection() != null)
        ? module
            .getElection()
            .getGuessingAdapters()
        : null;
    final var effective = moduleSetting != null
        ? moduleSetting
        : election != null
            ? election.getGuessingAdapters()
            : null;
    return effective == ElectionProperties.GuessingAdapters.ACCEPTED;

  }

  /**
   * The property which names the decision of {@link #acceptsGuessingAdapters(String)}, for
   * the guiding message which has to tell how to accept what it refuses.
   *
   * @param workflowModuleId The workflow module ID
   * @return The property key of the module-scoped override
   */
  public static String guessingAdaptersProperty(
      final String workflowModuleId) {

    return "%s.workflow-modules.%s.election.guessing-adapters"
        .formatted(PREFIX, workflowModuleId);

  }

  /**
   * Whether the workflows of the given workflow module release the records of their processed
   * task deliveries when they end (<code>vanillabp.delivery.release-on-workflow-end</code>,
   * which can be overridden as
   * <code>vanillabp.workflow-modules.&lt;id&gt;.delivery.release-on-workflow-end</code>). The
   * module's setting wins where it is defined. Otherwise the global one applies, and the
   * default is to keep the records until the retention has passed.
   *
   * @param workflowModuleId The workflow module ID
   * @return Whether an ended workflow releases its delivery records
   */
  public boolean releasesDeliveryRecordsOnWorkflowEnd(
      final String workflowModuleId) {

    final var module = workflowModules.get(workflowModuleId);
    final var moduleSetting = (module != null) && (module.getDelivery() != null)
        ? module
            .getDelivery()
            .getReleaseOnWorkflowEnd()
        : null;
    final var effective = moduleSetting != null
        ? moduleSetting
        : delivery != null
            ? delivery.getReleaseOnWorkflowEnd()
            : null;
    return (effective != null) && effective;

  }

  /**
   * Whether the election cache is told about workflows which ended
   * (<code>vanillabp.workflow-adapter-cache.release-on-workflow-end</code>). Then their hints
   * leave it after <code>WorkflowAdapterCacheProperties.getEndedTimeToLive()</code>, instead
   * of after a full time-to-live. It is global and not per workflow module. There is one
   * cache per application, and it costs one listener per deployed process either way.
   * <p>
   * This is the third reason to have the BPMS report the end of a workflow, next to an
   * application's <code>&#64;WorkflowEnded</code> method and the release of delivery
   * records. Where one of those asked for it already, the cache gets it for free.
   *
   * @return Whether the end of a workflow is reported for the sake of the election cache
   */
  public boolean releasesElectionHintsOnWorkflowEnd() {

    return (workflowAdapterCache != null) && workflowAdapterCache.isReleaseOnWorkflowEnd();

  }

  /**
   * The property which names the decision of
   * {@link #releasesDeliveryRecordsOnWorkflowEnd(String)}, for the messages which have to tell
   * where the behaviour they describe comes from.
   *
   * @param workflowModuleId The workflow module ID
   * @return The property key of the module-scoped override
   */
  public static String releaseOnWorkflowEndProperty(
      final String workflowModuleId) {

    return "%s.workflow-modules.%s.delivery.release-on-workflow-end"
        .formatted(PREFIX, workflowModuleId);

  }

  /**
   * How long a task of the given scope may stay open before VanillaBP reports it
   * (<code>vanillabp.delivery.max-task-age</code>, which can be overridden per workflow
   * module, per workflow and per task). The most specific level which configured a value
   * wins, the same way as adapter-scoped properties resolve. If nothing is configured
   * anywhere, it is {@link DeliveryProperties#DEFAULT_MAX_TASK_AGE}.
   * <p>
   * A result of {@link Duration#ZERO} means that the check is switched off.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskDefinition The task definition or <code>null</code>
   * @return The maximum age, never <code>null</code>
   */
  public Duration maxTaskAge(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.getTasks().get(taskDefinition)
        : null;

    final var configured = Stream
        .of(
            task != null ? task.getDelivery() : null,
            workflow != null ? workflow.getDelivery() : null,
            module != null ? module.getDelivery() : null,
            delivery)
        .filter(java.util.Objects::nonNull)
        .map(DeliveryProperties::getMaxTaskAge)
        .filter(java.util.Objects::nonNull)
        .findFirst();
    return configured.orElse(DeliveryProperties.DEFAULT_MAX_TASK_AGE);

  }

  /**
   * How many of the OTHER tasks open in a workflow one delivery probes
   * (<code>vanillabp.delivery.max-open-tasks-checked</code>, which can be overridden per
   * workflow module, per workflow and per task). The most specific level which configured a
   * value wins, the same way as in {@link #maxTaskAge(String, String, String)}.
   * <p>
   * Zero means that the probing is off for that scope, and so does
   * <code>check-open-tasks-on-delivery: false</code>.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskDefinition The task definition or <code>null</code>
   * @return How many tasks one delivery may probe, never negative
   */
  public int maxOpenTasksChecked(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    if (!checksOpenTasksOnDelivery(workflowModuleId, bpmnProcessId, taskDefinition)) {
      return 0;
    }
    final var configured = deliveryLevelsOf(workflowModuleId, bpmnProcessId, taskDefinition)
        .map(DeliveryProperties::getMaxOpenTasksChecked)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(DeliveryProperties.DEFAULT_MAX_OPEN_TASKS_CHECKED);
    return Math.max(0, configured);

  }

  /**
   * Whether a delivery of the given scope looks at the other tasks open in its workflow at
   * all (<code>vanillabp.delivery.check-open-tasks-on-delivery</code>, which can be
   * overridden per workflow module, per workflow and per task). The default is
   * <code>true</code>.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskDefinition The task definition or <code>null</code>
   * @return Whether the other open tasks are probed
   */
  public boolean checksOpenTasksOnDelivery(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    return deliveryLevelsOf(workflowModuleId, bpmnProcessId, taskDefinition)
        .map(DeliveryProperties::getCheckOpenTasksOnDelivery)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(Boolean.TRUE);

  }

  /**
   * The <code>delivery</code> sections which may answer a question about one task, most
   * specific first: the task, its workflow, its workflow module, and the global one. Each of
   * them may be missing. That is what "whatever the next less specific level says" means.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskDefinition The task definition or <code>null</code>
   * @return The sections which exist, most specific first
   */
  private Stream<DeliveryProperties> deliveryLevelsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.getTasks().get(taskDefinition)
        : null;
    return Stream
        .of(
            task != null ? task.getDelivery() : null,
            workflow != null ? workflow.getDelivery() : null,
            module != null ? module.getDelivery() : null,
            delivery)
        .filter(java.util.Objects::nonNull);

  }

  /**
   * The property which names the decision of {@link #maxTaskAge(String, String, String)}, for
   * the message which reports a task older than that.
   *
   * @return The property key of the global setting
   */
  public static String maxTaskAgeProperty() {

    return "%s.delivery.max-task-age".formatted(PREFIX);

  }

  /**
   * Refuses a negative maximum age at every level at which it may be configured. A negative
   * duration would report every task at its first redelivery. That is a typo, not a
   * decision. Zero is allowed and switches the check off.
   */
  private void validateMaxTaskAge() {

    final var offenders = new LinkedList<String>();
    checkMaxTaskAge(delivery, "%s.delivery".formatted(PREFIX), offenders);
    workflowModules
        .values()
        .forEach(module -> {
          final var modulePrefix = "%s.workflow-modules.%s".formatted(PREFIX, module.getWorkflowModuleId());
          checkMaxTaskAge(module.getDelivery(), "%s.delivery".formatted(modulePrefix), offenders);
          module
              .getWorkflows()
              .values()
              .forEach(workflow -> {
                final var workflowPrefix = "%s.workflows.%s".formatted(modulePrefix, workflow.getBpmnProcessId());
                checkMaxTaskAge(workflow.getDelivery(), "%s.delivery".formatted(workflowPrefix), offenders);
                workflow
                    .getTasks()
                    .forEach((
                        taskDefinition,
                        task) -> checkMaxTaskAge(
                            task.getDelivery(),
                            "%s.tasks.%s.delivery".formatted(workflowPrefix, taskDefinition),
                            offenders));
              });
        });
    if (offenders.isEmpty()) {
      return;
    }
    // the maps come from a binder and keep no order. So the message is sorted, to read the
    // same way on every boot
    offenders.sort(String::compareTo);
    throw new IllegalStateException(
        """
            A negative maximum age was configured for open tasks:
              %s
            The value says how long a task may wait for its asynchronous completion before VanillaBP \
            reports it, so it has to be positive - the default is %s. Use '0' to switch the report off \
            for that scope."""
            .formatted(
                String.join("\n  ", offenders),
                DeliveryProperties.DEFAULT_MAX_TASK_AGE_ISO));

  }

  private static void checkMaxTaskAge(
      final DeliveryProperties properties,
      final String keyPrefix,
      final List<String> offenders) {

    if ((properties == null) || (properties.getMaxTaskAge() == null)) {
      return;
    }
    if (properties.getMaxTaskAge().isNegative()) {
      offenders.add("%s.max-task-age".formatted(keyPrefix));
    }

  }

  /**
   * The resources locations for the given properties, resolved in this order:
   * <ol>
   * <li>the adapter-specific location
   * <code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.resources-location</code>,</li>
   * <li>the same key at the adapter itself,
   * <code>vanillabp.adapters.&lt;id&gt;.resources-location</code>. An application which
   * keeps the BPMN of all its modules in one place per BPMS writes it there,</li>
   * <li>the global <code>vanillabp.resources-location</code> for BPMN which is NOT
   * specific to a BPMS,</li>
   * <li>the <b>convention</b>, which is what the classpath facts imply:
   * <table border="1">
   * <caption>Conventional locations</caption>
   * <tr><th>Situation</th><th>Location</th></tr>
   * <tr><td>several workflow modules, or a single one shipped as its own
   * artifact</td>
   * <td><code>classpath*:&lt;workflow-module-id&gt;/processes/&lt;adapter-id&gt;</code></td></tr>
   * <tr><td>exactly one workflow module, declared by the application's MAIN
   * artifact</td>
   * <td><code>classpath*:&lt;workflow-module-id&gt;/processes/&lt;adapter-id&gt;</code>,
   * and <code>classpath*:processes/&lt;adapter-id&gt;</code> if the first holds
   * nothing</td></tr>
   * </table>
   * A conventional location is adapter-specific like a configured one.</li>
   * </ol>
   * The locations are returned in the order in which they are searched. The first one with
   * BPMN files wins (see {@code DeploymentService}). So a process is never deployed twice. A
   * configured location is always the only one. The convention names two where the
   * application IS the workflow module. A module tested inside its own Maven module is the
   * main artifact as well, while its files are below the module ID.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @return The locations to search, never empty. Each says whether it has VanillaBP BPMN
   *         (true) or BPMN for the target BPMS (false)
   */
  public List<ResourcesLocation> getAdapterResourcesLocationsFor(
      final String workflowModuleId,
      final String adapterId) {

    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule != null) {
      final var adapter = workflowModule.getAdapters().get(adapterId);
      if ((adapter != null) && (adapter.getResourcesLocation() != null) && !adapter.getResourcesLocation().isBlank()) {
        return List.of(new ResourcesLocation(adapter.getResourcesLocation(), false));
      }
    }

    final var adapter = adapters.get(adapterId);
    if ((adapter != null) && (adapter.getResourcesLocation() != null) && !adapter.getResourcesLocation().isBlank()) {
      return List.of(new ResourcesLocation(adapter.getResourcesLocation(), false));
    }

    final var globalResourcesLocation = getResourcesLocation();
    if ((globalResourcesLocation != null) && !globalResourcesLocation.isBlank()) {
      return List.of(new ResourcesLocation(globalResourcesLocation, true));
    }

    final var conventionalLocations = conventionalResourcesLocations.get(workflowModuleId);
    if ((conventionalLocations != null) && !conventionalLocations.isEmpty()) {
      return conventionalLocations
          .stream()
          .map(conventionalLocation -> new ResourcesLocation(
              "%s/%s".formatted(conventionalLocation, adapterId), false))
          .toList();
    }

    throw new IllegalStateException(
        """
            None of these properties is set for resources specific to the BPMS
              '%s.workflow-modules.%s.adapters.%s.resources-location' or
              '%s.adapters.%s.resources-location'
            nor property '%s.resources-location' for VanillaBP resources (not specific to the BPMS), and the
            workflow module '%s' is not known in the classpath, so no location can be derived by convention!

            If using one of the first two options then the location needs to be specific to the adapter, so that
            migrating to another adapter later costs nothing.
            Sample: 'classpath*:/workflow-resources/%s'"""
            .formatted(PREFIX, workflowModuleId, adapterId, PREFIX, adapterId, PREFIX, workflowModuleId, adapterId));

  }

  /**
   * Why the convention searched where it did for a workflow module, as a sentence for a
   * message about BPMN files which were not found. The convention only searches below
   * <code>processes/</code> for the single workflow module of the application's own artifact.
   * A developer who put the files there needs to know whether VanillaBP saw the module that
   * way.
   *
   * @param workflowModuleId The workflow module ID
   * @return The sentence, or nothing where every adapter of the module reads a configured
   *         location, and the convention played no part
   */
  public Optional<String> whyTheConventionSearchedThere(
      final String workflowModuleId) {

    final var conventionalLocations = conventionalResourcesLocations.get(workflowModuleId);
    if (conventionalLocations == null) {
      return Optional.empty();
    }
    final var conventionWasUsed = getDeploymentAdaptersFor(workflowModuleId)
        .stream()
        .anyMatch(adapterId -> getAdapterResourcesLocationsFor(workflowModuleId, adapterId)
            .getFirst()
            .location()
            .equals("%s/%s".formatted(conventionalLocations.getFirst(), adapterId)));
    if (!conventionWasUsed) {
      return Optional.empty();
    }
    if (conventionalLocations.size() > 1) {
      return Optional.of(
          """
              The application's own JAR or directory holds this workflow module, so \
              'classpath*:processes/<adapter>' was searched as well.""");
    }
    if (conventionalResourcesLocations.size() > 1) {
      return Optional.of(
          """
              The application has %d workflow modules, so the BPMN files of each one are \
              expected below its own module ID."""
              .formatted(conventionalResourcesLocations.size()));
    }
    return Optional.of(
        """
            The file 'META-INF/workflow-module' of this module was not found in the \
            application's own JAR or directory, so 'classpath*:processes/<adapter>' was not \
            searched. VanillaBP searches there only for the workflow module of the application \
            itself.""");

  }

  /**
   * Checks the configuration of a platform which has nothing to add to the messages. It is
   * the same run as {@link #validateProperties(List, List, String)}, without a note of its
   * own.
   *
   * @param adaptersLoaded The adapter types found in the classpath
   * @param knownWorkflowModuleIds The workflow module IDs found in the classpath
   * @throws IllegalStateException Where the configuration cannot be made to work, with a
   *           message which names the keys to write
   */
  public void validateProperties(
      final List<String> adaptersLoaded,
      final List<String> knownWorkflowModuleIds) {

    validateProperties(adaptersLoaded, knownWorkflowModuleIds, null);

  }

  /**
   * Adds a platform-specific note to a guiding message, if there is one.
   *
   * @param message The platform-neutral message
   * @param platformConfigurationNote The note or null
   * @return The message, followed by the note in a paragraph of its own
   */
  private static String appendPlatformNote(
      final String message,
      final String platformConfigurationNote) {

    if ((platformConfigurationNote == null) || platformConfigurationNote.isBlank()) {
      return message;
    }
    return "%s\n%s".formatted(message, platformConfigurationNote);

  }

  /**
   * Checks the configuration. It is the same on all platforms, except for the
   * <code>platformConfigurationNote</code>. On some platforms the configuration binding
   * behaves in a way which a developer cannot guess from the properties alone. Such a
   * platform passes a sentence which names that behaviour. It is added to the messages which
   * a developer could otherwise misread (see
   * {@link io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties#adapterTypes()}
   * for the convention "the id is the type", which this is about).
   *
   * @param adaptersLoaded The adapter types found in the classpath
   * @param knownWorkflowModuleIds The workflow module IDs found in the classpath
   * @param platformConfigurationNote A platform-specific note added to "nothing configured"
   *     messages, or null
   */
  public void validateProperties(
      final List<String> adaptersLoaded,
      final List<String> knownWorkflowModuleIds,
      final String platformConfigurationNote) {

    validateProperties(
        ClasspathFacts.of(adaptersLoaded, knownWorkflowModuleIds),
        platformConfigurationNote);

  }

  /**
   * Checks the configuration, after everything which can be derived from the classpath
   * facts was derived (see {@link #normalize(ClasspathFacts)}). The rules are the same for
   * configured and for derived entries.
   * <p>
   * What this refuses is thrown where it is found, and not collected into
   * {@link io.vanillabp.integration.adapter.migration.startup.StartupFindings}. This runs
   * while the configuration is bound, before there is an adapter, a workflow module or a
   * deployment. Every later check reads the very object which this one just called unusable.
   * A start which went on from here would answer the next hundred questions from a
   * configuration which nobody can trust. And the box at its end would name a hundred things
   * which a developer does not have to change. What the check NOTICES, and does not refuse,
   * does go into the box.
   *
   * @param facts What the platform knows about the application without any property
   * @param platformConfigurationNote A platform-specific note added to "nothing configured"
   *     messages, or null
   */
  public void validateProperties(
      final ClasspathFacts facts,
      final String platformConfigurationNote) {

    final var adaptersLoaded = facts.adapterTypes();
    final var knownWorkflowModuleIds = facts.workflowModuleIds();

    normalize(facts);
    validateAndLink();
    validateWorkflowModuleIdsAgainstPrefixing();
    if (workflowAdapterCache == null) {
      // a binder which maps a missing section to null must not cost the defaults
      workflowAdapterCache = new WorkflowAdapterCacheProperties();
    }
    workflowAdapterCache.validate();
    if (metrics == null) {
      // a binder which maps a missing section to null must not cost the defaults
      metrics = new MetricsProperties();
    }
    metrics.validate();
    if (outbox == null) {
      // a binder which maps a missing section to null must not cost the defaults
      outbox = new PhaseTwoOutboxProperties();
    }
    outbox.validateStoreNames();
    outbox.validateHousekeeping(startupFindings());
    outbox.validateVisibilityWait();
    validateMaxTaskAge();
    refuseFullSyncPermissionsOutsideAWorkflow();
    refuseResourcesLocationsBelowTheWorkflowModule();
    refuseSettingsOfAWholeProcessWrittenAtATask();
    refuseDeliveryRetentionsBelowTheApplication();
    refuseDeliveryReleasesBelowTheWorkflowModule();
    reportRetentionSplit();
    reportWhatStaysAwake();

    if (knownWorkflowModuleIds.isEmpty()) {
      throw new IllegalStateException("No workflow-modules where given!");
    }

    if (adapters.isEmpty() && adaptersLoaded.isEmpty()) {
      throw new IllegalStateException(
          "No adapters configured and none found in classpath! Add a dependency providing a VanillaBP adapter.");
    }

    if (adapters.isEmpty()) {
      // several adapter types in the classpath (exactly one would have been derived by
      // normalize). Nobody can guess which one starts new workflows, and in which order
      // existing workflows are looked up. The order is made deterministic, because the
      // classpath order of extensions and beans is not stable
      final var sortedAdapterTypes = adaptersLoaded
          .stream()
          .sorted()
          .toList();
      throw new IllegalStateException(
          appendPlatformNote(
              """
                  Several VanillaBP adapters were found in classpath:
                    %s
                  Name the order in which they are used by the property '%s.prioritized-adapters' - the first one \
                  starts new workflows, the others are asked for workflows started earlier (BPMS migration).
                  Sample:
                    %s.prioritized-adapters:
                  %s
                  An adapter id which IS an adapter type needs no further configuration; a custom id needs a section \
                  '%s.adapters.<id>.type=<adapter type>'."""
                  .formatted(
                      String.join("\n  ", sortedAdapterTypes),
                      PREFIX,
                      PREFIX,
                      sortedAdapterTypes
                          .stream()
                          .map("    - %s"::formatted)
                          .collect(Collectors.joining("\n")),
                      PREFIX),
              platformConfigurationNote));
    }

    // adapterTypes() collects into a hash map. So the lines are sorted, to read the same way
    // on every boot
    final var adaptersNotInClasspath = adapterTypes()
        .entrySet()
        .stream()
        .filter(entry -> !adaptersLoaded.contains(entry.getValue()))
        .map(entry -> "%s of type %s".formatted(entry.getKey(), entry.getValue()))
        .sorted()
        .collect(Collectors.joining(",\n  "));
    if (!adaptersNotInClasspath.isEmpty()) {
      throw new IllegalStateException(
          """
              The following adapters were configured in properties section 'vanillabp.adapters' but there is no adapter in classpath matching the given type:
                 %s
              Available adapter types in classpath: %s"""
              .formatted(adaptersNotInClasspath, adaptersLoaded));
    }

    // a workflow module found in the classpath needs NO configuration any more.
    // normalize(facts) derived an empty section for it, and the resources-location
    // convention finds its BPMN resources

    // unknown workflow-module properties
    final var workflowModulesConfiguredButNotInClasspath = new LinkedList<>(getWorkflowModules().keySet());
    workflowModulesConfiguredButNotInClasspath.removeAll(knownWorkflowModuleIds);
    // the map comes from a binder and keeps no order. So the message is sorted, to read the
    // same way on every boot
    workflowModulesConfiguredButNotInClasspath.sort(String::compareTo);
    if (!workflowModulesConfiguredButNotInClasspath.isEmpty()) {
      final var propPrefix = "\n  %s.workflow-modules.".formatted(PREFIX);
      startupFindings()
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.CONFIGURATION,
              "%s.workflow-modules".formatted(PREFIX),
              """
                  Found properties for workflow modules
                    %s.workflow-modules.%s
                  which were not found in the class-path! These properties are never used - remove them
                  or add the workflow module (a dependency having a 'META-INF/workflow-module' marker
                  file with that ID) to the application."""
                  .formatted(
                      PREFIX,
                      String.join(propPrefix, workflowModulesConfiguredButNotInClasspath)));
    }

    // adapter entries which are never used (a check from V1). Every key under an
    // 'adapters.<id>' section of any level (workflow module, workflow, task) has to refer to
    // a configured adapter id
    final var unusedModuleAdapterEntries = getWorkflowModules()
        .values()
        .stream()
        .flatMap(workflowModule -> {
          final var modulePrefix = "%s.workflow-modules.%s".formatted(PREFIX, workflowModule.workflowModuleId);
          return Stream.concat(
              unknownAdapterKeys(workflowModule.getAdapters(), modulePrefix),
              workflowModule
                  .getWorkflows()
                  .values()
                  .stream()
                  .flatMap(workflow -> {
                    final var workflowPrefix = "%s.workflows.%s".formatted(modulePrefix, workflow.getBpmnProcessId());
                    return Stream.concat(
                        unknownAdapterKeys(workflow.getAdapters(), workflowPrefix),
                        workflow
                            .getTasks()
                            .entrySet()
                            .stream()
                            .flatMap(task -> unknownAdapterKeys(
                                task.getValue().getAdapters(),
                                "%s.tasks.%s".formatted(workflowPrefix, task.getKey()))));
                  }));
        })
        .sorted()
        .collect(Collectors.joining("\n  "));
    if (!unusedModuleAdapterEntries.isEmpty()) {
      throw new IllegalStateException(
          """
              These properties refer to adapter ids not configured in 'vanillabp.adapters.*' - they are never used:
                %s
              Configured adapter ids are: '%s'. Fix the adapter id or add a section 'vanillabp.adapters.<id>'."""
              .formatted(unusedModuleAdapterEntries, configuredAdapterIdsJoinedBy("', '")));
    }

    // duplicates in prioritized-adapters lists
    validateNoDuplicatePrioritizedAdapters(
        getPrioritizedAdapters(),
        "%s.prioritized-adapters".formatted(PREFIX));

    // if more than one adapter is configured, the property 'vanillabp.prioritized-adapters'
    // has to list each configured adapter
    if (adapters.size() > 1 && (getPrioritizedAdapters().size() != adapters.size())) {
      throw new IllegalStateException(
          """
              The property '%s.prioritized-adapters' must list all the adapters configured in '%s.adapters.*' to define
              the order in which adapters are addressed to find workflows running.
              Configured adapters are: %s."""
              .formatted(PREFIX, PREFIX, configuredAdapterIdsJoinedBy(", ")));
    }
    getWorkflowModules()
        .values()
        .forEach(workflowModule -> {
          validateNoDuplicatePrioritizedAdapters(
              workflowModule.getPrioritizedAdapters(),
              "%s.workflow-modules.%s.prioritized-adapters".formatted(PREFIX, workflowModule.workflowModuleId));
          workflowModule
              .getWorkflows()
              .values()
              .forEach(workflow -> validateNoDuplicatePrioritizedAdapters(
                  workflow.getPrioritizedAdapters(),
                  "%s.workflow-modules.%s.workflows.%s.prioritized-adapters"
                      .formatted(PREFIX, workflowModule.workflowModuleId, workflow.getBpmnProcessId())));
        });

    // adapter configured
    if (adapters.size() == 1) {
      final var adapterId = adapters.keySet().iterator().next();
      // only CONFIGURED adapter-specific locations are worth the hint. A location derived
      // by convention is adapter-specific by design
      final var specificBpmnResources = workflowModules
          .entrySet()
          .stream()
          .filter(entry -> {
            final var adapter = entry
                .getValue()
                .getAdapters()
                .get(adapterId);
            return (adapter != null) && (adapter.getResourcesLocation() != null) && !adapter.getResourcesLocation()
                .isBlank();
          })
          .map(Map.Entry::getKey)
          // the map comes from a binder and keeps no order. So the message is sorted, to
          // read the same way on every boot
          .sorted()
          .toList();
      if (!specificBpmnResources.isEmpty()) {
        final var propPrefix = "%s.workflow-modules.".formatted(PREFIX);
        final var propInfix = ".adapters.%s.resources-location\n  %s".formatted(
            adapterId,
            propPrefix);
        final var propPostfix = ".adapters.%s.resources-location".formatted(adapterId);
        startupFindings()
            .notice(
                io.vanillabp.integration.spi.startup.StartupTopic.CONFIGURATION,
                "adapter '%s'".formatted(adapterId),
                """
                    Found only one VanillaBP adapter '%s' configured. Please ensure the properties
                      %s%s%s
                    are specific to this adapter in order to avoid future-problems once you wish to migrate to another adapter."""
                    .formatted(
                        adapterId,
                        propPrefix,
                        String.join(
                            propInfix,
                            specificBpmnResources),
                        propPostfix));
      }
    }

    // adapters in the classpath which are not used
    final var notConfiguredAdapters = new HashMap<String, Set<String>>();
    getPrioritizedAdapters()
        .stream()
        .filter(adapterId -> !adapters.containsKey(adapterId))
        .forEach(
            adapterId -> notConfiguredAdapters
                .computeIfAbsent(
                    "%s.prioritized-adapters".formatted(MigrationAdapterProperties.PREFIX),
                    key -> new HashSet<>())
                .add(adapterId));
    getWorkflowModules()
        .values()
        .forEach(
            workflowModule -> {
              workflowModule.getPrioritizedAdapters().stream()
                  .filter(adapterId -> !adapters.containsKey(adapterId))
                  .forEach(
                      adapterId -> notConfiguredAdapters
                          .computeIfAbsent(
                              "%s.workflow-modules.%s.prioritized-adapters".formatted(PREFIX,
                                  workflowModule.workflowModuleId),
                              key -> new HashSet<>())
                          .add(adapterId));
              workflowModule
                  .getWorkflows()
                  .values()
                  .forEach(
                      workflow -> workflow
                          .getPrioritizedAdapters()
                          .stream()
                          .filter(adapterId -> !adapters.containsKey(adapterId))
                          .forEach(
                              adapterId -> notConfiguredAdapters
                                  .computeIfAbsent(
                                      "%s.workflow-modules.%s.workflows.%s.prioritized-adapters".formatted(PREFIX,
                                          workflowModule.workflowModuleId, workflow.getBpmnProcessId()),
                                      key -> new HashSet<>())
                                  .add(adapterId)));
            });
    if (!notConfiguredAdapters.isEmpty()) {
      throw new IllegalStateException(
          """
              There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
                %s
              """
              // the keys and the ids below them are in hash collections. So both are
              // sorted, to read the same way on every boot
              .formatted(notConfiguredAdapters
                  .entrySet()
                  .stream()
                  .sorted(Map.Entry.comparingByKey())
                  .map(entry -> "%s => %s".formatted(
                      entry.getKey(),
                      entry
                          .getValue()
                          .stream()
                          .sorted()
                          .collect(Collectors.joining(","))))
                  .collect(Collectors.joining("\n  "))));
    }

    // resources-location. An empty prioritized-adapters list cannot happen here. normalize()
    // sets a single configured adapter as the default, and the check above forces several
    // adapters into 'vanillabp.prioritized-adapters'
    knownWorkflowModuleIds.forEach(
        workflowModuleId -> {
          final var prioritizedAdaptersOfModule = getPrioritizedAdaptersFor(workflowModuleId, null);
          prioritizedAdaptersOfModule
              .forEach(adapterId -> getAdapterResourcesLocationsFor(workflowModuleId, adapterId));
          getBpmnProcessIdsForWorkflowModule(workflowModuleId)
              .forEach(
                  bpmnProcessId -> {
                    final var prioritizedAdapters = getPrioritizedAdaptersFor(
                        workflowModuleId,
                        bpmnProcessId
                    );
                    prioritizedAdapters.forEach(adapterId -> getAdapterResourcesLocationsFor(
                        workflowModuleId,
                        adapterId));
                  });
        });
  }

  /**
   * Checks that environment variables which address the <code>vanillabp.*</code> tree were
   * really taken over by the configuration binding. Environment variables can only
   * <b>override</b> entries of dynamic maps (adapters, workflow modules) which are already
   * declared in a configuration file. They cannot <b>add</b> a new entry whose ID contains
   * dashes or dots, because the binder cannot rebuild the ID from the variable's name. The
   * binding silently ignores such a variable. So this check fails the startup with a guiding
   * message instead.
   * <p>
   * The check is a best-effort guard on the ID segments. It matches on an uppercase form
   * without separators (<code>c8-cloud</code> matches both <code>C8_CLOUD</code> and
   * <code>C8CLOUD</code>). So unusual IDs which share a prefix with another configured ID may
   * escape it. But a valid override is never reported as an error.
   *
   * @param rawPropertyNames The raw names of all available properties, including the
   *          unconverted names of environment variables (both platforms provide them)
   */
  public void validateEnvironmentVariableUsage(
      final Iterable<String> rawPropertyNames) {

    final var envVarPrefix = PREFIX.toUpperCase()
        + "_";
    final var violations = new LinkedList<String>();

    for (final var propertyName : rawPropertyNames) {
      if (!propertyName.matches("^%s[A-Z0-9_]+$".formatted(envVarPrefix))) {
        continue;
      }
      final var path = propertyName.substring(envVarPrefix.length());
      if (path.startsWith("ADAPTERS_")) {
        validateEnvironmentVariableIdSegment(
            propertyName,
            path.substring("ADAPTERS_".length()),
            adapters.keySet(),
            "%s.adapters".formatted(PREFIX),
            violations);
      } else if (path.startsWith("WORKFLOW_MODULES_")) {
        validateEnvironmentVariableIdSegment(
            propertyName,
            path.substring("WORKFLOW_MODULES_".length()),
            workflowModules.keySet(),
            "%s.workflow-modules".formatted(PREFIX),
            violations);
      }
      // all other sections (for example prioritized-adapters, resources-location, outbox)
      // have no dynamic ID segment. Either the platform's detection of unknown keys catches a
      // typo there, or it does no harm to the BPMS election
    }

    if (!violations.isEmpty()) {
      // the variables arrive in whatever order the platform hands them over. So the message
      // is sorted, to read the same way on every boot
      violations.sort(String::compareTo);
      throw new IllegalStateException(
          """
              Environment variables addressing the '%s' configuration were NOT taken over by the configuration binding:
                %s
              Environment variables can only OVERRIDE entries already declared in a configuration file - they
              cannot introduce a new adapter or workflow module (the entry's ID cannot be reconstructed from the
              variable's name). Declare the ID in a configuration file (e.g. 'vanillabp.adapters.<id>.type') and
              use the environment variable to override its values, or fix the ID part of the variable's name."""
              .formatted(PREFIX, String.join("\n  ", violations)));
    }

  }

  private static void validateEnvironmentVariableIdSegment(
      final String propertyName,
      final String idAndRemainder,
      final Set<String> configuredIds,
      final String sectionKey,
      final List<String> violations) {

    final var comparableRemainder = idAndRemainder.replace("_", "");
    final var matches = configuredIds
        .stream()
        .map(id -> id.toUpperCase().replaceAll("[^A-Z0-9]", ""))
        .anyMatch(comparableRemainder::startsWith);
    if (!matches) {
      violations.add(
          "'%s' does not address any of the IDs configured in '%s.*' (%s)"
              .formatted(
                  propertyName,
                  sectionKey,
                  configuredIds.isEmpty()
                      ? "none configured"
                      // the ids come from a binder's map, which keeps no order
                      : "'%s'".formatted(
                          configuredIds
                              .stream()
                              .sorted()
                              .collect(Collectors.joining("', '")))));
    }

  }

  private static void validateNoDuplicatePrioritizedAdapters(
      final List<String> prioritizedAdapters,
      final String propertyKey) {

    final var duplicates = prioritizedAdapters
        .stream()
        .filter(adapterId -> prioritizedAdapters
            .stream()
            .filter(adapterId::equals)
            .count() > 1)
        .distinct()
        .collect(Collectors.joining("', '"));
    if (!duplicates.isEmpty()) {
      throw new IllegalStateException(
          """
              The property '%s' lists these adapter ids more than once: '%s'!
              Remove the duplicates - the order of the remaining entries defines the priority."""
              .formatted(propertyKey, duplicates));
    }

  }

  private List<String> getBpmnProcessIdsForWorkflowModule(
      final String workflowModuleId) {

    final var workflowModule = getWorkflowModules().get(workflowModuleId);
    if (workflowModule == null) {
      return List.of();
    }

    return workflowModule.getWorkflows().values().stream()
        .map(WorkflowAdapterProperties::getBpmnProcessId)
        .toList();
  }

  /**
   * The builder of {@link MigrationAdapterProperties}. Its two type parameters are the class
   * being built and the builder itself. So a call inherited from a base class comes back as
   * the builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class MigrationAdapterPropertiesBuilder<C extends MigrationAdapterProperties, B extends MigrationAdapterProperties.MigrationAdapterPropertiesBuilder<C, B>> extends AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder<C, B> {

    /**
     * The configuration of all known adapters (properties section
     * <code>vanillabp.adapters.&lt;id&gt;.*</code>). The builder starts from the same value as
     * the field.
     */
    private Map<String, AdapterConfigProperties> adapters = Map.of();

    /**
     * Where to load VanillaBP BPMN files from which are NOT specific to any adapter.
     */
    private String resourcesLocation;

    /**
     * Properties specific to workflow modules. The builder starts from the same value as the
     * field.
     */
    private Map<String, WorkflowModuleAdapterProperties> workflowModules = Map.of();

    /**
     * The settings of the extensions (properties section
     * <code>vanillabp.extensions.&lt;extension&gt;.*</code>), overridable per workflow
     * module, per workflow and per task. The builder starts from the same value the
     * field does.
     */
    private Map<String, Map<String, String>> extensions = Map.of();

    /**
     * Configuration of the default {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
     * implementations (properties section <code>vanillabp.outbox</code>). The builder
     * starts from the same value the field does.
     */
    private PhaseTwoOutboxProperties outbox = new PhaseTwoOutboxProperties();

    /**
     * Refused here on purpose: the permission to share a whole workflow aggregate
     * belongs to the single workflow (see decision 66 in the repository's DECISIONS.md).
     */
    private Boolean allowFullSyncWithBpms;

    /**
     * Whether the expressions of every BPMN model of this application are meant as they
     * are (<code>vanillabp.accept-expressions-in-the-model</code>) - the least specific of
     * the three levels, for an application which decided it once.
     */
    private Boolean acceptExpressionsInTheModel;

    /**
     * The <code>&#64;TaskParam</code> parameters of the whole application whose type the
     * developer declared (<code>vanillabp.declared-task-params</code>).
     */
    private java.util.List<String> declaredTaskParams;

    /**
     * Whether something other than this application serves every task which does not say
     * otherwise (<code>implemented-externally</code>, see {@link #implementedExternally}).
     * The least specific of the eight positions; <code>null</code> means "not configured
     * at this level".
     */
    private Boolean implementedExternally;

    /**
     * Configuration of the default election cache
     * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} (properties section
     * <code>vanillabp.workflow-adapter-cache</code>). The builder starts from the same
     * value the field does.
     */
    private WorkflowAdapterCacheProperties workflowAdapterCache = new WorkflowAdapterCacheProperties();

    /**
     * What VanillaBP does about a workflow aggregate whose store is not covered by the
     * transaction it opens (properties section <code>vanillabp.transactions</code>,
     * overridable per workflow module). The builder starts from the same value the field
     * does.
     */
    private TransactionsProperties transactions = new TransactionsProperties();

    /**
     * What VanillaBP does about a prioritized adapter which cannot locate workflows
     * (properties section <code>vanillabp.election</code>, overridable per workflow
     * module). The builder starts from the same value the field does.
     */
    private ElectionProperties election = new ElectionProperties();

    /**
     * What VanillaBP does with the records of processed task deliveries (properties
     * section <code>vanillabp.delivery</code>, overridable per workflow module - except
     * the retention, see <code>vanillabp.delivery.retention</code>). The builder starts
     * from the same value the field does.
     */
    private DeliveryProperties delivery = new DeliveryProperties();

    /**
     * What VanillaBP publishes as metrics (properties section
     * <code>vanillabp.metrics</code>) - today only how long the measurement of a gauge
     * which has to ask somebody is reused. The builder starts from the same value the
     * field does.
     */
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * Where every startup check leaves what it found, so the whole start says it once,
     * at its end, instead of a line per check (see
     * {@link io.vanillabp.integration.adapter.migration.startup.StartupFindings}). The
     * builder starts from the same value the field does.
     */
    private io.vanillabp.integration.adapter.migration.startup.StartupFindings startupFindings = new io.vanillabp.integration.adapter.migration.startup.StartupFindings();

    /**
     * Adapter ids this application USED to have and deliberately does not configure any
     * more - the last step of a BPMS migration, once nothing runs in the old BPMS any
     * more. The builder starts from the same value the field does.
     */
    private List<String> retiredAdapters = List.of();

    /**
     * The base location BPMN resources of a workflow module are read from when neither
     * an adapter-specific nor the global <code>resources-location</code> is configured -
     * derived from the classpath facts by {@link #normalize(ClasspathFacts)}. The
     * builder starts from the same value the field does.
     */
    private Map<String, List<String>> conventionalResourcesLocations = Map.of();

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link MigrationAdapterProperties#builder()} hands out the builder of this class.
     */
    public MigrationAdapterPropertiesBuilder() {
    }

    /**
     * The configuration of all adapters known (properties section
     * <code>vanillabp.adapters.&lt;id&gt;.*</code>).
     *
     * @param adapters The value of {@link #adapters}
     * @return This builder, so the calls chain
     */
    public B adapters(
        final Map<String, AdapterConfigProperties> adapters) {

      this.adapters = adapters;
      return self();

    }

    /**
     * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
     *
     * @param resourcesLocation The value of {@link #resourcesLocation}
     * @return This builder, so the calls chain
     */
    public B resourcesLocation(
        final String resourcesLocation) {

      this.resourcesLocation = resourcesLocation;
      return self();

    }

    /**
     * Properties specific to workflow modules.
     *
     * @param workflowModules The value of {@link #workflowModules}
     * @return This builder, so the calls chain
     */
    public B workflowModules(
        final Map<String, WorkflowModuleAdapterProperties> workflowModules) {

      this.workflowModules = workflowModules;
      return self();

    }

    /**
     * The settings of the extensions (properties section
     * <code>vanillabp.extensions.&lt;extension&gt;.*</code>), overridable per workflow
     * module, per workflow and per task.
     *
     * @param extensions The value of {@link #extensions}
     * @return This builder, so the calls chain
     */
    public B extensions(
        final Map<String, Map<String, String>> extensions) {

      this.extensions = extensions;
      return self();

    }

    /**
     * Configuration of the default {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
     * implementations (properties section <code>vanillabp.outbox</code>).
     *
     * @param outbox The value of {@link #outbox}
     * @return This builder, so the calls chain
     */
    public B outbox(
        final PhaseTwoOutboxProperties outbox) {

      this.outbox = outbox;
      return self();

    }

    /**
     * Refused here on purpose: the permission to share a whole workflow aggregate
     * belongs to the single workflow (see decision 66 in the repository's DECISIONS.md).
     *
     * @param allowFullSyncWithBpms The value of {@link #allowFullSyncWithBpms}
     * @return This builder, so the calls chain
     */
    public B allowFullSyncWithBpms(
        final Boolean allowFullSyncWithBpms) {

      this.allowFullSyncWithBpms = allowFullSyncWithBpms;
      return self();

    }

    /**
     * Whether the expressions of every BPMN model of this application are meant as they
     * are (<code>vanillabp.accept-expressions-in-the-model</code>) - the least specific of
     * the three levels, for an application which decided it once.
     *
     * @param acceptExpressionsInTheModel The value of
     * {@link #acceptExpressionsInTheModel}
     * @return This builder, so the calls chain
     */
    public B acceptExpressionsInTheModel(
        final Boolean acceptExpressionsInTheModel) {

      this.acceptExpressionsInTheModel = acceptExpressionsInTheModel;
      return self();

    }

    /**
     * The <code>&#64;TaskParam</code> parameters of the whole application whose type the
     * developer declared (<code>vanillabp.declared-task-params</code>).
     *
     * @param declaredTaskParams The value of {@link #declaredTaskParams}
     * @return This builder, so the calls chain
     */
    public B declaredTaskParams(
        final java.util.List<String> declaredTaskParams) {

      this.declaredTaskParams = declaredTaskParams;
      return self();

    }

    /**
     * Whether something other than this application serves every task which does not say
     * otherwise (<code>implemented-externally</code>, see {@link #implementedExternally}).
     * The least specific of the eight positions; <code>null</code> means "not configured
     * at this level".
     *
     * @param implementedExternally The value of {@link #implementedExternally}
     * @return This builder, so the calls chain
     */
    public B implementedExternally(
        final Boolean implementedExternally) {

      this.implementedExternally = implementedExternally;
      return self();

    }

    /**
     * Configuration of the default election cache
     * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} (properties section
     * <code>vanillabp.workflow-adapter-cache</code>).
     *
     * @param workflowAdapterCache The value of {@link #workflowAdapterCache}
     * @return This builder, so the calls chain
     */
    public B workflowAdapterCache(
        final WorkflowAdapterCacheProperties workflowAdapterCache) {

      this.workflowAdapterCache = workflowAdapterCache;
      return self();

    }

    /**
     * What VanillaBP does about a workflow aggregate whose store is not covered by the
     * transaction it opens (properties section <code>vanillabp.transactions</code>,
     * overridable per workflow module).
     *
     * @param transactions The value of {@link #transactions}
     * @return This builder, so the calls chain
     */
    public B transactions(
        final TransactionsProperties transactions) {

      this.transactions = transactions;
      return self();

    }

    /**
     * What VanillaBP does about a prioritized adapter which cannot locate workflows
     * (properties section <code>vanillabp.election</code>, overridable per workflow
     * module).
     *
     * @param election The value of {@link #election}
     * @return This builder, so the calls chain
     */
    public B election(
        final ElectionProperties election) {

      this.election = election;
      return self();

    }

    /**
     * What VanillaBP does with the records of processed task deliveries (properties
     * section <code>vanillabp.delivery</code>, overridable per workflow module - except
     * the retention, see <code>vanillabp.delivery.retention</code>).
     *
     * @param delivery The value of {@link #delivery}
     * @return This builder, so the calls chain
     */
    public B delivery(
        final DeliveryProperties delivery) {

      this.delivery = delivery;
      return self();

    }

    /**
     * What VanillaBP publishes as metrics (properties section
     * <code>vanillabp.metrics</code>) - today only how long the measurement of a gauge
     * which has to ask somebody is reused.
     *
     * @param metrics The value of {@link #metrics}
     * @return This builder, so the calls chain
     */
    public B metrics(
        final MetricsProperties metrics) {

      this.metrics = metrics;
      return self();

    }

    /**
     * Where every startup check leaves what it found, so the whole start says it once,
     * at its end, instead of a line per check (see
     * {@link io.vanillabp.integration.adapter.migration.startup.StartupFindings}).
     *
     * @param startupFindings The value of {@link #startupFindings}
     * @return This builder, so the calls chain
     */
    public B startupFindings(
        final io.vanillabp.integration.adapter.migration.startup.StartupFindings startupFindings) {

      this.startupFindings = startupFindings;
      return self();

    }

    /**
     * Adapter ids this application USED to have and deliberately does not configure any
     * more - the last step of a BPMS migration, once nothing runs in the old BPMS any
     * more.
     *
     * @param retiredAdapters The value of {@link #retiredAdapters}
     * @return This builder, so the calls chain
     */
    public B retiredAdapters(
        final List<String> retiredAdapters) {

      this.retiredAdapters = retiredAdapters;
      return self();

    }

    /**
     * The base location BPMN resources of a workflow module are read from when neither
     * an adapter-specific nor the global <code>resources-location</code> is configured -
     * derived from the classpath facts by {@link #normalize(ClasspathFacts)}.
     *
     * @param conventionalResourcesLocations The value of {@link #conventionalResourcesLocations}
     * @return This builder, so the calls chain
     */
    public B conventionalResourcesLocations(
        final Map<String, List<String>> conventionalResourcesLocations) {

      this.conventionalResourcesLocations = conventionalResourcesLocations;
      return self();

    }

    /**
     * The builder itself, typed as the builder of the subclass. Every method of the
     * chain returns it, which is what keeps a chain started on a subclass builder at
     * that subclass.
     *
     * @return This builder
     */
    @Override
    protected abstract B self();

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public abstract C build();

    /**
     * What this builder holds, for a message and for a debugger.
     *
     * @return The name of this builder and every value written into it
     */
    @Override
    public String toString() {

      return "MigrationAdapterProperties.MigrationAdapterPropertiesBuilder("
          + "super="
          + super.toString()
          + ", "
          + "adapters="
          + adapters
          + ", "
          + "resourcesLocation="
          + resourcesLocation
          + ", "
          + "workflowModules="
          + workflowModules
          + ", "
          + "extensions="
          + extensions
          + ", "
          + "outbox="
          + outbox
          + ", "
          + "allowFullSyncWithBpms="
          + allowFullSyncWithBpms
          + ", "
          + "acceptExpressionsInTheModel="
          + acceptExpressionsInTheModel
          + ", "
          + "declaredTaskParams="
          + declaredTaskParams
          + ", "
          + "implementedExternally="
          + implementedExternally
          + ", "
          + "workflowAdapterCache="
          + workflowAdapterCache
          + ", "
          + "transactions="
          + transactions
          + ", "
          + "election="
          + election
          + ", "
          + "delivery="
          + delivery
          + ", "
          + "metrics="
          + metrics
          + ", "
          + "startupFindings="
          + startupFindings
          + ", "
          + "retiredAdapters="
          + retiredAdapters
          + ", "
          + "conventionalResourcesLocations="
          + conventionalResourcesLocations
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link MigrationAdapterProperties} itself rather than a subclass of it.
   */
  private static final class MigrationAdapterPropertiesBuilderImpl extends MigrationAdapterProperties.MigrationAdapterPropertiesBuilder<MigrationAdapterProperties, MigrationAdapterProperties.MigrationAdapterPropertiesBuilderImpl> {

    /**
     * Nobody but {@link MigrationAdapterProperties#builder()} builds one.
     */
    private MigrationAdapterPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected MigrationAdapterProperties.MigrationAdapterPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public MigrationAdapterProperties build() {

      return new MigrationAdapterProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected MigrationAdapterProperties(
      final MigrationAdapterProperties.MigrationAdapterPropertiesBuilder<?, ?> b) {

    super(b);

    this.adapters = b.adapters;
    this.resourcesLocation = b.resourcesLocation;
    this.workflowModules = b.workflowModules;
    this.extensions = b.extensions;
    this.outbox = b.outbox;
    this.allowFullSyncWithBpms = b.allowFullSyncWithBpms;
    this.acceptExpressionsInTheModel = b.acceptExpressionsInTheModel;
    this.declaredTaskParams = b.declaredTaskParams;
    this.implementedExternally = b.implementedExternally;
    this.workflowAdapterCache = b.workflowAdapterCache;
    this.transactions = b.transactions;
    this.election = b.election;
    this.delivery = b.delivery;
    this.metrics = b.metrics;
    this.startupFindings = b.startupFindings;
    this.retiredAdapters = b.retiredAdapters;
    this.conventionalResourcesLocations = b.conventionalResourcesLocations;

  }

  /**
   * A builder of {@link MigrationAdapterProperties}, empty except for the values which
   * have a default.
   *
   * @return The builder
   */
  public static MigrationAdapterProperties.MigrationAdapterPropertiesBuilder<?, ?> builder() {

    return new MigrationAdapterProperties.MigrationAdapterPropertiesBuilderImpl();

  }

  /**
   * The configuration of all adapters known (properties section
   * <code>vanillabp.adapters.&lt;id&gt;.*</code>).
   *
   * @return The value of {@link #adapters}
   */
  public Map<String, AdapterConfigProperties> getAdapters() {

    return adapters;

  }

  /**
   * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
   *
   * @return The value of {@link #resourcesLocation}
   */
  public String getResourcesLocation() {

    return resourcesLocation;

  }

  /**
   * Properties specific to workflow modules.
   *
   * @return The value of {@link #workflowModules}
   */
  public Map<String, WorkflowModuleAdapterProperties> getWorkflowModules() {

    return workflowModules;

  }

  /**
   * The settings of the extensions (properties section
   * <code>vanillabp.extensions.&lt;extension&gt;.*</code>), overridable per workflow
   * module, per workflow and per task.
   *
   * @return The value of {@link #extensions}
   */
  public Map<String, Map<String, String>> getExtensions() {

    return extensions;

  }

  /**
   * Configuration of the default {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
   * implementations (properties section <code>vanillabp.outbox</code>).
   *
   * @return The value of {@link #outbox}
   */
  public PhaseTwoOutboxProperties getOutbox() {

    return outbox;

  }

  /**
   * Refused here on purpose: the permission to share a whole workflow aggregate belongs
   * to the single workflow (see decision 66 in the repository's DECISIONS.md).
   *
   * @return The value of {@link #allowFullSyncWithBpms}
   */
  public Boolean getAllowFullSyncWithBpms() {

    return allowFullSyncWithBpms;

  }

  /**
   * Whether the expressions of every BPMN model of this application are meant as they
   * are (<code>vanillabp.accept-expressions-in-the-model</code>) - the least specific of
   * the three levels, for an application which decided it once.
   *
   * @return The value of {@link #acceptExpressionsInTheModel}
   */
  public Boolean getAcceptExpressionsInTheModel() {

    return acceptExpressionsInTheModel;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of the whole application whose type the
   * developer declared (<code>vanillabp.declared-task-params</code>).
   *
   * @return The value of {@link #declaredTaskParams}
   */
  public java.util.List<String> getDeclaredTaskParams() {

    return declaredTaskParams;

  }

  /**
   * Whether something other than this application serves every task which does not say
   * otherwise (<code>implemented-externally</code>, see {@link #implementedExternally}).
   * The least specific of the eight positions; <code>null</code> means "not configured
   * at this level".
   *
   * @return The value of {@link #implementedExternally}
   */
  public Boolean getImplementedExternally() {

    return implementedExternally;

  }

  /**
   * Configuration of the default election cache
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} (properties section
   * <code>vanillabp.workflow-adapter-cache</code>).
   *
   * @return The value of {@link #workflowAdapterCache}
   */
  public WorkflowAdapterCacheProperties getWorkflowAdapterCache() {

    return workflowAdapterCache;

  }

  /**
   * What VanillaBP does about a workflow aggregate whose store is not covered by the
   * transaction it opens (properties section <code>vanillabp.transactions</code>,
   * overridable per workflow module).
   *
   * @return The value of {@link #transactions}
   */
  public TransactionsProperties getTransactions() {

    return transactions;

  }

  /**
   * What VanillaBP does about a prioritized adapter which cannot locate workflows
   * (properties section <code>vanillabp.election</code>, overridable per workflow
   * module).
   *
   * @return The value of {@link #election}
   */
  public ElectionProperties getElection() {

    return election;

  }

  /**
   * What VanillaBP does with the records of processed task deliveries (properties
   * section <code>vanillabp.delivery</code>, overridable per workflow module - except
   * the retention, see <code>vanillabp.delivery.retention</code>).
   *
   * @return The value of {@link #delivery}
   */
  public DeliveryProperties getDelivery() {

    return delivery;

  }

  /**
   * What VanillaBP publishes as metrics (properties section
   * <code>vanillabp.metrics</code>) - today only how long the measurement of a gauge
   * which has to ask somebody is reused.
   *
   * @return The value of {@link #metrics}
   */
  public MetricsProperties getMetrics() {

    return metrics;

  }

  /**
   * Adapter ids this application USED to have and deliberately does not configure any
   * more - the last step of a BPMS migration, once nothing runs in the old BPMS any
   * more.
   *
   * @return The value of {@link #retiredAdapters}
   */
  public List<String> getRetiredAdapters() {

    return retiredAdapters;

  }

  /**
   * The base location BPMN resources of a workflow module are read from when neither an
   * adapter-specific nor the global <code>resources-location</code> is configured -
   * derived from the classpath facts by {@link #normalize(ClasspathFacts)}.
   *
   * @return The value of {@link #conventionalResourcesLocations}
   */
  public Map<String, List<String>> getConventionalResourcesLocations() {

    return conventionalResourcesLocations;

  }

  /**
   * The configuration of all adapters known (properties section
   * <code>vanillabp.adapters.&lt;id&gt;.*</code>).
   *
   * @param adapters The value of {@link #adapters}
   */
  public void setAdapters(
      final Map<String, AdapterConfigProperties> adapters) {

    this.adapters = adapters;

  }

  /**
   * Where to load VanillaBP BPMN files from, which are NOT specific to any adapter.
   *
   * @param resourcesLocation The value of {@link #resourcesLocation}
   */
  public void setResourcesLocation(
      final String resourcesLocation) {

    this.resourcesLocation = resourcesLocation;

  }

  /**
   * Properties specific to workflow modules.
   *
   * @param workflowModules The value of {@link #workflowModules}
   */
  public void setWorkflowModules(
      final Map<String, WorkflowModuleAdapterProperties> workflowModules) {

    this.workflowModules = workflowModules;

  }

  /**
   * The settings of the extensions (properties section
   * <code>vanillabp.extensions.&lt;extension&gt;.*</code>), overridable per workflow
   * module, per workflow and per task.
   *
   * @param extensions The value of {@link #extensions}
   */
  public void setExtensions(
      final Map<String, Map<String, String>> extensions) {

    this.extensions = extensions;

  }

  /**
   * Configuration of the default {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
   * implementations (properties section <code>vanillabp.outbox</code>).
   *
   * @param outbox The value of {@link #outbox}
   */
  public void setOutbox(
      final PhaseTwoOutboxProperties outbox) {

    this.outbox = outbox;

  }

  /**
   * Refused here on purpose: the permission to share a whole workflow aggregate belongs
   * to the single workflow (see decision 66 in the repository's DECISIONS.md).
   *
   * @param allowFullSyncWithBpms The value of {@link #allowFullSyncWithBpms}
   */
  public void setAllowFullSyncWithBpms(
      final Boolean allowFullSyncWithBpms) {

    this.allowFullSyncWithBpms = allowFullSyncWithBpms;

  }

  /**
   * Whether the expressions of every BPMN model of this application are meant as they
   * are (<code>vanillabp.accept-expressions-in-the-model</code>) - the least specific of
   * the three levels, for an application which decided it once.
   *
   * @param acceptExpressionsInTheModel The value of {@link #acceptExpressionsInTheModel}
   */
  public void setAcceptExpressionsInTheModel(
      final Boolean acceptExpressionsInTheModel) {

    this.acceptExpressionsInTheModel = acceptExpressionsInTheModel;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of the whole application whose type the
   * developer declared (<code>vanillabp.declared-task-params</code>).
   *
   * @param declaredTaskParams The value of {@link #declaredTaskParams}
   */
  public void setDeclaredTaskParams(
      final java.util.List<String> declaredTaskParams) {

    this.declaredTaskParams = declaredTaskParams;

  }

  /**
   * Whether something other than this application serves every task which does not say
   * otherwise (<code>implemented-externally</code>, see {@link #implementedExternally}).
   * The least specific of the eight positions; <code>null</code> means "not configured
   * at this level".
   *
   * @param implementedExternally The value of {@link #implementedExternally}
   */
  public void setImplementedExternally(
      final Boolean implementedExternally) {

    this.implementedExternally = implementedExternally;

  }

  /**
   * Configuration of the default election cache
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} (properties section
   * <code>vanillabp.workflow-adapter-cache</code>).
   *
   * @param workflowAdapterCache The value of {@link #workflowAdapterCache}
   */
  public void setWorkflowAdapterCache(
      final WorkflowAdapterCacheProperties workflowAdapterCache) {

    this.workflowAdapterCache = workflowAdapterCache;

  }

  /**
   * What VanillaBP does about a workflow aggregate whose store is not covered by the
   * transaction it opens (properties section <code>vanillabp.transactions</code>,
   * overridable per workflow module).
   *
   * @param transactions The value of {@link #transactions}
   */
  public void setTransactions(
      final TransactionsProperties transactions) {

    this.transactions = transactions;

  }

  /**
   * What VanillaBP does about a prioritized adapter which cannot locate workflows
   * (properties section <code>vanillabp.election</code>, overridable per workflow
   * module).
   *
   * @param election The value of {@link #election}
   */
  public void setElection(
      final ElectionProperties election) {

    this.election = election;

  }

  /**
   * What VanillaBP does with the records of processed task deliveries (properties
   * section <code>vanillabp.delivery</code>, overridable per workflow module - except
   * the retention, see <code>vanillabp.delivery.retention</code>).
   *
   * @param delivery The value of {@link #delivery}
   */
  public void setDelivery(
      final DeliveryProperties delivery) {

    this.delivery = delivery;

  }

  /**
   * What VanillaBP publishes as metrics (properties section
   * <code>vanillabp.metrics</code>) - today only how long the measurement of a gauge
   * which has to ask somebody is reused.
   *
   * @param metrics The value of {@link #metrics}
   */
  public void setMetrics(
      final MetricsProperties metrics) {

    this.metrics = metrics;

  }

  /**
   * Adapter ids this application USED to have and deliberately does not configure any
   * more - the last step of a BPMS migration, once nothing runs in the old BPMS any
   * more.
   *
   * @param retiredAdapters The value of {@link #retiredAdapters}
   */
  public void setRetiredAdapters(
      final List<String> retiredAdapters) {

    this.retiredAdapters = retiredAdapters;

  }

  /**
   * The base location BPMN resources of a workflow module are read from when neither an
   * adapter-specific nor the global <code>resources-location</code> is configured -
   * derived from the classpath facts by {@link #normalize(ClasspathFacts)}.
   *
   * @param conventionalResourcesLocations The value of {@link #conventionalResourcesLocations}
   */
  public void setConventionalResourcesLocations(
      final Map<String, List<String>> conventionalResourcesLocations) {

    this.conventionalResourcesLocations = conventionalResourcesLocations;

  }

}
