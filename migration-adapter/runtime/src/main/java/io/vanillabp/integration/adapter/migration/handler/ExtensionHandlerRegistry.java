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
 * The handler methods of every registered extension contract. They are kept next to the
 * <code>&#64;WorkflowTask</code> methods of the same workflow service classes, and the same
 * registration call scans them.
 * <p>
 * <b>Why the workflow services are remembered.</b> A contract may be registered before or
 * after the workflow services are scanned. On both platforms that depends on when the
 * extension's own bean is created. The extension cannot influence that and should not have
 * to think about it. So every registration of a workflow service is kept. A contract which
 * arrives later is applied to all of them. A workflow service which arrives later is
 * scanned for every contract known by then. The result is the same in either order.
 */
public class ExtensionHandlerRegistry implements ExtensionHandlers {

  /**
   * Where the wiring report goes. A report of what was wired is no finding. So it is
   * written where it is made, and not collected into the box.
   */
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(ExtensionHandlerRegistry.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId,
                             Class<? extends Annotation> annotationType) {
  }

  /**
   * A workflow service class registered for one BPMN process. It holds everything a
   * contract needs to scan it, whenever the contract arrives.
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
   * What the BPMS know about the deployed versions of the BPMN processes. The
   * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}
   * owns it and shares it. The methods of an extension are selected by version exactly like
   * VanillaBP's own.
   */
  private final ProcessVersions processVersions;

  private final Map<Class<? extends Annotation>, HandlerContract> contracts = new ConcurrentHashMap<>();

  private final List<RegisteredWorkflowService> workflowServices = new LinkedList<>();

  private final Map<RegistryKey, List<ExtensionHandlerMethod>> methods = new ConcurrentHashMap<>();

  private final Map<RegistryKey, MigrationProcessService<?>> processServices = new ConcurrentHashMap<>();

  /**
   * The hint about the second writer which a handler of an extension is. It is given while
   * the handlers are found and not later. After the boot nothing knows any more whether a
   * save is allowed.
   */
  private final SavingHandlerCheck savingHandlerCheck;

  /**
   * Where a finding of this registry goes, so that the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * What one report about the handler methods which nobody sees is about.
   */
  private record LookedOver(
                            Class<? extends Annotation> annotationType,
                            Class<?> workflowServiceClass) {
  }

  /**
   * The reports already written. A workflow service class arrives here once for each BPMN
   * process it declares. The report is about the class, so it is written once per
   * contract.
   */
  private final Set<LookedOver> classesLookedOverPerContract = ConcurrentHashMap.newKeySet();

  /**
   * The workflow modules for which the wiring report was already written. A contract which
   * is registered after its module was deployed writes its own line right away. So an
   * extension whose bean is created late is still in the report.
   */
  private final Set<String> workflowModulesReported = ConcurrentHashMap.newKeySet();

  /**
   * The methods already reported because they name a version which never arrives. This is
   * said once per method, however often the versions of a workflow module are resolved.
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
   * The names which the adapters read from the models they deployed. Only an extension asks
   * for them. That is why they are kept here and not next to the wiring. Nothing which
   * VanillaBP decides depends on a name.
   */
  private final Map<BpmnElement, String> bpmnTaskNames = new ConcurrentHashMap<>();

  /**
   * The elements which the extensions reported for the models the adapters wired. Each is
   * kept as the lookup keys a call would offer for it. Once the BPMS has reported the
   * deployed version, they are checked against it. They are kept per BPMN process and not
   * per adapter, because the extension reports them from a callback which names no
   * adapter.
   */
  private final Map<RegistryKey, Set<List<String>>> elementsOfDeployedModels = new ConcurrentHashMap<>();

