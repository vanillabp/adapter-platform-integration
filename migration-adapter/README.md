![Header](../readme/vanillabp-headline.png)

# Migration adapter

The migration adapter is the implementation of VanillaBP's adapter mechanism
used under the hood. Platform integrations are sole responsible for loading configuration
and analyzing business code in a way specific to the respective platform. Connecting
to adapters of supported BPMSs is done by this module which is used by platform
integrations  as a dependency. This ensures the same behavior of VanillaBP on
different platforms.

## Two kinds of plug-ins: adapters and extensions

Everything that takes part in deploying a workflow module implements
`ExtensionWiringService<BPMN, PC>`. There are two kinds of implementations, and telling
them apart is the first thing to understand about this module:

|                     |                                                                                 **BPMS adapter**                                                                                 |                                                                                      **Extension**                                                                                      |
|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Interface           | `AdapterDeploymentService<BPMN, PC>` — which *extends* `ExtensionWiringService`                                                                                                  | `ExtensionWiringService<BPMN, PC>`                                                                                                                                                      |
| What it is for      | connecting VanillaBP to ONE business process management system: it owns the BPMN model type, reads and deploys the models and executes every workflow operation against its BPMS | integrating something ADDITIONAL into the deployment: it sees the models the adapter sees and wires its own concerns against them, but it never talks to the BPMS on VanillaBP's behalf |
| Owns the model?     | yes — it reads, prepares and deploys it                                                                                                                                          | no — it only inspects (and may enrich) what the adapter read                                                                                                                            |
| How many per module | one per configured adapter id (several BPMS side by side = the migration scenario)                                                                                               | any number                                                                                                                                                                              |
| Examples            | `camunda7`, `camunda8`, `process-engine-api`                                                                                                                                     | the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit)                                                                                                         |

An adapter is therefore "the wiring service that owns the model", which is why the
sections below describe the pipeline once and note where extensions join it.

## Features

The migration adapter is "an adapter aware of other adapters": it is the single point
where BPMS adapters plug in, and it decides on a per-workflow basis which BPMS is used.
This enables migration scenarios without touching business code:

1. Migrating within the same BPMS (on-premises to SaaS, or between versions).
2. Migrating from one BPMS to another (e.g. Camunda 7 to Camunda 8).

### BPMS election by prioritized adapters

Which BPMS is used is configured as a *prioritized list of adapters*
(`vanillabp.prioritized-adapters`). The list can be overridden per workflow module
(`vanillabp.workflow-modules.<id>.prioritized-adapters`) and per workflow
(`...workflows.<bpmnProcessId>.prioritized-adapters`) — the most specific non-empty
value wins (see `MigrationAdapterProperties`).

- **New workflows** are always started using the first (highest-priority) adapter.
- **Existing workflows** may still live in a previously used BPMS. For operations on
  existing workflows (message correlation, completing tasks) the adapters are asked in
  priority order whether they own the instance (`MigratableProcessService.awarenessOfTask`
  and `awarenessOfWorkflow`, returning a `WorkflowAwareness`). This is why eventual
  consistency has to be handled *here* and not in individual adapters: a remote BPMS
  (like Camunda 8) may not know an instance *yet*, and only the migration adapter can
  decide to fall back to the next adapter in the list.

### Awareness contract (`WorkflowAwareness`)

Asking a BPMS whether it knows a workflow or task has four possible answers
(`MigratableProcessService.awarenessOfTask(workflowAggregateId, taskId)` /
`awarenessOfWorkflow(aggregatePersistence, workflowAggregateId)`):

|       Value        |                            Meaning                            |        Migration adapter's reaction         |
|--------------------|---------------------------------------------------------------|---------------------------------------------|
| `ACTIVE`           | The BPMS knows the workflow/task and it is active             | Use this adapter                            |
| `COMPLETED`        | The BPMS knows the workflow/task but it was already completed | Use this adapter (operation comes too late) |
| `UNKNOWN_TO_BPMS`  | The BPMS definitely does not know the workflow/task           | Fall back to the next adapter of the list   |
| `BPMS_UNAVAILABLE` | The BPMS could not be asked (unreachable, timeout)            | Do **not** fall back — retry later          |

The distinction between `UNKNOWN_TO_BPMS` and `BPMS_UNAVAILABLE` is crucial: only a
definite "not known" permits falling back to the next adapter — a temporary failure
must not silently elect the wrong BPMS. There is an instance-level method
(`awarenessOfWorkflow`) in addition to the task-level one because message correlation
has no task ID and task IDs are not unique across BPMSs.

The election runtime lives in `WorkflowLocator` (one instance per process service):
every operation on an EXISTING workflow (complete/cancel task, user task, message
correlation — in phase one and again at phase-two dispatch) walks the prioritized
adapters with an operation-specific probe (`awarenessOfTask`,
`awarenessOfUserTask`, `awarenessOfWorkflow`). `ACTIVE` executes there, `UNKNOWN_TO_BPMS`
falls through to the next adapter, `COMPLETED` is a warned no-op,
`BPMS_UNAVAILABLE` retries twice (500&nbsp;ms apart, fixed — "optimize late") and
then fails naming the adapter — it NEVER falls back. New workflows always start in
the first-priority adapter (no probing).

Successful elections populate a `WorkflowAdapterCache`
(business SPI; key = workflow module, BPMN process, serialized aggregate ID →
adapter ID). The next election probes the cached adapter first. Entries are HINTS,
not truth: a stale hit (the adapter answers `UNKNOWN_TO_BPMS`) falls through to the
full walk and repairs the entry; `BPMS_UNAVAILABLE` on a cached adapter follows the
retry-never-fallback contract. The platform integrations provide a bounded,
expiring in-memory default (`InMemoryWorkflowAdapterCache`, 10&nbsp;000 entries /
1&nbsp;h TTL by default, both configurable — see below); an application bean
implementing `WorkflowAdapterCache` replaces it — cluster setups plug their own
shared cache infrastructure this way (VanillaBP deliberately ships no distributed
implementation).

### Sizing the election cache, and knowing when to (story 58)

