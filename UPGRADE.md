# Upgrade notes

Documents changes that were necessary when upgrading major dependency versions,
so the reasoning can be looked up later (e.g. when upgrading BPMS adapters or
applications built on VanillaBP).

## Startup configuration validation (2026-07-31)

Story 26c - configuration defects surface at startup, never first at runtime:

- **New core helper `MigrationAdapterProperties.isFirstPriorityAnywhere(adapterId)`**:
  true if the adapter id is FIRST in the prioritized-adapters list globally, of any
  workflow module or of any workflow. Rule for adapters: an adapter that is first
  anywhere always fails the boot on an inconsistent connection configuration; only
  an adapter that is nowhere first may honor `deployment-failure: warn` and boot
  degraded (migration scenario: the old BPMS must not block the boot).
- **Three states of an adapter's config section**: absent → the application boots
  and a guiding WARN names the exact property keys to add; complete → silent;
  inconsistent → boot fails naming the missing keys (unless the degrade rule above
  applies). Messages name property KEYS, never VALUES - credentials are never
  echoed (asserted by boot tests).
- **Aggregate-ID round-trip check at ProcessService creation** (both platforms):
  the aggregate ID crosses the phase-two outbox serialized as a String; an ID type
  that does not convert from/to String losslessly now fails the startup with a
  guiding message (Spring: `DefaultConversionService` both directions; Quarkus:
  supported-type set in `AggregateIdConversion`). Custom
  `AggregatePersistenceAware` implementations (no determinable ID type) are
  exempt - they own the serialized form.

Deliberately still lazy (documented, not defects): phase-two outbox resolution
(optional dependency, guiding message on first use), the PEA mock's VOLATILE
warning (fires when the engine producer runs).

## Adapter config model: per-id beans, canonical location, level resolution (2026-07-30)

Story 26d - three related changes, breaking for adapters and early Camunda 8 users:

- **One process service AND one deployment service per configured adapter id**
  (multiple ids of one BPMS type = the migration scenario). Spring: adapters
  register element beans programmatically via a `BeanRegistrar` using the new
  platform helper `AdapterBeanRegistrarSupport.forEachConfiguredAdapterId`; the
  adapter id is a constructor parameter. Quarkus: adapters produce ONE bean of
  type `List<MigratableProcessService<Object>>` /
  `List<AdapterDeploymentService<Model, Context>>` per adapter (a CDI producer
  cannot yield N element beans for N runtime-config ids); the platform flattens
  List beans alongside element beans and keeps them from ArC's unused-bean
  removal (`keepPerAdapterIdListBeans`). HARD RULE: the List element type is the
  SPI interface literally (CDI does not match subtypes in type arguments).
- **Camunda 8 configuration relocated** (BREAKING for early users): the
  provisional flat namespace `camunda8-adapter.<id>.*` is GONE; the connection
  keys (`mode`, `rest-address`, `grpc-address`, `prefer-rest-over-grpc`,
  `tenant-id`, `cluster-id`, `region`, `client-id`, `client-secret`) now live at
  the canonical per-adapter location `vanillabp.adapters.<id>.*`, contributed via
  the story-19 overlay pattern on both platforms. The last
  `getPropertyNames()`-based key parsing was deleted with it.
- **Level resolution in core:** `MigrationAdapterProperties.resolveForAdapter(
  module, process, task, adapterId, extractor)` resolves adapter-scoped
  properties most-specific-wins across task > workflow > workflow-module >
  adapter. The property model gained the workflow-level `adapters` map and the
  task-level slot (`workflows.<w>.tasks.<t>.adapters.<id>.*`) - structural
  preparation for stories 27/21 (workflow-level config is still rejected at
  startup).

## `vanillabp.outbox.*` consolidated onto the core model (2026-07-30)

Follow-up of the config-binding consolidation (decision 7): the outbox
configuration was modeled per platform (Spring
`io.vanillabp.integration.outbox.PhaseTwoOutboxProperties`, Quarkus nested
`@ConfigMapping` interface) with duplicated defaults and javadoc. Now the core
class `io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties`
is the single source of truth (attached to `MigrationAdapterProperties.outbox`,
bound as part of the `vanillabp.*` tree). **Keys and defaults unchanged**
(PT10S / PT30S / 10 / true / P7D - pinned by a core unit test).

