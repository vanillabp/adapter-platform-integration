package io.vanillabp.integration.adapter.migration.handler;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.migration.transaction.SavingHandlerCheck;
import io.vanillabp.integration.adapter.migration.transaction.TransactionForm;
import io.vanillabp.integration.adapter.migration.workflowtask.HandlerMethodsNobodySees;
import io.vanillabp.integration.adapter.migration.workflowtask.HandlerVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.ProcessVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The handler methods of every registered extension contract, kept next to the
 * <code>&#64;WorkflowTask</code> methods of the same workflow service classes and
 * scanned from the same registration call.
 * <p>
 * <b>Why the workflow services are remembered.</b> A contract may be registered before
 * or after the workflow services are scanned - on both platforms that depends on when
 * the extension's own bean is created, which the extension cannot influence and should
 * not have to reason about. So every registration of a workflow service is kept, a
 * contract arriving later is applied to all of them, and a workflow service arriving
 * later is scanned for every contract known by then. Whichever order the two happen in,
 * the result is the same.
 */
public class ExtensionHandlerRegistry implements ExtensionHandlers {

  private static final Logger log = LoggerFactory.getLogger(ExtensionHandlerRegistry.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId,
                             Class<? extends Annotation> annotationType) {
  }

  /**
   * A workflow service class registered for one BPMN process - everything a contract
   * needs to scan it, whenever it arrives.
   */
  private record RegisteredWorkflowService(
                                           String workflowModuleId,
                                           String bpmnProcessId,
                                           Class<?> workflowServiceClass,
                                           Class<?> workflowAggregateClass,
                                           Supplier<Object> workflowServiceBean,
                                           Function<Class<?>, Object> beanResolver,
                                           MigrationProcessService<?> processService) {
  }

  private final TransactionRunner transactionRunner;

  /**
   * What the BPMS know about the deployed versions of the BPMN processes - owned by the
   * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}
   * and shared, because the methods of an extension are selected by version exactly like
   * VanillaBP's own.
   */
  private final ProcessVersions processVersions;

  private final Map<Class<? extends Annotation>, HandlerContract> contracts = new ConcurrentHashMap<>();

  private final List<RegisteredWorkflowService> workflowServices = new LinkedList<>();

  private final Map<RegistryKey, List<ExtensionHandlerMethod>> methods = new ConcurrentHashMap<>();

  private final Map<RegistryKey, MigrationProcessService<?>> processServices = new ConcurrentHashMap<>();

  /**
   * The hint about the second writer a handler of an extension is, given while the
   * handlers are found rather than later: nothing after the boot knows any more whether a
   * save is allowed.
   */
  private final SavingHandlerCheck savingHandlerCheck = new SavingHandlerCheck();

  /**
   * What one report about the handler methods nobody sees is about.
   */
  private record LookedOver(
                            Class<? extends Annotation> annotationType,
                            Class<?> workflowServiceClass) {
  }

  /**
   * The reports already written. A workflow service class arrives here once per BPMN
   * process it declares and the report is about the class, so it is written once per
   * contract.
   */
  private final Set<LookedOver> classesLookedOverPerContract = ConcurrentHashMap.newKeySet();

  /**
   * The workflow modules the report about what was wired was already written for. A
   * contract registered after its module was deployed writes its own line right away, so
   * an extension whose bean is created late is not left out of the report.
   */
  private final Set<String> workflowModulesReported = ConcurrentHashMap.newKeySet();

  /**
   * The methods already reported as naming a version which never arrives - said once per
   * method, however often the versions of a workflow module are resolved.
   */
  private final Set<String> methodsReportedAsUnreachable = ConcurrentHashMap.newKeySet();

  /**
   * One element of one BPMN process of one workflow module.
   */
  private record BpmnElement(
                             String workflowModuleId,
                             String bpmnProcessId,
                             String activityId) {
  }

  /**
   * The names the adapters read off the models they deployed. Only an extension asks for
   * them, which is why they are kept here rather than next to the wiring: nothing
   * VanillaBP decides depends on a name.
   */
  private final Map<BpmnElement, String> bpmnTaskNames = new ConcurrentHashMap<>();