Both bounds are properties of the platform, not of an adapter:
`vanillabp.workflow-adapter-cache.max-entries` and `.time-to-live`
(`WorkflowAdapterCacheProperties`, validated at startup like everything else, today's
values as defaults). An application running several thousand workflows of one process
at the same time does not run out of memory — the map is bounded, a full cache costs
about 3&nbsp;MB. It runs into EVICTION PRESSURE instead: more hot workflows than
places, so entries are dropped before they are read.

**Why the bound is a number and not a soft reference.** Soft references were
considered and rejected: the JVM clears them only when it is nearly out of heap, so
the cache would grow into the old generation and be reclaimed exactly when the
application is under pressure anyway; every soft reference is visited by full GCs,
which lengthens the pauses this kind of application cares about most; it would trade a
deterministic bound for one depending on GC tuning
(`-XX:SoftRefLRUPolicyMSPerMB`), for a cache whose loss is cheap by design; and the
numbers do not call for it, since 100.000 entries are roughly 30&nbsp;MB and raising
the bound is the cheaper answer. An application which really wants soft or off-heap
semantics has the SPI bean for it.

**What is measured.** `WorkflowAdapterCacheStatistics` (one per application) counts
hits, misses, evictions, evictions before an entry was ever read, and LOST HINTS. The
process services wrap WHATEVER cache is in use into an
`InstrumentedWorkflowAdapterCache` reporting there, so hits and misses exist for an
application-provided cache as well. A number which disappears once somebody plugs in
their own cache would surprise exactly the operator who needs it. Size and evictions
are different: only the implementation knows them, so the in-memory default reports
them itself and an application's cache reports none (the size gauge is NaN, not a
wrong zero). `WorkflowAdapterCacheMeters` publishes all of it as Micrometer meters
under `vanillabp.workflow.adapter.cache.*`; Micrometer is OPTIONAL and both platforms
apply `MeterBinder` beans to their registries by themselves, so an application without
it boots unchanged and reports nothing.

**The warning is about lost hints, not about a full cache.** A cache which is merely
full is healthy, and an entry evicted unread is not by itself a defect (a workflow
which is started and never operated on afterwards leaves exactly such an entry
behind). It becomes one when that workflow IS looked up later: then the bound, not the
workflow, decided the outcome. The keys of unused evictions are therefore remembered
(hash codes, bounded like the cache), and a later miss on such a key is a lost hint.
Ten of them within an hour produce ONE guiding WARN naming the observed number, the
observation period and the property to raise. Heap pressure is deliberately not the
trigger, because the cache cannot see the old generation and the JVM does not tell it.

The workflow probe takes the aggregate's persistence because a BPMS without a business
key finds the workflow by the process variable carrying the aggregate's ID, and that
variable is named after the aggregate's ID attribute
(`AggregatePersistenceAware.getAggregateIdName()`). The election runs BEFORE every other
SPI method of an operation, so an adapter must never derive the name from a previous
call - Camunda 8 did exactly that until story 54 and searched under a placeholder name,
which found nothing on a cluster with secondary storage and reported every workflow as
unknown.

To migrate, one puts the new BPMS first in the priority list and keeps the old one in
the list: new instances start in the new BPMS while existing instances complete in the
old one.

### Waiting for a workflow to become visible (story 54)

An election which asks a BPMS answering from an eventually consistent read model
(Camunda 8 searches its query API, fed by an exporter) gets an honest "unknown" for a
workflow started moments ago. Turning that into `WorkflowNotFoundException` names causes
which are all wrong, and it hits the most ordinary sequence there is: start a workflow,
then correlate the message which lets it continue.

Three pieces solve it, and the split matters:

1. **The adapter contributes the window, the core does the waiting.**
   `MigratableProcessService.workflowVisibilityDelay()` (a `default` returning
   `WorkflowVisibilityDelay.none()`, so no adapter breaks) answers how long an
   `UNKNOWN_TO_BPMS` may still turn into `ACTIVE` and how often to ask. Camunda 7 answers
   from the very transaction which created the instance and reports none; Camunda 8
   reports `vanillabp.adapters.<id>.workflow-visibility-timeout` (default 10 seconds,
   zero switches it off). Eventual consistency is the core's business, the timing is the
   adapter's.
2. **The waiting is bounded by a hint, never blanket.** `WorkflowLocator` waits only
   while probing an adapter the `WorkflowAdapterCache` names for that workflow. A
   workflow nobody ever heard of has no hint and fails as fast as before - which a wrong
   ID has to, since waiting the full window on every typo would turn a programming error
   into a timeout.
3. **The cache is filled where VanillaBP knows the answer without asking**
   (`MigrationProcessService.rememberWorkflowAdapter`): when a start is SCHEDULED (the
   elected adapter is decided then), again after its phase two, and on every inbound
   delivery - a task, a user task, a `@WorkflowEnded` notification, a BPMS-initiated
   start. For the latter the inbound contexts carry the adapter's id
   (`TaskInvocationContext.getAdapterId()` and its siblings, `default null`, implemented
   by all three adapters). A delivery PROVES which BPMS holds the workflow.

   Recording at SCHEDULING time is what makes the sequence work at all: on a remote BPMS
   the instance is created after the commit, so a correlation in the very next
   transaction runs its election before phase two ever ran. Without the early hint it
   would find nothing to wait for. The price is the usual one of a hint: a rolled-back
   start leaves an entry behind, and the next operation on that aggregate ID waits out
   the window before failing.

**What was deliberately NOT built: an outbox query.** A `START_WORKFLOW` entry still
`OPEN` would prove "too early rather than unknown", and one `DONE` a moment ago would
prove "exactly the window we are waiting for". It was left out:

- the outbox can only ever contribute the POSITIVE half. A workflow does not have to have
  been started by this VanillaBP: a version-1 application which migrated, a start by
  another system, a BPMS-initiated start (which writes no entry at all) and a cleaned-up
  `DONE` entry all leave the outbox silent while the workflow runs perfectly well;