- Spring: the standalone properties class is DELETED; the outbox
  auto-configurations consume `MigrationAdapterProperties.getOutbox()`. The
  outbox key descriptions moved into
  `additional-spring-configuration-metadata.json`.
- Quarkus: the nested `@ConfigMapping` interface stays (SmallRye needs
  `@WithDefault`), the dispatchers consume the CORE object via the generated
  mapper; the necessarily duplicated defaults (interface vs. core) are pinned
  equal by `QuarkusMigrationAdapterPropertiesMapperTest`.

## Configuration binding consolidated onto the core model (2026-07-30)

The `vanillabp.*` tree was modeled three times (core POJOs,
`SpringBootMigrationAdapterProperties`, `QuarkusMigrationAdapterProperties`)
with two hand-written copy transformers. Now the tree is modeled ONCE in the
core and bound natively per platform. **Zero user-visible config-key changes.**

- **Core model reshape:** `MigrationAdapterProperties.adapters` is now
  `Map<String, AdapterConfigProperties>` (fields `type`, `deploymentFailure`),
  matching the user-facing keys `vanillabp.adapters.<id>.{type,deployment-failure}`
  1:1 (prerequisite for direct binding; pulled forward from story 26d). The
  separate `deploymentFailures` map is gone; `getDeploymentFailureFor(id)` stays.
  The id→type view is `adapterTypes()` (deliberately NOT a JavaBean getter - the
  binder and the metadata processor must not see it as a property).