  /**
   * Built by the {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}.
   * It hands over what an extension's handler needs to be treated like one of the registry's
   * own.
   * <p>
   * The version answers are shared and not built again. So one selection serves VanillaBP's
   * own handlers and those of an extension (decision 56 in the repository's DECISIONS.md).
   *
   * @param transactionRunner The platform's transaction runner. It wraps every call the
   *          same way as it wraps a workflow task
   * @param processVersions What the BPMS know about the versions of the BPMN processes
   * @param findings Where a finding of this registry is left
   */
  public ExtensionHandlerRegistry(
      final TransactionRunner transactionRunner,
      final ProcessVersions processVersions,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this.transactionRunner = transactionRunner;
    this.processVersions = processVersions;
    this.findings = findings;
    this.savingHandlerCheck = new SavingHandlerCheck(findings);

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
   * Registers a workflow service class for a BPMN process. It is called from the same place
   * where the <code>&#64;WorkflowTask</code> methods of that class are registered.
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
      // this workflow module was deployed before the contract arrived. So its line was
      // written without these methods, and the versions of the module were resolved
      // without them too
      reportWiringOf(key, registered);
      reportVersionsNobodyReports(key, registered, contract);
    }
    if (!contract.savesWorkflowAggregate()) {
      // the extension said while wiring that none of its methods writes. So there is no
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
   * Writes the report about the methods of this contract's annotation which the scan cannot
   * reach, once per annotation and class. VanillaBP writes the same report about its own
   * handler annotations, for the same reason. An extension's scan also reads only the public
   * methods of the class. So a non-public method, or an override which did not repeat the
   * annotation, is silently missing. The extension then behaves as if the application had
   * never written it.
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
      findings
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.CODE,
              "workflow service '%s'".formatted(workflowServiceClass.getName()),
              report);
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
        // overlapping version ranges are ambiguous. Ranges which do not overlap are a valid
        // way to serve several generations of a model, exactly as for @WorkflowTask
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
   * Writes what was wired for every BPMN process of a workflow module. There is one line per
   * extension, process and annotation, and it names each method and the keys it serves. The
   * core calls this once a workflow module is deployed. The <code>&#64;WorkflowTask</code>
   * side is judged at the same moment.
   * <p>
   * Everything the line names is in the registry already, so no extension is asked anything.
   * The contract knows the extension and the annotation. The registry key knows the workflow
   * module and the BPMN process. The method knows the keys it was wired to. A BPMN process
   * for which no method of an extension was found is not named. For most pairs of extension
   * and process, such a line would be about nothing. {@link HandlerMethodsNobodySees}
   * reports a method which nobody can see.
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
    // one formatted message and not placeholders. So a test on either platform reads the
    // line the way a developer does
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
   * Resolves the version tags which the methods of a workflow module name. Then it checks
   * the version ranges which name a tag for overlaps. Those could not be checked at
   * registration, because no BPMS had been asked about its versions yet. VanillaBP's own
   * handler methods go through the same second pass, from the same place.
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
   * Says at startup that a method which names versions can never run. The extension which
   * owns the annotation reports no version with its calls
   * ({@link HandlerContract.Builder#callsCarryTheProcessVersion()}). Without this, the
   * application learns it at the first event which does not arrive. And an event which does
   * not arrive looks like nothing at all.
   * <p>
   * It is a warning and does not end the boot. The method is an addition of an extension.
   * An application whose other methods serve their events has to keep running. VanillaBP
   * judges its own methods which serve no deployed version the same way.
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
        .forEach(method -> findings
            .warn(
                io.vanillabp.integration.spi.startup.StartupTopic.CODE,
                "method '%s' of process '%s' of workflow module '%s'".formatted(
                    method.describe(),
                    key.bpmnProcessId(),
                    key.workflowModuleId()),
                """
                    This %s method serves version %s, but extension '%s' reports no process version \
                    with its calls - the method never runs. Drop the version from the annotation, \
                    or ask the extension to report the version of the process its events are \
                    about."""
                    .formatted(
                        method.describeAnnotation(),
                        method.describeVersions(),
                        contract.getExtensionId())));

  }

  /**
   * The methods of the extensions registered for one BPMN process. Each comes with the
   * verdict whether its version specifications are worth keeping. The three registries of
   * the core give the same answer about their own methods. So the same startup check
   * reports a method of an extension.
   * <p>
   * A contract which reports no process version with its calls is left out.
   * {@link #reportVersionsNobodyReports} judges its methods, and saying the same thing twice
   * in two voices helps nobody.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param serves Whether a method's version specifications are worth keeping
   * @return One verdict per registered method
   */
  public List<HandlerVersions> handlerVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.function.Predicate<io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions> serves) {

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
            method.describe(), describeWithVersions(method), serves.test(method.servedVersions())))
        .toList();

  }

  /**
   * A method of an extension as a message names it: annotation, method, extension and the
   * versions it serves.
   */
  private static String describeWithVersions(
      final ExtensionHandlerMethod method) {

    return "%s method '%s' of extension '%s' (version %s)"
        .formatted(
            method.describeAnnotation(),
            method.describe(),
            method.getExtensionId(),
            method.describeVersions());

  }

  @Override
  public void elementsOfTheDeployedModel(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<List<String>> lookupKeysOfEachElement) {

    if ((annotationType == null) || (workflowModuleId == null) || (bpmnProcessId == null)) {
      throw new IllegalArgumentException(
          """
              An extension reported elements of a deployed model without naming the annotation, the \
              workflow module or the BPMN process (got '%s', '%s', '%s')! Pass all three to \
              ExtensionHandlers#elementsOfTheDeployedModel."""
              .formatted(annotationType, workflowModuleId, bpmnProcessId));
    }
    if (lookupKeysOfEachElement == null) {
      return;
    }
    final var reported = elementsOfDeployedModels
        .computeIfAbsent(
            new RegistryKey(workflowModuleId, bpmnProcessId, annotationType),
            key -> java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>()));
    lookupKeysOfEachElement
        .stream()
        .filter(java.util.Objects::nonNull)
        .map(keys -> keys
            .stream()
            .filter(key -> (key != null) && !key.isBlank())
            .toList())
        .forEach(reported::add);

  }

  /**
   * The elements which the extensions reported for one BPMN process, and which no method
   * serves in the version the BPMS gave the deployed model. Only a contract which asks for
   * this is asked ({@link HandlerContract#deployedVersionNeedsAMethod()}). See decision 127
   * in the repository's DECISIONS.md.
   * <p>
   * The methods of an element are the ones which a call with its keys may reach in some
   * version. They are picked the same way as in
   * {@link #taskParameterNames(Class, String, String, List)}. An element which none of them
   * serves is not asked about, because for an extension no method is a valid answer. For the
   * other elements, one method which covers the version is enough.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version the BPMS gave the model this start deployed
   * @param resolver Resolves version tags of that process
   * @return Each element no method serves in that version, empty where all are served
   */
  public List<io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.UnservedElement> elementsTheDeployedVersionLeavesUnserved(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final VersionRange.ProcessVersionResolver resolver) {

    final var unserved = new LinkedList<io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.UnservedElement>();
    elementsOfDeployedModels
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().workflowModuleId().equals(workflowModuleId))
        .filter(entry -> entry.getKey().bpmnProcessId().equals(bpmnProcessId))
        // the same order on every start, so that the message reads the same
        .sorted(java.util.Comparator.comparing(entry -> entry.getKey().annotationType().getName()))
        .forEach(entry -> {
          final var contract = contracts.get(entry.getKey().annotationType());
          if ((contract == null) || !contract.deployedVersionNeedsAMethod()) {
            return;
          }
          final List<List<String>> elements;
          synchronized (entry.getValue()) {
            elements = List.copyOf(entry.getValue());
          }
          elements
              .forEach(lookupKeys -> {
                final var mayRun = methodsWhichMayRun(
                    contract.getAnnotationType(), workflowModuleId, bpmnProcessId, lookupKeys);
                if (mayRun.isEmpty() || mayRun.stream().anyMatch(method -> method.matchesVersion(version, resolver))) {
                  return;
                }
                unserved
                    .add(
                        new io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.UnservedElement(
                            describeElement(lookupKeys), mayRun.stream()
                                .map(ExtensionHandlerRegistry::describeWithVersions).distinct()
                                .toList(), consequenceOf(contract)));
              });
        });
    return unserved;

  }

  /**
   * An element as the message which ends the start names it: by its first key, which is the
   * one an extension prefers, and by the other keys where there are any.
   */
  private static String describeElement(
      final List<String> lookupKeys) {

    if (lookupKeys.isEmpty()) {
      return "the process itself";
    }
    if (lookupKeys.size() == 1) {
      return "element '%s'".formatted(lookupKeys.getFirst());
    }
    return "element '%s' (also matched by %s)"
        .formatted(
            lookupKeys.getFirst(),
            lookupKeys
                .subList(1, lookupKeys.size())
                .stream()
                .map("'%s'"::formatted)
                .collect(java.util.stream.Collectors.joining(", ")));

  }

  /**
   * What an element without a method means for the extension. Where the extension gave its
   * own words, they are used.
   */
  private static String consequenceOf(
      final HandlerContract contract) {

    final var consequence = contract.getConsequenceOfAnElementWithoutAMethod() == null
        ? "It finds no method to call for such an element and goes on without one."
        : contract.getConsequenceOfAnElementWithoutAMethod();
    return "For @%s of extension '%s': %s"
        .formatted(contract.getAnnotationType().getSimpleName(), contract.getExtensionId(), consequence);

  }

  /**
   * Keeps the BPMN names of the tasks which an adapter just wired, so that an extension can
   * ask for one. It is called while <code>wireBpmn</code> runs, for every process whose
   * wiring an adapter checks. That includes the processes which no
   * <code>&#64;WorkflowService</code> claims, because an extension may have something to say
   * about those too.
   * <p>
   * A task without a name is not remembered. Where two adapters of a migration deploy the
   * same process, the name from the adapter which wires last is the answer. Both are
   * intended. The name is a label, and a label which VanillaBP guesses is worse than no
   * label for an extension.
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
  public Collection<String> taskParameterNames(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    requireContract(annotationType);
    return namesReadBy(methodsWhichMayRun(annotationType, workflowModuleId, bpmnProcessId, lookupKeys));

  }

  /**
   * What the methods of EVERY registered contract read for one element: the union of
   * {@link #taskParameterNames(Class, String, String, List)} over all of them. An adapter
   * asks this when its BPMS hands a task to one channel only. Then that channel has to fetch
   * what every extension reads as well (see decision 122 in the repository's
   * DECISIONS.md).
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param lookupKeys The keys a method may be matched by, most wanted first
   * @return The parameter names, sorted and without duplicates. Empty where no contract is
   *         registered, or where no method of any contract reads a variable here
   */
  public List<String> taskParameterNamesOfEveryContract(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    // each contract is walked on its own. The keys of one extension never decide which
    // method of another extension runs
    return namesReadBy(contracts
        .keySet()
        .stream()
        .flatMap(annotationType -> methodsWhichMayRun(annotationType, workflowModuleId, bpmnProcessId, lookupKeys)
            .stream())
        .toList());

  }

  private List<ExtensionHandlerMethod> methodsWhichMayRun(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    final var registered = methods.get(new RegistryKey(workflowModuleId, bpmnProcessId, annotationType));
    if (registered == null) {
      return List.of();
    }
    final var mayRun = new LinkedList<ExtensionHandlerMethod>();
    synchronized (registered) {
      // the walk of find, done for every version at once. A key reaches its methods only in
      // the versions which no earlier key serves, and the catch-all only where no key does.
      // So the walk stops at the first method which serves every version, because nothing
      // after it ever runs for this element (see decision 121 in the repository's
      // DECISIONS.md)
      for (final var lookupKey : lookupKeys) {
        final var serving = registered
            .stream()
            .filter(method -> method.serves(lookupKey))
            .toList();
        mayRun.addAll(serving);
        if (serving.stream().anyMatch(ExtensionHandlerMethod::servesEveryVersion)) {
          return mayRun;
        }
      }
      registered
          .stream()
          .filter(ExtensionHandlerMethod::servesEveryKey)
          .forEach(mayRun::add);
    }
    return mayRun;

  }

  /**
   * The UNION of what the methods read, because a delivery has to satisfy whichever of them
   * runs. It is sorted, because a subscription which names variables is compared with itself
   * across restarts.
   */
  private static List<String> namesReadBy(
      final Collection<ExtensionHandlerMethod> methods) {

    return methods
        .stream()
        .map(ExtensionHandlerMethod::getTaskParameterNames)
        .flatMap(List::stream)
        .distinct()
        .sorted()
        .toList();

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
            // an extension calls in from wherever it was called itself, and it cannot know
            // that in advance. So the handler joins a transaction which is open, and gets one
            // of its own only where none runs
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

    // a contract which says it never writes wins over the call. The call's default is to
    // save, so a caller of such a contract cannot say anything else, and nothing is
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
   * The method which serves this call, or <code>null</code>.
   * <p>
   * The keys are walked in the order the caller offered them. All methods are asked about
   * one key before the next key is tried. So the first key which some method serves decides.
   * That lets an extension say that the element id wins over the task definition. Walking
   * the methods first would let the order of the class scan decide instead (see decision 51
   * in the repository's DECISIONS.md). Only where no key is named at all does the method run
   * which serves every key of the process.
   * <p>
   * A method whose versions do not cover the version of the call is skipped, as if it did not
   * serve the key. So two methods for two generations of a model can stand side by side. A
   * call which names no version reaches the methods which name none. For a contract which
   * says nothing about versions, that is every method.
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
      // the catch-all runs where nothing more specific exists
      return registered
          .stream()
          .filter(ExtensionHandlerMethod::servesEveryKey)
          .filter(method -> method.matchesVersion(processVersion, resolver))
          .findFirst()
          .orElse(null);
    }

  }

}