- it would need a new query method in EVERY store implementation (gruelbox, the Spring
  and Quarkus JDBC stores, MongoDB, and any store an application wrote itself) - the SPI
  `PhaseTwoOutbox` has exactly one method today, `schedule`;
- what it would buy over the cache is one case: the start ran on ANOTHER node of a
  cluster, whose in-memory cache the correlating node does not share. That case is the
  one the `WorkflowAdapterCache` bean exists for - an application running clustered
  plugs its shared cache in, which is cheaper than teaching every outbox store a query.

The residual is therefore honest and documented: on a cluster WITHOUT a shared cache, an
operation reaching a node which neither started the workflow nor received a delivery for
it can still fail while the BPMS catches up. Retrying the business operation works, and
so does a shared cache.

### Deployment pipeline

`DeploymentService` orchestrates deployment per workflow module:

1. Resolve the adapters the module has to be deployed to and find the matching
   `AdapterDeploymentService` by adapter ID. This is the **union** of the module's
   effective prioritized-adapters list and every adapter named in a workflow-level
   `prioritized-adapters` override of that module: BPMS election is
   process-granular while deployment is file-granular, so an adapter prioritized
   for a single workflow only still has to receive the module's resources —
   otherwise starting that workflow would fail at runtime. Every adapter of the
   union receives the module's *full* resources (per-process filtering was
   considered and rejected: BPMN files may contain several processes and adapters
   deploy whole files; extra processes deployed to an adapter are inert because
   workflow starts are routed by the election, and during a BPMS migration having
   the module's complete model in both BPMS is even desirable).
2. Load the BPMN resources (either from an adapter-specific `resources-location` or
   the generic VanillaBP BPMN files).
3. For each file run the pipeline `readBpmn` → `prepareBpmn` (per process) →
   `wireBpmn` (adapter) → `wireBpmn` of all matching `ExtensionWiringService`s.
   The adapter-specific *processing context* (generic parameter `PC`) is accumulated
   across all executable processes of a file and across all files of a module.
   Extensions receive the same processing context as the adapter's `wireBpmn`;
   extensions are matched by assignability, so extensions may declare interfaces
   as model or processing-context type.
4. `deployResources` pushes the result to the BPMS. A failing deployment aborts
   booting unless the adapter's `deployment-failure` policy is `warn` *and* the
   adapter is first priority neither for the module nor for any of its workflows.
   After all adapters were processed, configured workflow IDs
   (`vanillabp.workflow-modules.<module>.workflows.<id>`) matching no executable
   BPMN process found are reported by a WARN naming the known process IDs (not a
   failure — the BPMN may arrive later, e.g. during a BPMS migration; process IDs
   are known only after `readBpmn`, which is why this check lives in the pipeline
   and not in the early properties validation).
5. Once the application is ready, `startWorkflowProcessing` is called for adapters and
   extensions — only then workflows are actually processed. It is called for *every*
   adapter resources were deployed to (not only the highest-priority one), since
   during a BPMS migration all configured BPMSs have to keep processing workflows.
6. On graceful shutdown of the application, `stopWorkflowProcessing` is the
   counterpart of step 5, executed in reverse order: extensions are stopped first (in
   reverse wiring order), then the adapters. It is wired by the platform integrations
   (Spring Boot: `SmartLifecycle.stop()`; Quarkus: a `ShutdownEvent` observer) so no
   new workflow jobs are processed while web/messaging infrastructure is being torn
   down.

### Name-clash avoidance (`NameClashAvoidanceSupport`)

One adapter-scoped property decides how a workflow module's identifiers are kept
apart from another module's: `name-clash-avoidance` = `BY_ADAPTER` (VanillaBP 1's
behavior — the BPMS' own isolation, e.g. a tenant named after the module) |
`USE_PREFIX` (no tenant; VanillaBP prefixes the identifiers, separator `__`) |
`NONE`. The core owns both the resolution (most-specific-wins across workflow >
workflow module > adapter) and the composition of the strings
(`NameClashAvoidanceService implements NameClashAvoidanceSupport`, handed to every
adapter as a platform bean).

Without any configuration the ADAPTER's default applies
(`AdapterDeploymentService#defaultNameClashAvoidance`, `BY_ADAPTER` unless
overridden). Both Camunda adapters override it with `NONE`: a Camunda 8 cluster
started from the stock image has multi-tenancy switched off and rejects a deploy
command carrying a tenant id, and Camunda 7 offers more than one mechanism (a tenant,
prefixes, an engine per module), so neither presumes one. The platform integrations
hand the adapters' deployment services to the service as a lazily resolved supplier
(adapters receive the service themselves, so they cannot be injected).

`BY_ADAPTER` means "the BPMS' own isolation", and what that IS the core does not know
(a tenant, a namespace, a database of its own). It therefore computes none of it: the
adapters derive the tenant themselves from the resolved mode (`Camunda8Scoping` and
`Camunda7Scoping`, duplicated on purpose - the rule is one line and the concept is
theirs). The core answers `modeFor` and nothing else.

The same split holds for validating: an adapter which carries configuration only
BY_ADAPTER could honor hands the PROPERTY KEY to `validateNoneNameClashStrategy`, and
the core answers whether BY_ADAPTER applies anywhere for that adapter - if it does
not, the boot fails naming the property, the modes which do apply and the two ways to
reconcile them. Both Camunda adapters pass their `tenant-id` this way.

And for asking the BPMS itself: Camunda 8 looks the tenant up in the cluster before
deploying (a cluster without multi-tenancy, or an unknown tenant, becomes a message
naming the property to change), while Camunda 7 has nothing to ask - a tenant id is an
attribute of the deployment there and the engine creates nothing.

Since `NONE` protects nothing, the core has the ADAPTER report it once per workflow
module and adapter id while the mode is resolved, i.e. at startup
(`AdapterDeploymentService#warnAboutUnscopedIdentifiers`). What can be used instead
is BPMS knowledge: Camunda 8 offers prefixing, a tenant per module on a
multi-tenancy cluster or a cluster per module, Camunda 7 offers prefixing, a tenant
per module or an engine per module (`data-source-name`, `table-prefix`). The default
implementation names what every BPMS can offer.