- **`deployment-failure` is bound as the core enum** on both platforms
  (case-insensitively; Spring via a `@ConfigurationPropertiesBinding` converter,
  Quarkus via SmallRye's enum converter). An invalid value fails naming the
  offending key and the allowed values - the former aggregate message
  ("These values are invalid: ...") is replaced by the platform's bind failure
  carrying "must be one of 'fail' or 'warn'" (Spring) / SmallRye's
  allowed-values message (Quarkus).
- **Core `normalize()` + validation absorb the last duplicated logic:** type
  defaulting (`type` absent → id is the type), single-adapter
  `prioritized-adapters` defaulting, the "No adapters configured!" message
  (Quarkus' `xxxx` placeholder aligned to `xxx`) and the workflow-level
  rejection (the Quarkus message changed from listing raw keys to the core
  wording `...Remove these properties: vanillabp.workflow-modules.<id>.workflows`).
  The check "deployment-failure configured for unknown adapter id" is gone -
  structurally impossible now (the policy lives inside the adapter's section).
- **Spring binds the core POJOs directly:** `SpringBootMigrationAdapterProperties`
  and the transformer are DELETED; the thin subclass
  `VanillaBpConfigurationProperties` carries `@ConfigurationProperties("vanillabp")`.
  The module now ships IDE metadata (`spring-configuration-metadata.json` incl.
  hand-written descriptions of the stable top-level keys).
- **Quarkus keeps `@ConfigMapping`; the transformer shrank to capability checks
  plus a GENERATED MapStruct `toCore()`** (`unmappedSourcePolicy`/
  `unmappedTargetPolicy = ERROR`: adding a property to only one side fails the
  build). The SmallRye interface's fluent accessors are made visible to MapStruct
  by the new build-time-only artifact `vanillabp-mapstruct-fluent-accessors`
  (annotation-processor path only - build the reactor with `install`, not
  `package`).
- **Blanket `withMappingIgnore("vanillabp.**")` dropped:** adapter extensions
  now register an OVERLAY `@ConfigMapping(prefix = "vanillabp")` for their own
  keys (reference: the Quarkus dummy adapter's `DummyAdapterOverlayProperties`;
  Spring counterpart: a second `@ConfigurationProperties("vanillabp")` class).
  Consequence: a typo under `vanillabp.*` FAILS the Quarkus startup again
  (Spring's JavaBean binding stays lenient - accepted asymmetry).
- **Environment-variable misbinding validation:** env vars cannot introduce NEW
  dashed adapter/module ids (they can only override entries declared in config
  files). `VANILLABP_*` variables whose id segment matches no configured id now
  fail the startup with a guiding message on both platforms
  (`MigrationAdapterProperties.validateEnvironmentVariableUsage`).

## Aggregate-ID storage is the adapter's decision (2026-07-30)

Review feedback on the hardening story: the shared SPI constant
`MigratableProcessService.AGGREGATE_ID_VARIABLE` (`"aggregateId"`) was removed
again. How the workflow aggregate's ID is stored in the BPMS is the **adapter's
decision**, not a cross-adapter contract: Camunda 7 uses its dedicated business
key, whereas Camunda 8 stores the aggregate as process variables and therefore
names the variable carrying the ID after the aggregate's ID property (the
Process-Engine-API adapter follows the Camunda 8 model).

- **`AggregatePersistenceAware.getAggregateIdName()` added** (business SPI,
  `default` method with a guiding message like the other methods). The
  Spring-Data-based support implements it via `SpringDataUtil.getIdName`.
- **`startWorkflowPhaseTwo` signature changed** (adapter SPI, breaking for BPMS
  adapters): `startWorkflowPhaseTwo(module, process, aggregateId)` →
  `startWorkflowPhaseTwo(module, process, aggregatePersistence, aggregateId)` -
  phase two is dispatched from the outbox (possibly after a restart), so the
  adapter has no other way to obtain the aggregate's ID-property name there.
  Phase one already carried the persistence support.

## Validation parity, ProcessService stubs, SPI alignments (2026-07-28)

Hardening changes relevant for adapters and early adopters:

- **One validation, in core:** `MigrationAdapterProperties.validateProperties` is
  now called on BOTH platforms (Quarkus previously re-implemented the checks
  inline and diverged). The transformers only map platform bindings and check
  what only the platform can know (adapters present in classpath, workflow-level
  rejection, deployment-failure value parsing, Quarkus extension/capability
  consistency). Behavior changes: configuration for a workflow module NOT in the
  classpath only WARNS (previously Quarkus failed the build); new checks reject
  unused `vanillabp.workflow-modules.<m>.adapters.<id>` entries and duplicates in
  `prioritized-adapters` lists.
- **`vanillabp.resilience.*` removed entirely** ("optimize late" - it was mapped
  and validated but never consumed). Retry/timeout design returns per adapter
  with the first consumer (complete/cancel-task story).
- **`ProcessService` operations not yet implemented throw
  `UnsupportedOperationException`** ("not yet supported by VanillaBP 2") from the
  new platform-neutral base `ProcessServiceBase` - replacing Spring's silent
  no-ops and Quarkus' raw `AbstractMethodError`.
- **`WorkflowAwareness` constants renamed:** `TASK_ACTIVE` → `ACTIVE`,
  `TASK_COMPLETED` → `COMPLETED` (the enum answers for workflows AND tasks; both
  constants were unused so far, no adapter is affected).
- **`AggregatePersistenceAware.loadById(Object)` added** (business SPI; needed by
  the task-processing and sync stories). The platform-provided supports implement
  it (Spring Data: `findById`). Additionally ALL methods of the interface are now
  `default` methods throwing an `UnsupportedOperationException` with a guiding
  message - future method additions stay source-compatible for hand-written
  implementations; the platform supports override everything.
- **Naming unified:** `MigrationProcessService.needsTransactionForStartingWorkflows`
  → `needsTwoPhaseCommitForStartingWorkflows` (the SPI term).
- **Deployment pipeline hygiene:** BPMN input streams are owned and closed by the
  pipeline (adapters must not close them); `prepareBpmn` must return non-null;
  modules without any executable BPMN process are skipped with a warning instead
  of calling adapters with a null processing context; extension matching uses
  declared-type assignability everywhere (wiring previously matched on actual
  instances while start/stop matched on declared types).

## Phase-two chain collapsed: `PhaseTwoCall` + `PhaseTwoRouter` (2026-07-28)

Breaking changes of the outbox part of the adapter SPI and the platform beans
(adapters are not affected — `MigratableProcessService` is unchanged):

- **Removed:** `PhaseTwoDispatch` and `ProcessServicePhaseTwo` (SPI) and the
  platform dispatch beans (`PhaseTwoDispatchSpringBean`,
  `QuarkusPhaseTwoDispatch`). The chain
  Outbox → `PhaseTwoDispatch` → `ProcessServicePhaseTwo` →
  `MigrationProcessService` had two layers too many; it is now
  Outbox → `PhaseTwoRouter` (core-owned, `migration-adapter` runtime) →
  `MigrationProcessService` → adapter. Platform process-service beans register
  with the router at bean creation (including a `Function<String,Object>`
  converting the serialized aggregate ID back to the aggregate's ID type —
  conversion happens exactly once, in the router).
- **`PhaseTwoOutbox` reworked (hybrid):** one abstract method
  `boolean schedule(PhaseTwoCall call)`; typed default methods
  (`scheduleStartWorkflow(module, process, aggregateId, adapterId)`) build the
  new immutable `PhaseTwoCall` record and delegate. The signature gained
  `adapterId`: the adapter elected in phase one IS persisted for start operations
  and used at dispatch time (no re-election; stale entries after configuration
  changes yield a guiding error). Contract additions: unique idempotency key
  (store-level unique constraint, duplicate schedule = no-op returning `false`),
  DONE instead of delete (async cleanup after `vanillabp.outbox.retention`,
  default 7 days), documented at-least-once residual window. Key derivation rules
  live on `PhaseTwoOperation` and are a persisted contract.
- **Store schemas changed** (entries of the previous format are not migrated —
  never released): Quarkus JDBC table `VANILLABP_PHASE_TWO_OUTBOX` gained
  `ADAPTER_ID`, `IDEMPOTENCY_KEY` (unique), `STATUS`, `DONE_AT` and dropped
  `AGGREGATE_ID_TYPE`; the Mongo collection analogously (sparse unique index on
  `idempotencyKey`). Gruelbox maps the contract natively
  (`uniqueRequestId` + retention threshold).
- **`vanillaBpOutboxTaskScheduler` beans deleted (Spring):** the outbox
  dispatchers run on private single-thread executors; an application's
  `@EnableScheduling`/`TaskScheduler` setup is no longer affected.
- **`@Transactional` removed** from the former
  `ProcessServiceSpringBean.startWorkflowPhaseTwo` (the method itself is gone;
  phase two needs no local transaction — dual-TM applications broke on it).

## Adapter start phases carry module + process id (2026-07-09)

Breaking change of `MigratableProcessService`, relevant for BPMS adapters:

- `startWorkflowPhaseOne(aggregatePersistence, aggregate)` →
  `startWorkflowPhaseOne(String workflowModuleId, String bpmnProcessId,
  aggregatePersistence, aggregate)`.
- `startWorkflowPhaseTwo(aggregateId)` →
  `startWorkflowPhaseTwo(String workflowModuleId, String bpmnProcessId, aggregateId)`.

Reason: a `MigratableProcessService` is one bean per adapter id, shared across all
processes; without the module and process id an adapter cannot tell which process to
start (embedded engines need the BPMN process id to select the process and the module
id as the BPMS tenant; remote engines need the process id for the create-instance
command). The methods' own documented idempotency key
(`workflowModuleId + bpmnProcessId + workflowAggregateId`) was previously
unconstructible. Both values are forwarded from `MigrationProcessService`, which holds
them as fields. `awarenessOfTask`/`awarenessOfWorkflow` still take only the aggregate
id — they will gain the same two parameters in the fallback-election story (they are
not called by the core yet).

## Phase-two outbox restructured (2026-07-09)

Breaking changes of the outbox part of the adapter SPI, relevant for custom
`PhaseTwoOutbox` implementations (adapters are not affected —
`MigratableProcessService` is unchanged):

- `PhaseTwoOutbox.schedule(module, process, adapterId, aggregateId)` →
  `scheduleStartWorkflow(module, process, aggregateId)`. The adapter is no longer
  part of the scheduled call: it is determined at dispatch time by
  `MigrationProcessService` (starting a workflow always uses the highest-priority
  adapter; upcoming `ProcessService` operations will probe the prioritized adapters
  instead). Every future two-phase operation gets its own `schedule*` method.
- `MigratableProcessServicePhaseTwo` (4-arg method incl. `adapterId`) was replaced
  by `PhaseTwoDispatch` (3-arg, no `adapterId`) — the platform-provided bean outbox
  implementations dispatch to.
- New interface `ProcessServicePhaseTwo`: implemented by the platform integrations'
  process-service beans; `PhaseTwoDispatch` implementations use it to route a
  dispatched call to the bean of the workflow module/BPMN process it belongs to.
- Store-based default implementations (Spring MongoDB, Quarkus JDBC) now persist an
  `operation` discriminator instead of the adapter ID (JDBC column `ADAPTER_ID` →
  `OPERATION`; entries of the previous format are not migrated — the table/collection
  was never part of a release).

## Adapter SPI consolidation (2026-07-05)

Breaking changes of the adapter SPI, relevant for the upcoming adapter repositories
(there are no adapters built against the previous signatures yet):

### New module `io.vanillabp:vanillabp-integration-spi` (business SPI)

The SPI was split into a *business SPI* (interfaces business code may implement) and
the *adapter SPI* (`io.vanillabp.adapter:migration-adapter-spi`, implemented by BPMS
adapters and platform integrations):

- `AggregatePersistenceAware` now exists exactly once:
  `io.vanillabp.integration.spi.AggregatePersistenceAware` in
  `vanillabp-integration-spi`. The three byte-identical copies
  (`io.vanillabp.integration.adapter.spi.*` in the adapter SPI,
  `io.vanillabp.integration.spi.aggregate.*` in `vanillabp-spring-boot-support`,
  `io.vanillabp.integration.spi.*` in `vanillabp-quarkus-support`) and both
  `AggregatePersistenceAwareWrapper` classes were removed. The support modules
  provide the interface transitively, so business code keeps depending on the
  support module only (Spring Boot users have to adjust the import from
  `io.vanillabp.integration.spi.aggregate` to `io.vanillabp.integration.spi`).

### `AdapterDeploymentService<BPMN, DMN, PC>` → `AdapterDeploymentService<BPMN, PC> extends ExtensionWiringService<BPMN, PC>`

- The unused `DMN` type parameter was removed (DMN support will be added once
  designed).
- The adapter interface no longer declares `getModelType()`,
  `getProcessContextType()`, `wireBpmn(...)`, `startWorkflowProcessing(...)` and
  `stopWorkflowProcessing(...)` itself — they are inherited from
  `ExtensionWiringService` (an adapter is "the wiring service with deployment").
- `ExtensionWiringService.getOrder()` got a `default 0`, so adapters need not
  implement it.
- `ExtensionWiringService.stopWorkflowProcessing(...)` (default no-op) is called on
  graceful shutdown in reverse start order (extensions first, then adapters) —
  wired by Spring Boot's `SmartLifecycle.stop()` and a Quarkus `ShutdownEvent`
  observer.

### `MigratableProcessService`: awareness instead of `isTaskActive`

`Boolean isTaskActive(String taskId)` was replaced by:

```java
WorkflowAwareness awarenessOfTask(Object workflowAggregateId, String taskId);
WorkflowAwareness awarenessOfWorkflow(Object workflowAggregateId);
```

with `enum WorkflowAwareness { TASK_ACTIVE, TASK_COMPLETED, UNKNOWN_TO_BPMS,
BPMS_UNAVAILABLE }`. Contract: `BPMS_UNAVAILABLE` means "do not fall back to the next
adapter — retry later"; only `UNKNOWN_TO_BPMS` permits falling back. The
instance-level method exists because message correlation has no task ID and task IDs
are not unique across BPMSs. `startWorkflowPhaseOne` now uses
`io.vanillabp.integration.spi.AggregatePersistenceAware` (import change only).

### New configuration

- `vanillabp.adapters.<id>.deployment-failure` = `fail` (default) | `warn`:
  with `warn` a deployment failure of a NON-first-priority adapter is logged and the
  application still starts; a failure of the first-priority adapter always fails the
  boot.
- `vanillabp.resilience.{max-retries,initial-interval,multiplier,timeout}`:
  retry/backoff settings for eventually-consistent BPMS calls, overridable per
  workflow module and (once supported) per workflow — the most specific block wins
  as a whole.

## Quarkus 3.26.4 → 3.37.1 (2026-07-05)

Version bump in `quarkus-integration/pom.xml` (`quarkus.version`). One real change was
required:

### Config root phase changed to RUN_TIME

`QuarkusMigrationAdapterProperties` was declared as
`@ConfigRoot(phase = BUILD_AND_RUN_TIME_FIXED)`. Values of such config roots are read
at **build time** and frozen. The workflow-module-specific config files
(`<module-id>.properties/.yaml`) are added by generated config builders to the
static-init/runtime config only — they are never part of the build-time
configuration.

Up to Quarkus 3.26 this worked **by accident**: the `@ConfigMapping` instance was
re-populated at static init against the full config (including the module config
sources). Since the Quarkus config optimization that introduced the generated
`SharedConfig` class (mapping instances are created once from build-time values and
reused in static-init and runtime config via `withMappingInstance`), the mapping's
`workflowModules()` map stayed empty at runtime, and all `QuarkusProdModeTest`s that
actually launch the application failed with:

```
IllegalStateException: No workflow-modules configured! Add properties sections
'vanillabp.workflow-modules.<id>' ...
```

Fix (also semantically the right choice, because VanillaBP configuration such as
adapter endpoints must be overridable per environment, e.g. via environment
variables):

- `QuarkusMigrationAdapterProperties`: `ConfigPhase.BUILD_AND_RUN_TIME_FIXED` →
  `ConfigPhase.RUN_TIME`
- `ConfigBuildStepProcessor.buildMigrationAdapterProperties`:
  `@Record(ExecutionTime.STATIC_INIT)` → `@Record(ExecutionTime.RUNTIME_INIT)`
  (the synthetic `MigrationAdapterProperties` bean was already `setRuntimeInit()`)

Diagnosis hint for similar problems: decompile
`io/quarkus/runtime/generated/StaticInitConfig*.class` and `SharedConfig.class` from
the `generated-bytecode.jar` of a prod-mode build — they show which config builders,
sources and mapping instances are actually wired.

## Spring Boot 3.5.x → 4.1.0 (2026-07-05)

Spring Boot 4 modularized the formerly monolithic `spring-boot-autoconfigure` and
`spring-boot-test-autoconfigure` artifacts: technology-specific auto-configurations and
test slices now live in their own modules with new package names. The starters
(`spring-boot-starter-data-jpa`, `spring-boot-starter-data-mongodb`,
`spring-boot-starter-test`) kept their names and pull the new modules in transitively —
**except test slices**, which now require an explicit dependency.

### Version bumps

- `spring-boot-integration/pom.xml`: `spring-boot-dependencies` BOM 3.5.5 → 4.1.0.
- `test-utils/pom.xml` (plain-Java module with optional Spring deps, no BOM):
  `spring-beans`/`spring-context` 6.2.14 → 7.0.8 (Spring Framework 7),
  `spring-boot`/`spring-boot-test` 3.5.5 → 4.1.0.

### Moved classes (import changes)

|                 Class                  |                Old package (Boot 3.x)                 |                 New package (Boot 4.x)                 |         New module          |
|----------------------------------------|-------------------------------------------------------|--------------------------------------------------------|-----------------------------|
| `@EntityScan`                          | `org.springframework.boot.autoconfigure.domain`       | `org.springframework.boot.persistence.autoconfigure`   | `spring-boot-persistence`   |
| `HibernateJpaAutoConfiguration`        | `org.springframework.boot.autoconfigure.orm.jpa`      | `org.springframework.boot.hibernate.autoconfigure`     | `spring-boot-hibernate`     |
| `MongoClientSettingsBuilderCustomizer` | `org.springframework.boot.autoconfigure.mongo`        | `org.springframework.boot.mongodb.autoconfigure`       | `spring-boot-mongodb`       |
| `@DataJpaTest`                         | `org.springframework.boot.test.autoconfigure.orm.jpa` | `org.springframework.boot.data.jpa.test.autoconfigure` | `spring-boot-data-jpa-test` |

Affected files (test code only — main code was not affected):

- `spring-boot-integration/runtime/src/test/.../JpaSpringDataUtilTest.java`
- `spring-boot-integration/runtime/src/test/.../MongoDbSpringDataUtilTest.java`
- `spring-boot-integration/integration-tests/main-integration-test/src/test/.../AdapterConfigurationTest.java`

### New dependency required

Test slices are no longer part of `spring-boot-starter-test`. For `@DataJpaTest` the
artifact `org.springframework.boot:spring-boot-data-jpa-test` (scope `test`) was added
to `spring-boot-integration/runtime/pom.xml`. (`@DataMongoTest` would analogously
require `spring-boot-data-mongodb-test` — not needed so far.)

### Not affected

- `SslAutoConfiguration` stayed in `spring-boot-autoconfigure`.
- Core auto-configuration mechanics (`@AutoConfiguration`, `@ConditionalOnClass`,
  `AutoConfiguration.imports`, `EnvironmentPostProcessor` via `spring.factories`) —
  unchanged, no code changes needed.
- Bean-definition registration (`BeanDefinitionRegistryPostProcessor`,
  `ResolvableType`-based generic `ProcessService` beans) — unchanged.
- JPA/Hibernate usage (`PersistenceUnitUtil`, `Hibernate.unproxy`) under Hibernate 7 —
  unchanged.
- Spring Boot 4.1.0 manages JUnit Jupiter **6.0.x** and Mockito 5.23; tests kept
  running without changes (root `mockito.version` is upgraded separately).