  /**
   * @param transactionRunner The platform's transaction runner, which wraps every
   *          invocation the way it wraps a workflow task
   * @param processVersions What the BPMS know about the versions of the BPMN processes
   */
  public ExtensionHandlerRegistry(
      final TransactionRunner transactionRunner,
      final ProcessVersions processVersions) {

    this.transactionRunner = transactionRunner;
    this.processVersions = processVersions;

  }

  @Override
  public void register(
      final HandlerContract contract) {

    final var previous = contracts.putIfAbsent(contract.getAnnotationType(), contract);
    if (previous != null) {
      throw new IllegalStateException(
          """
              The annotation '@%s' is claimed by extension '%s' and by extension '%s'! An annotation \
              belongs to exactly one extension - the second registration would silently replace the \
              first, so the boot ends here instead."""
              .formatted(
                  contract.getAnnotationType().getSimpleName(),
                  previous.getExtensionId(),
                  contract.getExtensionId()));
    }
    synchronized (workflowServices) {
      workflowServices.forEach(service -> scan(contract, service));
    }

  }

  /**
   * Registers a workflow service class for a BPMN process - called from the same place
   * the <code>&#64;WorkflowTask</code> methods of that class are registered.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowServiceBean Supplies the bean instance of that class
   * @param beanResolver Resolves beans by class
   * @param processService The process service of the BPMN process
   */
  public void registerWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final MigrationProcessService<?> processService) {

    final var service = new RegisteredWorkflowService(
        workflowModuleId, bpmnProcessId, workflowServiceClass, processService
            .getWorkflowAggregateClass(), workflowServiceBean, beanResolver, processService);
    synchronized (workflowServices) {
      workflowServices.add(service);
      contracts
          .values()
          .forEach(contract -> scan(contract, service));
    }

  }

  private void scan(
      final HandlerContract contract,
      final RegisteredWorkflowService service) {

    reportHandlerMethodsNobodySees(contract, service.workflowServiceClass());

    final var found = ExtensionHandlerScanner
        .scan(
            contract,
            service.workflowServiceClass(),
            service.workflowAggregateClass(),
            service.workflowServiceBean(),
            service.beanResolver());
    final var key = new RegistryKey(service.workflowModuleId(), service.bpmnProcessId(), contract.getAnnotationType());
    processServices.putIfAbsent(key, service.processService());
    if (found.isEmpty()) {
      return;
    }
    final var registered = methods.computeIfAbsent(key, ignored -> new LinkedList<>());
    synchronized (registered) {
      found
          .forEach(method -> {
            failOnDuplicateWiring(
                contract,
                service.workflowModuleId(),
                service.bpmnProcessId(),
                registered,
                method,
                VersionRange.NO_RESOLVER);
            registered.add(method);
          });
    }
    if (workflowModulesReported.contains(service.workflowModuleId())) {
      // this workflow module was deployed before the contract arrived, so its line was
      // written without these methods, and the versions of the module were resolved
      // without them too
      reportWiringOf(key, registered);
      reportVersionsNobodyReports(key, registered, contract);
    }
    if (!contract.savesWorkflowAggregate()) {
      // the extension said while wiring that none of its methods writes, so there is no
      // second writer to warn about (see decision 50 in the repository's DECISIONS.md)
      return;
    }
    savingHandlerCheck
        .reportHandlersWhichMaySave(
            service.workflowModuleId(),
            service.bpmnProcessId(),
            service.workflowAggregateClass(),
            (service.processService() != null) && service
                .processService()
                .detectsConcurrentModification(),
            contract.getExtensionId(),
            contract.getAnnotationType());

  }

  /**
   * Writes the report about the methods of this contract's annotation which the scan
   * cannot reach, once per (annotation, class). It is the same report VanillaBP writes
   * about its own handler annotations, for the same reason: an extension's scan reads
   * the public methods of the class too, so a non-public method or an override which
   * repeated no annotation is silently not there, and the extension then behaves as if
   * the application had never written it.
   */
  private void reportHandlerMethodsNobodySees(
      final HandlerContract contract,
      final Class<?> workflowServiceClass) {

    if (!classesLookedOverPerContract.add(new LookedOver(contract.getAnnotationType(), workflowServiceClass))) {
      return;
    }
    final var report = HandlerMethodsNobodySees
        .reportFor(workflowServiceClass, List.of(contract.getAnnotationType()));
    if (report != null) {
      log.warn(report);
    }

  }

  private static void failOnDuplicateWiring(
      final HandlerContract contract,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<ExtensionHandlerMethod> registered,
      final ExtensionHandlerMethod method,
      final VersionRange.ProcessVersionResolver resolver) {

    registered
        .stream()
        .filter(existing -> existing != method)
        .filter(existing -> existing.overlaps(method))
        // overlapping version ranges are ambiguous; disjoint ones are a legitimate way
        // to serve several generations of a model, exactly as for @WorkflowTask
        .filter(existing -> existing.overlapsVersions(method, resolver))
        .findFirst()
        .ifPresent(existing -> {
          throw new IllegalStateException(
              """
                  The @%s methods '%s' (%s, version %s) and '%s' (%s, version %s) of extension '%s' \
                  both serve BPMN process '%s' of workflow module '%s'! Which of them is meant cannot \
                  be guessed - remove one of them, name what each of them serves, or distinguish them \
                  by the versions they serve."""
                  .formatted(
                      contract.getAnnotationType().getSimpleName(),
                      existing.describe(),
                      existing.describeWiring(),
                      existing.describeVersions(),
                      method.describe(),
                      method.describeWiring(),
                      method.describeVersions(),
                      contract.getExtensionId(),
                      bpmnProcessId,
                      workflowModuleId));
        });

  }

  /**
   * Writes what was wired for every BPMN process of a workflow module, one line per
   * extension, process and annotation, naming each method and the keys it serves. The core
   * calls it once a workflow module is deployed, which is the moment the
   * <code>&#64;WorkflowTask</code> side is judged at as well.
   * <p>
   * Everything the line names is in the registry already, so an extension is asked for
   * nothing: the contract knows the extension and the annotation, the registry key knows
   * the workflow module and the BPMN process, and the method knows the keys it was wired
   * to. A BPMN process no method of an extension was found for is not named, because a
   * line for every pair of extension and process would be a line about nothing for most of
   * them; a method nobody can see is reported by {@link HandlerMethodsNobodySees} instead.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  public void reportWiring(
      final String workflowModuleId) {

    workflowModulesReported.add(workflowModuleId);
    methods
        .keySet()
        .stream()
        .filter(key -> key.workflowModuleId().equals(workflowModuleId))
        .sorted(
            java.util.Comparator
                .comparing(RegistryKey::bpmnProcessId)
                .thenComparing(key -> key.annotationType().getSimpleName()))
        .forEach(key -> reportWiringOf(key, methods.get(key)));

  }

  private void reportWiringOf(
      final RegistryKey key,
      final List<ExtensionHandlerMethod> registered) {

    final String extensionId;
    final String wiring;
    synchronized (registered) {
      if (registered.isEmpty()) {
        return;
      }
      extensionId = registered
          .getFirst()
          .getExtensionId();
      wiring = registered
          .stream()
          .map(method -> "'%s' serves %s%s"
              .formatted(
                  method.describe(),
                  method.describeWiring(),
                  method.servesEveryVersion()
                      ? ""
                      : " of version %s".formatted(method.describeVersions())))
          .collect(java.util.stream.Collectors.joining("; "));
    }
    // one formatted message rather than placeholders, so a test on either platform reads
    // the line the way a developer does
    log
        .info(
            """
                Extension '%s' serves BPMN process '%s' of workflow module '%s' with these @%s \
                methods: %s. Of the keys an invocation offers, the first one a method serves wins; a \
                method serving every element runs where none of them is served."""
                .formatted(
                    extensionId,
                    key.bpmnProcessId(),
                    key.workflowModuleId(),
                    key.annotationType().getSimpleName(),
                    wiring));

  }

  /**
   * Resolves the version tags the methods of a workflow module name and checks the
   * version ranges naming a tag for overlaps - those could not be placed while
   * registering, since no BPMS had been asked about its versions at that point. The same
   * second pass VanillaBP's own handler methods go through, run from the same place.
   *
   * @param workflowModuleId The workflow module ID
   */
  public void resolveProcessVersions(
      final String workflowModuleId) {

    methods
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .forEach(entry -> {
          final var key = entry.getKey();
          final var registered = entry.getValue();
          final var contract = contracts.get(key.annotationType());
          if (contract == null) {
            return;
          }
          reportVersionsNobodyReports(key, registered, contract);
          synchronized (registered) {
            if (registered.stream().allMatch(method -> method.versionTags().isEmpty())) {
              return;
            }
            processVersions.warmUp(workflowModuleId, key.bpmnProcessId());
            final var resolver = processVersions.resolverFor(workflowModuleId, key.bpmnProcessId());
            registered
                .forEach(method -> method
                    .versionTags()
                    .forEach(tag -> processVersions
                        .reportUnknownVersionTag(
                            workflowModuleId,
                            key.bpmnProcessId(),
                            tag,
                            "method '%s' of extension '%s'".formatted(method.describe(), contract.getExtensionId()))));
            registered
                .forEach(method -> failOnDuplicateWiring(
                    contract,
                    workflowModuleId,
                    key.bpmnProcessId(),
                    registered,
                    method,
                    resolver));
          }
        });

  }

  /**
   * Says at startup that a method naming versions can never run, because the extension
   * owning the annotation reports no version with its calls
   * ({@link HandlerContract.Builder#callsCarryTheProcessVersion()}). Without this the
   * application learns it at the first event which does not arrive, and an event which
   * does not arrive looks like nothing at all.
   * <p>
   * A warning rather than the end of the boot: what the method does is an addition of an
   * extension, and an application whose other methods serve their events has to keep
   * running. The same reading VanillaBP applies to its own methods which serve no
   * deployed version.
   */
  private void reportVersionsNobodyReports(
      final RegistryKey key,
      final List<ExtensionHandlerMethod> registered,
      final HandlerContract contract) {

    if (contract.callsCarryTheProcessVersion()) {
      return;
    }
    final List<ExtensionHandlerMethod> naming;
    synchronized (registered) {
      naming = registered
          .stream()
          .filter(method -> !method.servesEveryVersion())
          .toList();
    }
    naming
        .stream()
        .filter(method -> methodsReportedAsUnreachable
            .add("%s|%s|%s|%s"
                .formatted(
                    key.workflowModuleId(),
                    key.bpmnProcessId(),
                    key.annotationType().getName(),
                    method.describe())))
        .forEach(method -> log
            .warn(
                """
                    The {} method '{}' of BPMN process '{}' (workflow module '{}') serves version {}, \
                    but extension '{}' reports no process version with its calls - the method never \
                    runs. Drop the version from the annotation, or ask the extension to report the \
                    version of the process its events are about.""",
                method.describeAnnotation(),
                method.describe(),
                key.bpmnProcessId(),
                key.workflowModuleId(),
                method.describeVersions(),
                contract.getExtensionId()));

  }

  /**
   * The methods of the extensions registered for one BPMN process, each with the verdict
   * whether it serves one of the given versions - the same answer the three registries of
   * the core give about their own methods, so a method of an extension which serves no
   * version the BPMS holds is reported by the same startup check.
   * <p>
   * A contract which reports no process version with its calls is left out: its methods
   * are judged by {@link #reportVersionsNobodyReports}, and saying the same thing twice
   * in two voices helps nobody.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param servableVersions The versions worth serving
   * @param resolver Resolves version tags of that process
   * @return One verdict per registered method
   */
  public List<HandlerVersions> handlerVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> servableVersions,
      final VersionRange.ProcessVersionResolver resolver) {

    return methods
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .filter(entry -> entry.getKey().bpmnProcessId().equals(bpmnProcessId))
        .filter(entry -> {
          final var contract = contracts.get(entry.getKey().annotationType());
          return (contract != null) && contract.callsCarryTheProcessVersion();
        })
        .flatMap(entry -> List.copyOf(entry.getValue()).stream())
        .map(method -> new HandlerVersions(
            method.describe(), "%s method '%s' of extension '%s' (version %s)"
                .formatted(
                    method.describeAnnotation(),
                    method.describe(),
                    method.getExtensionId(),
                    method.describeVersions()), servableVersions
                        .stream()
                        .anyMatch(version -> method.matchesVersion(version, resolver))))
        .toList();

  }

  /**
   * Keeps the BPMN names of the tasks an adapter just wired, so an extension can ask for
   * one. Called while <code>wireBpmn</code> runs, for every process an adapter validates
   * the wiring of - the ones no <code>&#64;WorkflowService</code> claims included, since
   * an extension may well have something to say about those too.
   * <p>
   * A task carrying no name is not remembered, and where two adapters of a migration
   * deploy the same process the name of the one wiring last is the answer. Both are
   * deliberate: the name is a label, and a label VanillaBP guesses at is worse than one
   * an extension has to do without.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param tasks The tasks the adapter read out of the model
   */
  public void rememberBpmnTaskNames(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmnTaskSpec> tasks) {

    tasks
        .stream()
        .filter(task -> (task.name() != null) && !task.name().isBlank())
        .forEach(task -> bpmnTaskNames
            .put(new BpmnElement(workflowModuleId, bpmnProcessId, task.activityId()), task.name()));

  }

  @Override
  public Optional<String> bpmnTaskNameOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String activityId) {

    return Optional
        .ofNullable(bpmnTaskNames.get(new BpmnElement(workflowModuleId, bpmnProcessId, activityId)));

  }

  @Override
  public Optional<Class<?>> workflowAggregateOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    synchronized (workflowServices) {
      return workflowServices
          .stream()
          .filter(service -> service.workflowModuleId().equals(workflowModuleId))
          .filter(service -> service.bpmnProcessId().equals(bpmnProcessId))
          .map(RegisteredWorkflowService::workflowAggregateClass)
          .findFirst();
    }

  }

  @Override
  public List<String> bpmnProcessesOf(
      final String workflowModuleId) {

    synchronized (workflowServices) {
      return workflowServices
          .stream()
          .filter(service -> service.workflowModuleId().equals(workflowModuleId))
          .map(RegisteredWorkflowService::bpmnProcessId)
          .distinct()
          .toList();
    }

  }

  @Override
  public boolean hasHandler(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys,
      final String processVersion) {

    requireContract(annotationType);
    return find(annotationType, workflowModuleId, bpmnProcessId, lookupKeys, processVersion) != null;

  }

  @Override
  public Optional<Object> invoke(
      final HandlerCall call) {

    final var contract = requireContract(call.getAnnotationType());
    final var method = find(
        call.getAnnotationType(),
        call.getWorkflowModuleId(),
        call.getBpmnProcessId(),
        call.getLookupKeys(),
        call.getProcessVersion());
    if (method == null) {
      return Optional.empty();
    }

    final var processService = processServices
        .get(
            new RegistryKey(call.getWorkflowModuleId(), call.getBpmnProcessId(), call.getAnnotationType()));
    final var returned = AggregateWrite
        .inTransaction(
            transactionRunner,
            // an extension calls in from wherever it was called itself, which it cannot
            // know in advance - so the handler takes part in a transaction which is open
            // and gets one of its own only where nothing runs
            TransactionForm.CURRENT_OR_NEW,
            call.getWorkflowModuleId(),
            call.getBpmnProcessId(),
            call.getWorkflowAggregateId(),
            "the @%s notification of extension '%s'"
                .formatted(contract.getAnnotationType().getSimpleName(), contract.getExtensionId()),
            () -> run(contract, method, call, processService));

    return contract.deliversReturnValue()
        ? Optional.ofNullable(returned)
        : Optional.empty();

  }

  private Object run(
      final HandlerContract contract,
      final ExtensionHandlerMethod method,
      final HandlerCall call,
      final MigrationProcessService<?> processService) {

    final var workflowAggregate = call.isWorkflowAggregateProvided()
        ? call.getWorkflowAggregate()
        : loadWorkflowAggregate(contract, call, processService);

    final var context = HandlerContexts
        .of(
            workflowAggregate,
            call.getPayload(),
            name -> call.getVariables().get(name),
            call::getMultiInstances);
    final var returned = method.invoke(context);

    // a contract which says it never writes outranks the call: the call's default is to
    // save, so a caller of such a contract cannot say anything else and nothing is
    // refused here
    if (call.savesWorkflowAggregate() && contract.savesWorkflowAggregate() && (workflowAggregate != null)) {
      saveWorkflowAggregate(processService, workflowAggregate);
    }
    return returned;

  }

  private static Object loadWorkflowAggregate(
      final HandlerContract contract,
      final HandlerCall call,
      final MigrationProcessService<?> processService) {

    if (processService == null) {
      throw new IllegalStateException(
          """
              Extension '%s' asks for the workflow aggregate '%s' of BPMN process '%s' of workflow \
              module '%s', but no @WorkflowService of this application declares that process! Check \
              the workflow module and the BPMN process id the extension passes."""
              .formatted(
                  contract.getExtensionId(),
                  call.getWorkflowAggregateId(),
                  call.getBpmnProcessId(),
                  call.getWorkflowModuleId()));
    }
    final var workflowAggregate = processService
        .loadWorkflowAggregateById(processService.convertAggregateId(String.valueOf(call.getWorkflowAggregateId())));
    if (workflowAggregate == null) {
      throw new IllegalStateException(
          """
              No workflow aggregate '%s' of BPMN process '%s' of workflow module '%s' exists, but \
              extension '%s' has a notification about it! Either the workflow's aggregate was deleted \
              or the extension names an id which was never one."""
              .formatted(
                  call.getWorkflowAggregateId(),
                  call.getBpmnProcessId(),
                  call.getWorkflowModuleId(),
                  contract.getExtensionId()));
    }
    return workflowAggregate;

  }

  @SuppressWarnings("unchecked")
  private static <A> void saveWorkflowAggregate(
      final MigrationProcessService<A> processService,
      final Object workflowAggregate) {

    processService.saveWorkflowAggregate((A) workflowAggregate);

  }

  private HandlerContract requireContract(
      final Class<? extends Annotation> annotationType) {

    final var contract = contracts.get(annotationType);
    if (contract == null) {
      throw new IllegalStateException(
          """
              No extension registered a handler contract for the annotation '@%s'! Register it \
              through ExtensionHandlers#register before using it. Registered annotations: %s."""
              .formatted(
                  annotationType.getSimpleName(),
                  contracts.isEmpty()
                      ? "none"
                      : contracts
                          .keySet()
                          .stream()
                          .map(Class::getSimpleName)
                          .map("@%s"::formatted)
                          .sorted()
                          .collect(java.util.stream.Collectors.joining(", "))));
    }
    return contract;

  }

  /**
   * The method serving this call, or <code>null</code>.
   * <p>
   * The keys are walked in the order the caller offered them, and the methods are asked
   * about one key before the next key is tried. So the first key somebody serves decides,
   * which is what lets an extension say that the element id outranks the task definition;
   * walking the methods first would let the order of the class scan decide instead (see
   * decision 51 in the repository's DECISIONS.md). Only where no key is named at all does
   * the method serving every key of the process run.
   * <p>
   * A method whose versions do not cover the version of the call is passed over as if it
   * did not serve the key, so two methods for two generations of a model stand next to
   * each other. A call naming no version reaches the methods naming none, which is every
   * method of a contract saying nothing about versions.
   */
  private ExtensionHandlerMethod find(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys,
      final String processVersion) {

    final var registered = methods.get(new RegistryKey(workflowModuleId, bpmnProcessId, annotationType));
    if (registered == null) {
      return null;
    }
    final var resolver = processVersions.resolverFor(workflowModuleId, bpmnProcessId);
    synchronized (registered) {
      for (final var lookupKey : lookupKeys) {
        final var serving = registered
            .stream()
            .filter(method -> method.serves(lookupKey))
            .filter(method -> method.matchesVersion(processVersion, resolver))
            .findFirst();
        if (serving.isPresent()) {
          return serving.get();
        }
      }
      // the catch-all is what runs where nothing more specific exists
      return registered
          .stream()
          .filter(ExtensionHandlerMethod::servesEveryKey)
          .filter(method -> method.matchesVersion(processVersion, resolver))
          .findFirst()
          .orElse(null);
    }

  }

}