An adapter only decides WHERE to apply the result:

- in `prepareBpmn`, on its own model type — only the adapter knows it. Note the
  pipeline calls `prepareBpmn` once per PROCESS while all processes of a file share
  ONE model, so a model must be rewritten **once per file** (the adapters guard this
  by checking whether the file is already in the processing context);
- at every runtime boundary, outbound (`scoped*`) and inbound (`plain*`). Inbound
  always strips a KNOWN prefix, never "everything up to the first separator".

The result is transparent: registries, `ProcessService` calls, BPMN files and
configuration keep the PLAIN identifiers; only the BPMS sees scoped ones. Which
identifiers actually need scoping is BPMS-specific and documented in each adapter's
wiki (Camunda 7 does not prefix task definitions — they are process-local
expressions; Camunda 8 must prefix job types — they are subscribed cluster-wide).

Two guardrails belong to adapters:
`validateNativeIsolationSupported(adapterId, workflowModuleId, bpmsDescription)` is
called while DEPLOYING by an adapter whose BPMS has no isolation of its own (it
rejects `BY_ADAPTER`; `validateDistinctAdapterInstances` is the wrong place, the core
invokes that only for more than one id of a type), and
`validateNoCollidingProcessIds(adapterId, deployedProcesses)` is called once the
deployed processes are known. Changing the mode is a BPMS **migration**, not a
property change — hence a differing mode makes two adapter ids of one type distinct.

### Workflow-task processing

`@WorkflowTask` methods are executed by the core (package `workflowtask`): the
platform integration registers every `@WorkflowService` class under all BPMN
process IDs it declares (`bpmnProcess` + `secondaryBpmnProcesses`) with the
`WorkflowTaskRegistry` (scanning methods and building parameter binders once at
startup). Adapters interact through the adapter SPI `WorkflowTaskInvoker`:

1. During `wireBpmn`: `validateTaskWiring(module, process, tasks)` - both
   directions (every BPMN task has a `@WorkflowTask` method, every method matches
   a task), all defects in ONE guiding exception; throwing from `wireBpmn` honors
   the deployment-failure policy.
2. At runtime: `invokeWorkflowTask(module, process, TaskInvocationContext)` - the
   core resolves the handler (task definition or activity ID, `version` ranges),
   loads the aggregate by its serialized ID, invokes the method with bound
   parameters and saves the aggregate, all within one transaction run by the
   platform's `TransactionRunner` (a new transaction, or the caller's for embedded
   BPMS - `TaskInvocationContext.runInCurrentTransaction`). Outcomes: normal
   return = COMPLETED (or COMPLETION_PENDING for `@TaskId` methods - completion
   arrives via `ProcessService#completeTask`); `TaskException` = BPMN_ERROR with
   the aggregate changes COMMITTED (the restored V1 contract); any other exception
   rolls back and propagates - the adapter applies its BPMS' retry semantics and
   must not complete the task.

An adapter's `AdapterDeploymentService` extends `ExtensionWiringService`
("the wiring service with deployment"): preparing/wiring and starting/stopping of
workflow processing are inherited, reading and deploying of BPMS resources is added.
There is deliberately no DMN model type parameter yet — DMN support will be added to
the interface once designed.

#### Deployment-failure policy

By default, a failing deployment of any configured adapter aborts booting of the
application. During a BPMS migration the old BPMS may be temporarily unreachable —
setting `vanillabp.adapters.<id>.deployment-failure` to `warn` lets the application
start anyway if a NON-first-priority adapter fails to deploy (the failure is logged
and that adapter does not process workflows). A failure of the first-priority adapter
always fails the boot, regardless of the policy, because new workflows could not be
started otherwise.

#### Process versions (`version` attribute)

The `version` attribute of `@WorkflowTask`, `@WorkflowStartedByBpms` and
`@WorkflowEnded` is evaluated by `VersionRange` (parsed once at registration) and
`ProcessVersions` (package `workflowtask`), shared by all three handler kinds:

- a boundary is a version identifier as the BPMS counts it, or a version TAG of the
  model. A specification consisting of numbers is compared to the version the adapter
  reports in its invocation context, so it costs nothing;
- as soon as a TAG is involved, both sides are placed in the deployment order through
  the adapter SPI `ProcessVersionCatalog` (package `spi.version`), which an adapter
  hands over per process during `wireBpmn`
  (`WorkflowTaskInvoker.registerProcessVersions`). `CachingProcessVersionCatalog`
  implements the bookkeeping every adapter would write otherwise: the versions its
  deploy command reported, the BPMS query for a version it has never seen (a rolling
  deployment where another node is ahead) and a floor between two such queries;
- `WorkflowTaskInvoker.resolveProcessVersions(module)`, called by adapters at the end
  of `deployResources`, resolves the tags the application names while the application
  STARTS - the deployment has happened, so a tag deployed by this very start is
  included. A tag no BPMS knows is a WARN, never a boot failure: the tagged version may
  arrive later, and the other methods have to keep serving.

Two methods wired to the same BPMN element are ambiguous exactly when their ranges
OVERLAP (`VersionRange.overlaps`, interval math). A range naming a tag cannot be placed
before a BPMS was asked, so the check runs twice: at registration for everything
decidable without a BPMS, and again during `resolveProcessVersions`. Both times it fails
the boot naming both methods.

### Two-phase workflow start (`PhaseTwoOutbox` SPI)

Starting a workflow must be atomic with the local database transaction that persists
the workflow aggregate — otherwise a crash could produce a workflow in the BPMS without
an aggregate ("ghost workflow") or vice versa. Since remote BPMS cannot take part in
the local transaction, starting is split into two phases
(`MigratableProcessService`):

- **Phase one** runs inside the local transaction. Embedded BPMS start the workflow
  right here (same transaction, phase two is a no-op); remote/eventually-consistent
  BPMS only lock/validate.
