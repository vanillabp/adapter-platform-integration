![Header](../readme/vanillabp-headline.png)

# Quarkus integration

In Quarkus one has to use Quarkus extensions to bring new functionality into a
Quarkus project (see https://quarkus.io/extensions/). As this is the best developer
experience also Quarkus platform integration of VanillaBP is done leveraging
Quarkus' extensions mechanism.

There is one main extension `vanillabp` (implemented
by this module) which is primarily responsible for two things:

1. Bringing the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) in Quarkus to life.
2. Managing of VanillaBP adapters at build and runtime connecting to BPMSs.

Additionally, there are VanillaBP adapter extensions which need be added next to the
`vanillabp` extension to provide runtime connectivity to supported BPMSs. There is one
[dummy adapter](./dummy-adapter) as a template for new adapters also providing the
documentation what needs to be considered on providing a new VanillaBP
adapter extension. For ready-to-use adapter extensions checkout
[https://www.vanillabp.io](https://www.vanillabp.io).

## Modules

To understand subsequent documentation, read the Quarkus guide
"[Writing your own extension](https://quarkus.io/guides/writing-extensions)"
to learn about concepts of Quarkus extensions.

1. **[deployment](./deployment):**<br>
   The deployment module of the extension. It is responsible for code analysis,
   loading configuration and preparing runtime CDI beans.
2. **[runtime](./runtime):**<br>
   The runtime module of the extension. It is responsible for bridging to
   [VanillaBP migration adapter](../migration-adapter) at runtime.
3. **[quarkus-support](./quarkus-support):**<br>
   A tiny collection of useful things in the context of Quarkus to be used as
   a dependency instead of `io.vanillabp:spi-for-java`.
4. **[integration-tests](./integration-tests):**<br>
   Modules which ensure the VanillaBP Quarkus extension works as documented.

## Implementation concepts

In contrast to Spring Boot (runtime reflection), the Quarkus integration does as much
as possible at **build time**, following Quarkus' extension philosophy:

1. **Code analysis via Jandex:** `@WorkflowService` classes and
   `AggregatePersistenceAware` implementations are found in the Jandex index
   (`ProcessServiceBuildStepProcessor`). That is why workflow sub-modules containing
   such classes must be indexed using the `jandex-maven-plugin`. The Jandex index is
   *not* needed for workflow-module detection itself: the `META-INF/workflow-module`
   descriptor is registered as an additional application-archive marker
   (`AdditionalApplicationArchiveMarkerBuildItem`), so JARs containing only the
   descriptor and BPMS resources (or JARs built without the plugin, e.g. by Gradle)
   are detected as well.
2. **Bean generation via Gizmo:** For each workflow aggregate a
   `ProcessService_<Aggregate>` CDI bean class extending `ProcessServiceBaseCdiBean<A>`
   is generated as bytecode at build time — the Quarkus counterpart of Spring's
   `BeanDefinition` registration. The runtime base class bridges to the
   [migration adapter](../migration-adapter)'s `MigrationProcessService`.
3. **Workflow module detection:** `WorkflowModuleBuildStepProcessor` scans all
   application archives for `META-INF/workflow-module` marker files (content = workflow
   module ID); the root archive acts as the *global* module fallback.
4. **Workflow-module-specific configuration:** Config sources for
   `<module-id>[-<profile>].properties/.yaml` files are generated as `ConfigBuilder`
   classes with config ordinals slightly above `application.*` (properties: 251,
   YAML: 256), so module properties take precedence. The files are also registered for
   dev-mode hot reload.
5. **Aggregate persistence:** Unlike Spring Boot there is *no* generic fallback —
   Quarkus has no single persistence idiom. A CDI bean implementing
   `AggregatePersistenceAware` (from [quarkus-support](./quarkus-support)) is required
   per aggregate; the most specific generic type wins (`AggregatePersistenceResolver`,
   Jandex-based).
6. **Validation at build time:** `EnsureCollectedClassesAreBeansBuildStepProcessor`
   fails the build if collected classes (workflow services, persistence
   implementations) are not actual CDI beans.
7. **Configuration binding:** the user-facing `vanillabp.*` tree is modeled ONCE in
   the platform-neutral core (`MigrationAdapterProperties`).
   `QuarkusMigrationAdapterProperties` stays a RUN_TIME `@ConfigMapping` (module
   config files and env overrides need the runtime config), and the transformer
   copies it onto the core model via the GENERATED MapStruct mapper
   `QuarkusMigrationAdapterPropertiesMapper` (`unmappedSourcePolicy`/
   `unmappedTargetPolicy = ERROR` pins the mapping at compile time; the SmallRye
   interface's fluent accessors are made visible to MapStruct by the reactor
   artifact `vanillabp-mapstruct-fluent-accessors` on the annotation-processor path
   — build with `install`, not `package`). Defaulting and ALL guiding validation
   run in the core; the transformer keeps only the capability checks.
   Adapter extensions contribute their own keys to the shared tree via an
   adapter-owned RUN_TIME `@ConfigRoot @ConfigMapping(prefix = "vanillabp")`
   overlay (reference: the dummy adapter's `DummyAdapterOverlayProperties`).
   There is deliberately NO blanket `withMappingIgnore("vanillabp.**")`: SmallRye's
   unknown-key validation passes if ANY registered mapping knows a key, so the
   platform mapping and the adapter overlays validate the tree together and a typo
   under `vanillabp.*` fails the startup (Quarkus is stricter than Spring Boot
   here - accepted).
8. **Runtime deployment pipeline:** `VanillaBpDeploymentRunner` (runtime module)
   observes the `StartupEvent` and drives the core `DeploymentService` for every
   workflow module found in the classpath:
   `readBpmn → prepareBpmn → wireBpmn → deployResources → startWorkflowProcessing`,
   honoring the `deployment-failure` policy and wiring extensions ordered by
   `getOrder()` - the counterpart of Spring Boot's `SpringBootDeploymentService`.
   Details:
   - **BPMN index:** `resources-location` is RUN_TIME configuration and a fast-jar
     cannot pattern-scan `**/*.bpmn` at runtime, so all `.bpmn` resource paths of
     all application archives are indexed at build time
     (`DeploymentPipelineBuildStepProcessor`) and recorded as the synthetic
     `BpmnResourceIndex` bean, filtered at runtime by the configured location. Only
     classpath locations are supported (a `file:` location fails with a guiding
     message). In dev mode the indexed files are hot-reload-watched; adding a NEW
     BPMN file requires a restart (or touching a watched file).
   - **Adapters** announce their deployment services via
     `VanillaBpAdapterDeploymentServiceBuildItem` (mirroring the process-service
     build item); the announced producer yields ONE bean of type
     `List<AdapterDeploymentService<Object, Object>>` with one instance per
     configured adapter id. Both element type parameters are LITERALLY `Object`,
     regardless of the adapter's model/context classes - CDI's parameterized-type
     matching of differing type arguments is not reliable across modes, so the
     platform looks the beans up with the exact type (the pipeline matches models
     via `getModelType()`/`getProcessContextType()`, never via the generics).
     Producer methods must be `@Singleton`: deployment/wiring services have no
     no-arg constructor and are not client-proxyable.
   - **Extensions** contribute plain `ExtensionWiringService` element beans (kept
     from ArC's unused-bean removal by the platform); see the
     [dummy extension](./integration-tests/dummy-extension) as the template.
   - **Ordering vs. the phase-two outbox:** the runner observes the `StartupEvent`
     with `@Priority(VanillaBpDeploymentRunner.STARTUP_PRIORITY)`, the outbox
     dispatchers with `OUTBOX_DISPATCHER_STARTUP_PRIORITY` - a crash-recovered
     phase-two operation is never dispatched before deployment finished and
     workflow processing started (Spring Boot enforces the same invariant via
     `@Order`-ed `ApplicationReadyEvent` listeners).
   - **Lifecycle semantics vs. Spring Boot (documented, accepted):** Spring deploys
     during context refresh and starts workflow processing on
     `ApplicationReadyEvent` (after the web server started serving); on Quarkus
     both happen in the `StartupEvent` observer, i.e. before the application
     serves traffic. On shutdown Spring stops workflow processing before the web
     server stops serving, whereas the Quarkus `ShutdownEvent` fires after the
     HTTP server stopped accepting requests. Both platforms stop extensions and
     adapters in reverse start order and stop all process services afterwards
     (`VanillaBpShutdownObserver` → `VanillaBpDeploymentRunner.stop()`).

## Hints

### Logging during tests

To minimize build output three actions were taken:

1. Logs are redirected to file using `maven-surefire-plugin` configuration
   `redirectTestOutputToFile`.
2. Logs of Quarkus builds are set to log-level `ERROR` using
   `systemPropertyVariables` of `maven-surefire-plugin` with
   `<quarkus.log.level>ERROR</quarkus.log.level>`.
3. In tests the logging is captured and printed only in case of failures
   by adding `@ExtendWith(SuppressOutputExtension.class)`.

In case of errors one might disable one or all of them for finding
the root cause of the problem.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