- **Phase two** runs after the local commit. For adapters reporting
  `needsTwoPhaseCommitForStartingWorkflows()`, the phase-two call is scheduled via
  the *transaction outbox* SPI `PhaseTwoOutbox`. The outbox is resolved PER
  AGGREGATE via the platform's `PhaseTwoOutboxResolver` (user-defined
  `PhaseTwoOutboxAware` beans first, then the platform's default selection) - AT
  STARTUP, by `MigrationProcessService.validatePhaseTwoOutboxAtStartup()`: if the
  first-priority adapter needs a two-phase commit and no outbox resolves, the boot
  fails with a guiding message naming the remedies (the same message remains as a
  runtime backstop).

A scheduled call is described by the immutable value type `PhaseTwoCall`
(operation, workflow module, BPMN process, workflow-aggregate ID in serialized
String form, elected adapter ID, operation-specific args). The dispatch chain is as
short as possible:

```
schedule*                     dispatch(call)         startWorkflowPhaseTwo(id, adapterId)
ProcessService ──► PhaseTwoOutbox (store) ──► PhaseTwoRouter ──► MigrationProcessService ──► adapter
      within local TX             after commit        (core-owned)     (adapter selection)
```

The core-owned `PhaseTwoRouter` holds a registry `(workflowModuleId, bpmnProcessId)
→ MigrationProcessService`, filled by the platform integration at bean-creation
time. The serialized aggregate ID is converted back into the aggregate's ID type by
the process service itself (`convertAggregateId`, backed by
`AggregatePersistenceAware.getAggregateIdType()` and the core's
`AggregateIdRoundTrip` - the type is validated at startup to round-trip losslessly;
a `null` type means the custom persistence layer owns the serialized form and the
String is passed through). For `START_WORKFLOW` the adapter elected in phase one
**is persisted with the outbox entry** and used in phase two — no re-election from
the then-current priorities. If the adapter was removed from the configuration
while the entry was still open (stale entry), dispatching fails with a guiding
message naming that case. Future `ProcessService` operations (message correlation,
completing tasks, ...) will instead probe the prioritized adapters at dispatch time
(their calls carry no adapter ID); each such operation gets its own typed
`schedule*` default method in `PhaseTwoOutbox` building the `PhaseTwoCall`.

#### Which operations exist: the operation registry

An operation is not a hardcoded case in the router but an entry of the
`PhaseTwoOperationRegistry` (business SPI), consisting of three things:

- its **name**, which the store persists and which is therefore a contract: never
  rename an operation, never change what an existing name means,
- its **idempotency-key derivation** (`PhaseTwoOperation.IdempotencyKey`, a function
  of the `PhaseTwoCall`), which is just as persisted as the name, and
- its **dispatch** (`PhaseTwoOperationDispatch`), which is not persisted at all: it
  is registered at startup by whoever owns the operation.

VanillaBP's own operations (`START_WORKFLOW`, `COMPLETE_TASK`, `CANCEL_TASK`,
`COMPLETE_USER_TASK`, `CANCEL_USER_TASK`, `CORRELATE_MESSAGE`,
`START_WORKFLOW_BY_MESSAGE`, `SEND_SIGNAL`, `AGGREGATE_CHANGED`) are constants of `PhaseTwoOperation`, registered by the
`PhaseTwoRouter` while it is built. Their dispatch is the process-service routing
described above. Their names and key rules are pinned by a test, because since they
stopped being enum constants nothing else guarantees them.

An **extension** contributes operations of its own, which is why the registry exists.
It builds them with `PhaseTwoOperation.extensionOperation(name, key)`, which enforces
a namespace (`my-extension:MY_OPERATION`), and registers them together with its own
dispatch:

```java
registry.register(
    PhaseTwoOperation.extensionOperation(
        "my-extension:NOTIFY",
        call -> Optional.of(call.workflowAggregateId() + "|" + call.args().get("event"))),
    (call, previouslyAttempted) -> notify(call));
```

The registry is offered as a bean by both platform integrations (Spring Boot:
`vanillaBpPhaseTwoOperationRegistry`; Quarkus: a `@Singleton` producer). Scheduling
works as for core operations: build the call with
`PhaseTwoCall.of(operation, ...)` and hand it to the `PhaseTwoOutbox`, inside the
business transaction. Dispatch then goes straight to the extension's handler: the
aggregate-ID-to-adapter election of the core operations does not apply, and no
process service has to be registered for the call's BPMN process.

Rules the registry enforces at registration time, each with a guiding message: an
operation is registered exactly once, an extension name is namespaced, and the core's
names are reserved. At dispatch time an unregistered name is an error naming the
operation and listing the registered ones. The entry stays in the store (like a
stale adapter ID of a START entry), so an extension temporarily missing from the
application does not silently lose its scheduled work.

Stores never look into the registry: they persist name, args and key and stay
operation-agnostic.

The core does not implement (or depend on) any outbox itself — it only defines the
`PhaseTwoOutbox` contract (stores implement exactly one method,
`schedule(PhaseTwoCall)`):

- **Scheduling:** `schedule(call)` MUST be invoked within the still-running local
  transaction that persists the workflow aggregate and MUST enlist the outbox entry
  in exactly that transaction: the entry becomes visible if and only if the
  transaction commits.
- **Idempotency key:** implementations MUST enforce uniqueness of
  `PhaseTwoCall.idempotencyKey()` (where present) via the store's unique-constraint
  mechanism; a duplicate `schedule` is a no-op returning `false`. For
  `START_WORKFLOW` the key is `workflowModuleId|bpmnProcessId|workflowAggregateId`
  — the storage-level enforcement of "a workflow is started at most once per
  aggregate". The derivation rules per operation are documented on
  `PhaseTwoOperation` and are a persisted contract.
- **Recovery:** every committed-but-unprocessed entry has to be dispatched through
  the `PhaseTwoRouter` right after the commit *and* after an application restart
  (crash recovery), retrying failed dispatches with a backoff.
- **DONE instead of delete:** a successful dispatch marks the entry DONE; physical
  deletion happens asynchronously after a configurable retention
  (`vanillabp.outbox.retention`, default 7 days) — keeping the deduplication window
  open beyond dispatch. Entries failing repeatedly are blocked (ERROR log naming
  module/process/aggregate/operation) and left as a monitorable trail.
- **At-least-once residual window:** a crash between the remote BPMS call and
  marking the entry DONE re-dispatches the entry on recovery. This is accepted
  (eventual consistency); adapters MUST therefore tolerate repeated
  `startWorkflowPhaseTwo` calls — a second call for an already-started workflow has
  to return without starting another workflow instance.
- **START re-dispatch mitigation (minimizes, does not close, the window):** stores
  pass "this entry was dispatched before" to
  `PhaseTwoRouter.dispatch(call, previouslyAttempted)` (the JDBC/MongoDB defaults
  claim entries by incrementing their attempts counter BEFORE dispatching, so a
  recovered/retried entry is recognized; the gruelbox default bridges its entry
  state via a `Submitter` wrapper — there the counter is only incremented on
  FAILED attempts, so a hard crash still re-dispatches without the probe). A
  previously attempted START entry probes the recorded adapter's
  `awarenessOfWorkflowForRedispatch` first: a workflow already known there means
  the previous dispatch succeeded — the entry is consumed without a second start.
  The probe's contract is stricter than the election's: NEVER optimistic (a wrong
  "known" loses a workflow; a wrong "unknown" merely yields the duplicate the
  residual permits anyway) — adapters that cannot query reliably answer
  `UNKNOWN_TO_BPMS`.

Default implementations are provided by the platform integrations (configured via
`vanillabp.outbox.*` — keys, defaults and documentation are modeled ONCE in the
core class `PhaseTwoOutboxProperties`, bound as part of the `vanillabp.*` tree;
applications may define their own `PhaseTwoOutbox` bean instead):

|  Platform   |       Persistence        |                                      Implementation                                      |
|-------------|--------------------------|------------------------------------------------------------------------------------------|
| Spring Boot | JPA                      | based on `com.gruelbox:transactionoutbox` (`spring-boot-integration`)                    |
| Spring Boot | MongoDB                  | own implementation using `MongoTemplate` (`spring-boot-integration`)                     |
| Quarkus     | JDBC datasource (Agroal) | own JDBC/JTA-based implementation (`quarkus-integration`; gruelbox does not support JTA) |

### Telling the application that a workflow ended (`WorkflowEndedInvoker`)

The counterpart of the BPMS-initiated start, and the one handler kind whose whole
point is that nothing depends on it:

- Adapters ask `workflowEndedHandlerExists(module, process)` while wiring and attach
  their listener only where the answer is yes. A model must not pay for a
  notification the application did not ask for, which is why the question exists at
  all instead of a plain "always attach".
- `workflowEnded(module, process, context)` loads the aggregate, calls the method and
  saves it, in the caller's transaction (embedded BPMS) or a new one (remote BPMS).
- A missing aggregate is NOT an error: an application may delete the aggregate of a
  workflow which ended, and a redelivered notification would find nothing either.
  Both cases are logged and skipped.
- What the context reports about the KIND of end is the adapter's honest answer, not
  a normalized fiction: Camunda 7 tells a cancellation from a regular end by the
  execution's delete reason, Camunda 8 never sees a cancelled instance's end.

### Broadcasting signals

A signal is the one BPMS operation which is not about a workflow, so it is the one
place where neither election nor aggregate applies:

- The scope is the WORKFLOW MODULE of the calling process service: each adapter
  broadcasts through ITS own client with ITS tenant, and the signal name is scoped
  like every other identifier of the module (prefixed in `use-prefix`). Crossing
  module boundaries is deliberately left to the application - a module is a scope,
  and which modules a signal is meant for is a business question.
- `MigrationProcessService.sendSignal(name)` fans out over the DEPLOYMENT UNION of the
  workflow module (`getDeploymentAdaptersFor`, story 27), not over the prioritized
  adapters of the calling process service. During a migration the subscriptions are
  spread across the BPMS, and a partial broadcast is worse than none.
- Every adapter is asked before the first failure is reported: a broadcast which
  stopped at the first unreachable BPMS would leave the others waiting.
- Embedded adapters broadcast in `sendSignalPhaseOne` (inside the caller's
  transaction), remote ones get one `SEND_SIGNAL` outbox entry EACH, carrying their
  adapter id - dispatch goes to exactly that adapter, without probing. There is no
  idempotency key: nothing about a signal can be deduplicated.
- The call carries no aggregate ID, which is why `PhaseTwoCall` allows it to be
  absent. The router's `SEND_SIGNAL` dispatch therefore does not convert one.

### Pushing a changed aggregate (`aggregateChanged`)

`MigrationProcessService.aggregateChanged(aggregate, taskId)` is `correlateMessage` with
another verb: save the aggregate, locate the BPMS by probing `awarenessOfWorkflow`, write
in phase one for an embedded BPMS and schedule an `AGGREGATE_CHANGED` outbox entry for a
remote one. A completed workflow is a warned no-op, an unknown one a
`WorkflowNotFoundException` naming that the aggregate WAS saved.

Two decisions are worth knowing:

- **No idempotency key.** The values are read from the aggregate when the entry is
  DISPATCHED, so a redelivered entry writes the then-current state. A key could only ever
  drop a push, never save one.
- **The task id decides the scope and nothing else.** Without one the values belong to the
  workflow's global scope, with one to the scope that task RUNS in (process, embedded
  subprocess, or one iteration of a multi-instance embedded subprocess) - never the task's
  own scope, and never additionally the global one, or the other iterations would see what
  one of them pushed. Which execution or element instance that is, is the adapter's
  business; the core only transports the id (in `ARG_TASK_ID`).

There is no ordering guarantee between outbox entries: the dispatchers select by due time
(`NEXT_ATTEMPT_AT <= now`) without an `ORDER BY`, so a push and a task completion scheduled
in the same transaction may reach a remote BPMS in the other order. That is documented
rather than fixed - inventing an order here would promise something the stores do not
implement, and an application which needs one can keep the calls in separate transactions.

WHAT is pushed stays the sync model's business (story 28). This operation adds no second
way of choosing values, which is what keeps the aggregate the single source of truth.

### Workflows the BPMS starts itself (`BpmsInitiatedStartInvoker`)

A timer, signal or conditional start event produces a workflow nobody asked for - and
therefore a workflow without a workflow aggregate, which is the one thing every other
mechanism needs: tasks are routed by the aggregate's ID, expressions read its
attributes. The core builds it, adapters only report and write back.

Adapters use the SPI (`io.vanillabp.integration.adapter.spi.workflowstart`) twice:

- `validateBpmsInitiatedStarts(module, process, specs)` during `wireBpmn`, with the
  start events of the deployed process the BPMS fires on its own. The core registers
  them and reports an application method serving a process (or a start event) which
  has none. Signal names are reported PLAIN - scoping stays invisible above the BPMS
  boundary (story 35). Throwing honors the `deployment-failure` policy.
- `startWorkflowByBpms(module, process, context)` when the BPMS reports such a start.
  The context carries values only: start event id, kind, trigger time, the BPMS' own
  identity of the start, the variables the model set, and whether the aggregate has to
  be built in the CALLER's transaction (embedded BPMS) or in a new one (remote BPMS).

What the core does, in one transaction: derive the ID, reuse an aggregate already
carrying it (a repeated notification builds nothing twice), otherwise instantiate the
class, write the ID and the variables into it, run the optional
`@WorkflowStartedByBpms` method and save. The result carries the aggregate's ID, the
name of its ID attribute and the variables the adapter writes back - the ID variable
plus the values shared per `@SyncWithBPMS` where the adapter asks for them
(`AggregateSyncMode`).

The ID rules live in `BpmsInitiatedStartId`: what the BPMS identifies the start by
wins (a remote BPMS' instance key, stable across redeliveries), then a timer's trigger
time, then a generated ID - and where none of them fits the ID attribute's type,
nothing is assigned and the persistence layer generates one while saving.

The `@WorkflowStartedByBpms` methods are scanned by `BpmsInitiatedStartScanner` and
held by `BpmsInitiatedStarts`, to which `WorkflowTaskRegistry` delegates the second
adapter-facing interface. Both scanners walk the same classes and share the value
conversion; folding them into ONE pluggable handler contract is the subject of the
extension-enablement story.

An adapter whose BPMS cannot report such a start implements none of this and fails the
deployment of such a process with a guiding message instead - a workflow which could
never obtain an aggregate is better refused than deployed.

### Viewer/history API (read path)

`ProcessService#getProcessDefinitions`, `#getBpmnXml` and `#getWorkflowHistory` are
read-only: no aggregate is saved, no transaction is required and no workflow is
advanced. The BPMS answering is elected by the same probing/caching
`WorkflowLocator` walk as every other operation on an existing workflow — with one
difference: `COMPLETED` is a REGULAR result (viewers show ended workflows), only a
workflow unknown to EVERY adapter raises the SPI's `WorkflowNotFoundException`.

**Composite process definition ids.** `getBpmnXml(processDefinitionId)` addresses a
process DEFINITION, not a workflow — there is no aggregate to elect a BPMS by. The
core therefore namespaces every adapter-native definition id it hands out (also the
one inside `WorkflowHistory#processDefinitionId()`):

```
<adapter id>#<adapter-native definition id>
```

Split at the FIRST `#` (adapter ids are configuration keys and never contain one;
native ids may, e.g. Camunda 7's `MyProcess:1:8a9c…`). The scheme is modeled in
`ProcessDefinitionIds` and is a stable contract: applications may store such ids
(e.g. in a viewer's URL). Adapters only ever see their native ids — the core strips
the namespace before calling `MigratableProcessService#getBpmnXml` and routes to the
adapter named by it (unknown adapter, malformed id or unknown definition →
`ProcessDefinitionNotFoundException`, each with a guiding message).

Adapters answer "I do not know this workflow" with an empty list / `null`; a BPMS
without an element history reports `elementsHistory()` as `null` (the SPI's
"not supported by the underlying BPMS"), and an eventually consistent BPMS reports
what is visible instead of raising an error.

### Aggregate persistence

The core does not know any persistence technology.
`io.vanillabp.integration.spi.AggregatePersistenceAware` (module `business-spi`)
abstracts saving an aggregate and determining its ID. Implementations are provided by
the platform integration (e.g. based on Spring Data) or by the business application
itself; the implementation with the most specific generic type for the aggregate wins.
It is the single canonical interface used on all platforms — business code implements
it regardless of running on Spring Boot or Quarkus.

### Extensions

An extension participates in two steps of the [deployment pipeline](#deployment-pipeline)
through `ExtensionWiringService<BPMN, PC>`:

|                           Method                            |                 Called                  |                                       Purpose                                        |
|-------------------------------------------------------------|-----------------------------------------|--------------------------------------------------------------------------------------|
| `getModelType()`                                            | at wiring time                          | the BPMN model type this extension understands, e.g. Camunda 7's `BpmnModelInstance` |
| `getProcessContextType()`                                   | at wiring time                          | the adapter's processing-context type this extension expects                         |
| `getOrder()`                                                | once, at startup                        | ordering among all wiring services of the same model type (default `0`)              |
| `wireBpmn(module, filename, bpmnProcessId, model, context)` | per executable BPMN process             | inspect the model and wire the extension's own concerns against it                   |
| `startWorkflowProcessing(module, context)`                  | after the module was deployed           | start whatever consumes that wiring (listeners, workers, …)                          |
| `stopWorkflowProcessing(module, context)`                   | on graceful shutdown (default: nothing) | stop it again                                                                        |

**Matching — an extension is either asked, or it is not.** An extension takes part in a
module's deployment only if BOTH declared types are assignable from the adapter's types:
the model type AND the processing-context type. A Camunda 7 extension therefore stays
untouched while a Camunda 8 module is deployed, and an extension declared against
`Object`/`Object` sees every BPMS. This is also the trade-off to be aware of: an extension
that wants to CONTRIBUTE to the adapter's processing context has to declare that adapter's
context type and thereby becomes BPMS-specific, whereas an extension that only reads the
model can stay generic.

**Ordering.** All wiring services of a module — adapters and extensions — are sorted by
`getOrder()` ascending, once at startup. Order matters whenever two of them touch the same
model: VanillaBP's own wiring decides which BPMN elements are served by `@WorkflowTask`
methods, so an extension reacting to that result has to run afterwards (the Business
Cockpit's listeners are consistently ordered last). Shutdown is the mirror image:
`stopWorkflowProcessing` runs on the extensions first (in reverse wiring order), then on
the adapters, so nothing is stopped while something else still feeds it.

**An extension may define its own SPI.** `wireBpmn` is where an extension's own
annotations become alive — the Business Cockpit finds `@UserTaskDetailsProvider` methods
there, just as VanillaBP finds `@WorkflowTask` methods. Such an SPI belongs to the
extension, **never to the VanillaBP core**: the core knows nothing about user-task details,
and an application not using that extension must not see its annotations.

**Registration** is platform-specific and the dummy extensions of both platform
integrations' test modules are the templates:

- *Spring Boot* — contribute a bean of type `ExtensionWiringService`, usually from an
  auto-configuration ordered after `SpringBootMigrationAdapterAutoConfiguration`. All
  beans of that type are collected; since the adapters' deployment services are wiring
  services too, they appear in the same collection and the platform filters them where
  only extensions are meant.
- *Quarkus* — the extension is a Quarkus extension: its runtime module `@Produces` the
  wiring service (`@Singleton`, because such services usually have no no-arg constructor
  and are therefore not client-proxyable), its deployment module registers that producer
  via `AdditionalBeanBuildItem` with `setUnremovable()` — the platform looks the beans up
  through `Instance`, so ArC would otherwise remove them. Unlike adapters, extensions
  announce no build item.

**Configuration** is the extension's own: on Quarkus as an overlay
`@ConfigMapping(prefix = "vanillabp")` if it wants to live under the `vanillabp.*` tree, on
Spring Boot as a second `@ConfigurationProperties("vanillabp")` class. VanillaBP's own
configuration is available as the injectable core object `MigrationAdapterProperties`
(adapter ids, workflow modules, prioritized adapters), which is usually all an extension
needs to know about the setup.

### Adapter/platform version guard (`AdapterPlatformVersion`)

Applications pin the VanillaBP versions themselves, usually by importing
`io.vanillabp:vanillabp-bom`. Maven resolves a version managed by the application
*before* the version an adapter requires transitively, silently and even if that means a
DOWNGRADE — no conflict is reported and the build stays green. An adapter newer than the
platform integration then fails at runtime with `NoSuchMethodError` /
`NoClassDefFoundError` deep inside the adapter.

Two consequences shape the guard:

- **The check belongs to the ADAPTER, not to the platform integration.** Only the adapter
  knows the platform version it was compiled against, and a too old platform integration
  cannot contain a check that was added later. The platform side only provides the
  mechanism.
- **The version numbers have to travel in the JARs.** `migration-adapter-spi` carries
  `META-INF/vanillabp/platform-version.properties` (filled by resource filtering), each
  adapter core carries `META-INF/vanillabp/adapter-<adapter-type>.properties` with its own
  version and the platform version it was built against
  (`platform.version=${adapter-platform.version}`). The per-adapter-type file name keeps
  the descriptors apart when several adapters are on the classpath — the normal case
  during a BPMS migration.

Adapters call `AdapterPlatformVersion.requireCompatiblePlatform(adapterType, someCoreClass)`
in the constructor of their `AdapterDeploymentService` implementation, which runs once per
configured adapter id on both platforms; results are cached per adapter type, failures are
not. Versions are compared by their numeric parts with the qualifier ignored, so
`2.0.0-SNAPSHOT` satisfies a required `2.0.0`; versions that cannot be parsed count as
compatible — the guard must never break a build it does not understand. When it does fail,
the message names the required version and every artifact to raise (starting with the
BOM), following the [configuration/error-message principle](#features) of guiding the
developer instead of just reporting.

## Modules

1. **business-spi:** (artifact `io.vanillabp:vanillabp-integration-spi`)<br>
   Interfaces business code may implement, kept strictly separate from the adapter
   SPI so business code never sees adapter-implementation interfaces:
   `io.vanillabp.integration.spi.AggregatePersistenceAware` — the single canonical
   persistence abstraction used on all platforms — and the outbox contract
   (`PhaseTwoOutbox` incl. `PhaseTwoCall`/`PhaseTwoOperation`, plus the
   per-aggregate attribution `PhaseTwoOutboxAware`): custom outboxes are
   contributed by APPLICATIONS, not by adapters, so these types live here (moved
   from the adapter SPI in story 26i). It is provided to applications
   transitively through the platform support modules (`vanillabp-spring-boot-support`
   / `vanillabp-quarkus-support`).
2. **spi:** (artifact `io.vanillabp.adapter:migration-adapter-spi`)<br>
   The adapter-facing SPI to be implemented by BPMS adapters and platform
   integrations: `AdapterDeploymentService` (extends `ExtensionWiringService`),
   `MigratableProcessService` (incl. `WorkflowAwareness`) and
   `ExtensionWiringService`. Adapters report BPMN parsing errors using
   `BpmnParseException` and guard themselves against a too old platform integration
   using [`AdapterPlatformVersion`](#adapterplatform-version-guard-adapterplatformversion).
   Depends on `business-spi` (uses `AggregatePersistenceAware` in signatures).
3. **runtime:**<br>
   This module implements the runtime behavior according to the
   features [listed above](#features), mainly `DeploymentService`
   (deployment pipeline incl. the shutdown pass and the deployment-failure policy),
   `MigrationProcessService` (per-process runtime used by the
   platform integrations' `ProcessService` beans) and `MigrationAdapterProperties`
   (configuration model incl. validation and deployment-failure resolution;
   the validation is the single, platform-neutral implementation - the platform
   transformers only map platform-specific bindings and check what only the
   platform can know).

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
