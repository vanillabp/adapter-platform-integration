# Writing a VanillaBP adapter for your BPMS

This document is for a team outside this repository which implements the VanillaBP adapter SPI
(service provider interface) for a BPMS (business process management system). Read it once from
top to bottom. It describes the two interfaces you implement and the calls the core expects back
from you. It says what your answers promise. And it says the things about VanillaBP which you
cannot learn from your own BPMS.

Everything here comes from the code of this repository or from a numbered entry of its
[`DECISIONS.md`](../DECISIONS.md). Where a statement is an assumption and not something the code
holds, it says so. Tests of this repository hold what the core promises you, and
[`README.md`](./README.md) names them per section. So if a promise stops being true, a build turns
red here, and you do not find out in your adapter.

## Vocabulary

A *process* is the BPMN model. A *workflow* is one running instance of it. Use the two words
strictly this way, as the SPI does.

An *adapter type* is your implementation as a whole. A short constant such as `camunda7` names it.
An *adapter id* is one configured instance of that type. An application may configure several ids
of one type at once. That is the migration feature, and it is why nearly every rule below is written
per id and not per type.

A *workflow module* is one unit you can deploy: some BPMN files plus the code which serves them. The
file `META-INF/workflow-module` marks it, and the content of that file is the module id. A *workflow
aggregate* is the application's own entity which holds all state of one workflow. It is addressed by
its id. That id is the only thing about it which VanillaBP carries into a BPMS.

Below, the *core* means `migration-adapter`, the platform-neutral part of VanillaBP. The *platform
integration* is the Spring Boot or Quarkus layer. It brings the core to life and hands your adapter
its collaborators.

## 1. What an adapter is, and what it is not

An adapter translates between the core and one BPMS. That job is smaller than it sounds, because
most of what looks like adapter work belongs to the core:

|                      Owned by the core                      |                                                  What that means for you                                                   |
|-------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| Which BPMS holds a workflow (the election)                  | You answer probes about your own scope. You never pick an adapter.                                                         |
| The transaction around a workflow aggregate                 | You are called inside a transaction the core opened, or after it committed. You never open one for the application's data. |
| The transaction outbox and its idempotency keys             | You never persist an outbox entry, and you never derive its key.                                                           |
| The record of the task deliveries VanillaBP processed       | You report an identity per delivery. The core decides what to do with a repeated one.                                      |
| Configuration validation and the startup messages around it | You validate your own keys, in the same shape.                                                                             |
| The viewer and history API the application sees             | You answer it for your BPMS in native ids. The core adds the namespace.                                                    |

Before you build anything, ask which part of it is the same for every BPMS. That part is already in
the core, or belongs there. Building it in an adapter is the most common wrong turn with this SPI.
Version 1 of VanillaBP left eventual consistency to each adapter. That is exactly why an application
could not move from one BPMS to another. If you find yourself writing retry logic, a registry for
idempotency, or a rule about which BPMS should serve a call, stop and ask us.

Five things follow from this, and you should know them before the interfaces.

You build one runtime object per configured adapter id, never one per type. An application moves
from one cluster, one tenancy model or one engine version to the next by running two ids of your
type side by side. An adapter which builds a single instance from the first id it finds breaks that
without a word.

Only you know what makes two such ids DIFFERENT. So the core asks you once at startup, wherever more
than one id of your type is configured (`AdapterDeploymentService.validateDistinctAdapterInstances`).
End the boot for a pair which addresses the same system. Name the property which would tell the two
apart, because the core writes no message here. An embedded engine wants its own database or table
prefix. Camunda 7 answers with `data-source-name` and `table-prefix`. A remote one wants its own
address, credentials, cluster or tenant, and that is how Camunda 8 answers. The Process-Engine-API
cannot tell anything apart, so it refuses the second id outright. Two ids may share one backend on
purpose. That setup is supported, and the scope contract of section 4 keeps them from answering for
each other.

Ids of OTHER types are not handed to you there. Some types still run on the same kind of system:
Camunda 7 and its forks CIB seven and Operaton all use the same tables. So the core also asks every
id `getAddressedSystem()` and ends the boot when two ids of any type answer with the same key
(decision 129). Answer if another adapter type could ever work on your system, and build the key
the way that type builds it: name the kind of system and the values which address it, never your
type. Give a `description` for the message, and a `howToSeparate` with the property keys, because
here the core writes the message and quotes both. If no other type can share your system, keep the
default, which answers nothing.

The id is an identity, not a label. Every outbox entry and every delivery record carries the id it
was written for. If you rename an id, the work which those records still hold open has no owner any
more. The symptom shows up much later: a workflow which was saved and never started (decision 17).
Read the id from configuration. Do not derive it from anything which can change.

Your configuration lives under `vanillabp.adapters.<id>.*`, in the same tree as the platform keys.
You contribute it as an overlay of the `vanillabp` prefix. Do not open a namespace of your own.
Properties which vary by scope are resolved at four levels: a task, then its workflow, then its
workflow module, then the adapter. The most specific configured value wins.

You ship both platforms. A feature which exists only on Spring Boot does not exist. VanillaBP
promises the same behaviour on Quarkus, and on the next platform after it.

You never implement the integration SPI. The platform or the application implements
`PhaseTwoOutbox`, `TaskDeliveryLog`, `TransactionRunner`, `AggregatePersistenceAware` and
`WorkflowAdapterCache`. The core uses them for you. A type from the adapter SPI must never appear in
a module which business code compiles against.

Your messages are part of the SPI, even though no interface carries them. VanillaBP promises that a
developer reaches a working configuration from what the application says at startup, with almost no
documentation. So every failure of yours does three things:

- it names the workflow module, the BPMN process and the workflow aggregate it is about,
- it says what was attempted,
- it gives at least one way out, with the exact property key or the change to make.

Validate at startup, not at the first workflow. For work which already happened, prefer a no-op with
a warning over an exception. Never print a credential. Nothing checks this automatically, but a
review will hold you to it.

## 2. The two interfaces

You implement `AdapterDeploymentService<BPMN, PC>` and `MigratableProcessService<A>`, one instance
of each per configured adapter id. Both live in `io.vanillabp.integration.adapter.spi`, the package
of the artifact `io.vanillabp:vanillabp-adapter-spi`. That artifact is the one dependency your core
needs from VanillaBP. The integration SPI and the extension SPI come with it.

### 2.0 The dependencies you take, and the version you pin them to

Pin one VanillaBP version in a property of your own, and import the platform's BOM (bill of
materials) with it. Then every artifact below is managed, and you write no second version anywhere:

```xml
<properties>
  <adapter-platform.version>2.0.0</adapter-platform.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.vanillabp</groupId>
      <artifactId>vanillabp-bom</artifactId>
      <version>${adapter-platform.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Your `core` module takes `io.vanillabp:vanillabp-adapter-spi`. Your `spring-boot` module adds
`io.vanillabp:vanillabp-spring-boot-integration`. Your Quarkus runtime module adds
`io.vanillabp:vanillabp-quarkus-integration`, and your Quarkus deployment module adds
`io.vanillabp:vanillabp-quarkus-integration-deployment`. For tests there are `io.vanillabp:test-utils`
and the BPMS double of section 7. The artifact which applications use brings the platform integration
along. So an application adds your adapter and nothing else of VanillaBP.

BPMS adapters are not in that BOM, on purpose, and yours should not be either. Adapters are released
on their own schedule, and an application gives your dependency its own version.

The property name `adapter-platform.version` is the convention, because the descriptor of the next
section names it. Nothing enforces it. What matters is that three things are the same version: the
one in that property, the one the BOM was imported with, and the one the descriptor reports. The
descriptor exists to catch these three drifting apart.

### 2.1 The deployment pipeline

`AdapterDeploymentService` extends `ExtensionWiringService`. So its methods are called in a fixed
order per workflow module:

```
readBpmn → prepareBpmn → wireBpmn → deployResources → startWorkflowProcessing
                                                       …
                                                      stopWorkflowProcessing
```

[Deployment pipeline](./README.md#deployment-pipeline) in the core's README draws the same pipeline
call by call, with every call-back you make while wiring.

`BPMN` is your own model type, whatever your BPMS parses a file into. `PC` is a processing context
which you invent. It collects state, and the core passes it through the whole pipeline, from the
first file of a module to `startWorkflowProcessing`. Extensions such as the Business Cockpit join a
pipeline by matching both types. So if you declare interfaces and not concrete classes, more of them
can join you.

|                               Method                               |                               Who calls it, and when                               |                                                           What it has to do                                                           |                                                                                                   What a wrong answer costs                                                                                                   |
|--------------------------------------------------------------------|------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `getAdapterId()`, `getAdapterType()`                               | the core, everywhere                                                               | the id from configuration, the type as a constant                                                                                     | an adapter which answers its type where an id is meant serves one instance for all of them                                                                                                                                    |
| `readBpmn(module, filename, stream, isVanillaBpBpmn)`              | the deployment pipeline, per BPMN file                                             | parse the stream into your model and return one entry per executable process in the file; throw `BpmnParseException` on a parse error | the pipeline owns the stream: if you close it, the next reader breaks                                                                                                                                                         |
| `prepareBpmn(module, existingContext, filename, processId, model)` | per process, before wiring                                                         | rewrite the model for your BPMS and return the context, never `null`                                                                  | this is called once per PROCESS, but all processes of a file share one model. So rewrite once per FILE and note that in the context. Both Camunda adapters guard it. Otherwise you get doubled prefixes and stacked listeners |
| `wireBpmn(module, filename, processId, model, context)`            | per process                                                                        | look at the model and make the wiring calls of section 3                                                                              | a task without a handler is then found by a running workflow, not by the boot                                                                                                                                                 |
| `deployResources(module, context)`                                 | once per module, after all files were wired                                        | push the resources to your BPMS, then report the deployed version per process                                                         |                                                                                                                                                                                                                               |
| `startWorkflowProcessing(module, context)`                         | once per module and adapter, after every adapter of that module finished deploying | open workers, subscriptions, job executors                                                                                            | nothing runs                                                                                                                                                                                                                  |
| `stopWorkflowProcessing(module, context)`                          | on a graceful shutdown, extensions first and adapters last                         | close what you opened                                                                                                                 | handlers are cut off while they run; see the paragraph about shutdown in section 5                                                                                                                                            |
| `getOrder()`                                                       | the pipeline                                                                       | the order among the wiring services which share your model type                                                                       |                                                                                                                                                                                                                               |
| `defaultNameClashAvoidance()`                                      | while deploying                                                                    | what "nothing configured" means for your BPMS; every existing adapter answers `BY_ADAPTER`, the behaviour of version 1                | an application which upgrades without touching its configuration no longer finds its running workflows                                                                                                                        |
| `warnAboutUnscopedIdentifiers(module, fromDefault)`                | once per module and id, when the mode is `NONE`                                    | name the alternatives which YOUR BPMS offers                                                                                          | the default names only what every BPMS can offer                                                                                                                                                                              |
| `validateDistinctAdapterInstances(ids)`                            | once per type at startup, only with more than one id                               | fail the boot where two ids of your type cannot be told apart                                                                         | two ids address one backend without a word, and the election has nothing to tell them apart by                                                                                                                                |
| `getAddressedSystem()`                                             | once per id at startup, after the check per type                                   | the key of the system this id works on, if another type could work on it too                                                          | a second type on your tables or cluster runs next to you, and nothing fails until the work of one ends up in the other                                                                                                        |
| `checkHealth()`                                                    | the platform's health endpoint, on the request thread                              | the cheapest question your BPMS answers, and return within a bounded time                                                             | an exception becomes `DOWN` as a last resort; a connection without configuration is `UNKNOWN`, never `DOWN`                                                                                                                   |

Four of those rows have a default which is a real answer. So leaving them alone is a decision, not
something you forgot. `checkHealth()` reports nothing, which is right for a BPMS without a cheap
question. `getOrder()` is never read for an adapter at all. The core takes the adapters out of that
list before it sorts, and `prioritized-adapters` decides which adapter of a workflow module goes
first. `defaultNameClashAvoidance()` answers `BY_ADAPTER`, and `warnAboutUnscopedIdentifiers` says
nothing. Those two belong together.

That pair decides what every application on your adapter has to configure. So know it before you
write a line. `BY_ADAPTER` means "my BPMS keeps the workflow modules apart itself". On both Camunda
adapters that is a tenant. If your BPMS has no isolation of its own, the default is a promise you
cannot keep. Do not change the default then. Keep it, and refuse the mode while you deploy, with
`scoping.validateNativeIsolationSupported(adapterId, module, whatYourBpmsIs)`. That call ends the boot
with a message which names `use-prefix` and `none` as the ways out. Every application on your adapter
then has to choose one of the two, in every workflow module. Say so in your wiki, on the first page an
application reads, because it is the first thing they will run into.

A failure thrown out of `wireBpmn` or `deployResources` follows
`vanillabp.adapters.<id>.deployment-failure`. The default `fail` ends the boot. With `warn`, an
adapter which is not first in the priority order may fail, and the application still starts. An
application which moves away from an old BPMS needs that when the old BPMS is down. All you do is
throw. The policy belongs to the core (`DeploymentFailureFailTest`, `DeploymentFailureWarnTest`).

In your constructor, call
`VanillaBpParts.requireAdapterFitsPlatform(adapterType, aClassOfYourCore)`. Also ship a descriptor
`META-INF/vanillabp/adapter-<type>.properties`, filled by resource filtering:

```
part.version=${project.version}
part.artifact=${project.groupId}:${project.artifactId}
platform.version=${adapter-platform.version}
```

`<type>` in the file name is exactly what `getAdapterType()` answers, letter for letter.
`adapter-platform.version` is the property of section 2.0. It is a property of YOUR build, and the
platform does not inject it. Switch resource filtering on for that resource. Otherwise the file
ships with the three `${...}` unresolved, and it is read as a descriptor which says nothing. On
Quarkus, register the file as a resource of your extension, so that a native image still holds it.

The platform reads the same file while it boots. If the parts of an application do not belong
together, it ends the boot and names both versions and the dependency to change. Without the
descriptor, nothing can be judged, and the boot says so once. An application which combines your
adapter with a platform integration it was not built for then fails with a `NoSuchMethodError`
somewhere inside your code. Still make the call in your constructor. A platform integration which is
older than the check cannot contain the check, so your adapter is the only part which can report
it.

### 2.2 The runtime service

The core's `MigrationProcessService` delegates to `MigratableProcessService<A>`. It has three groups
of methods: everything the BPMS is asked to DO, everything it is asked ABOUT, and a few switches
which say what your BPMS can and cannot do.

What your BPMS does is one method: `phaseOperations()`. It returns a `PhaseOperationHandler<A>` per
`PhaseOperation`. This is all you write about outbound work. Each operation is defined once in the
core. The definition carries its persisted name, its rule for the idempotency key, which BPMS serves
it, whether every adapter has to serve it, and the words it uses for itself in a message. Only what
it DOES differs per BPMS, and that is your handler (decision 29 in the repository's
`DECISIONS.md`). [An operation is defined once](./README.md#an-operation-is-defined-once) draws what
is left on the interface once the handlers carry the operations.

The core operations today are `START_WORKFLOW`, `START_WORKFLOW_BY_MESSAGE`, `COMPLETE_TASK`,
`CANCEL_TASK`, `COMPLETE_USER_TASK`, `CANCEL_USER_TASK`, `CORRELATE_MESSAGE`, `SEND_SIGNAL` and
`AGGREGATE_CHANGED`. If you leave one out of the map, you say that your BPMS has nothing like it.
That is a valid answer for `SEND_SIGNAL` and for `AGGREGATE_CHANGED`. The application then gets a
`PhaseOperationNotSupported` when it actually asks. For every other operation, the boot refuses your
adapter and names the operation (`AdapterOperationsAtStartupTest`). The map itself is the statement,
and the boot reads the map. Nothing looks behind a handler, because that would need reflection, and
a native image has none.

A handler is a pair of methods. The split between them is the same for every adapter and every
operation: phase one asks, phase two acts (decision 3).

`phaseOne(PhaseOneRequest)` runs inside the transaction which the application called from, and it
only ASKS. Does the parked task still exist? Is a subscription waiting for this message? Does a
deployed model declare it at all? Where your BPMS offers a lock for what phase two will do, taking
that lock is asking as well. If you throw here, the caller's transaction fails, and that is the
point: the stack trace still points at the business code which made the call. Doing nothing here is
a valid answer. Starting a workflow is the case where there is nothing to ask about.

`phaseTwo(PhaseTwoRequest)` runs after that transaction committed, on the thread of the outbox
dispatcher. It is the only place where your BPMS is changed.

Phase one must never move the process forward, whether your BPMS is remote or embedded. People get
the embedded case wrong, so here is the full reason. An engine command can lose a concurrency
conflict. It cannot be repeated inside the caller's transaction, because the conflict marks that
transaction as rollback-only. If you repeat just the engine part in a transaction of its own, the
process moves forward while the application rolls back. No switch lets you act in phase one. There
used to be one. No adapter ever used it, and it was removed, not renamed (decision 26). An adapter
which starts a workflow or completes a task in phase one breaks the contract without a sign. The
core schedules phase two anyway, and the operation happens twice.

`PhaseTwoRequest` gives you the aggregate's ID, not the aggregate. Phase two runs after the commit,
so load whatever you need there. Its `activationId()` names the element instance the operation was
planned in. It travels only for operations whose definition says so. Read it if your BPMS removes
duplicate messages with a net of its own. Three elements of a multi-instance call activity reach the
outbox as three operations. Without the activation id, they would reach such a BPMS as one message,
and VanillaBP cannot repair that from its side.

Phase two of a START says which workflow it created. That goes to
`PhaseTwoRequest#reportStartedWorkflow(String)`. It is the only moment anybody ever learns the id:
`phaseTwo` returns nothing, and the instance comes into existence after the caller's transaction
committed. The core writes the pair down next to the aggregate. From then on, an operation about that
workflow is no longer elected by probing every configured adapter. And an extension can name the
workflow even on a BPMS whose read model has not caught up with the start yet.

Call it once, with the id of the instance which the AGGREGATE is. On a BPMS with call activities,
that is the top parent. The instances created below it belong to tasks and travel with those tasks,
so nothing here walks a chain of parents. Every other operation leaves this alone. An adapter which
has nothing to report simply does not call it. Then no row is written, and everything stays as it was
before this call existed.

A workflow which your BPMS started on its own is written down by the start handler. There, the core
writes down `BpmsInitiatedStartContext#getNativeInstanceId`, and that value is no longer for messages
only. The start handler writes the row only where the `@WorkflowStartedByBpms` method named the
workflow, and for a start by message. Where it finds the workflow aggregate of a start which the
application made, it writes nothing. That row belongs to the dispatch, and so to your report above.
Two writers of the same row in two transactions end one of the two transactions on MongoDB. A start
by message is the exception, because the answer to a published message may not name the instance.
Camunda 8 answers with the key of the message. So report the id of a start wherever your BPMS gives
it to you. Decision 131 in the platform's `DECISIONS.md` has the reasons.

If your BPMS counts the versions of a process, report the version with the id.
`PhaseTwoRequest#reportStartedWorkflow(String, String)` takes the version of the process definition
the workflow was started on. Use the same form in which `TaskInvocationContext#getProcessVersion`
reports it for a task of that workflow. Call this method instead of the one with the id only, never
both. An extension which shows details per version reads the version from that row. On a BPMS which
answers from a read model, the row is the only place to read it in the first seconds. The core knows
which adapters count versions from the `ProcessVersionCatalog` you register while you wire your BPMN.
If you register one and report a start without a version, the core logs a WARN once per BPMN process
and writes the row anyway. A start which your BPMS fires itself, and every delivery, already carry
the version, through `BpmsInitiatedStartContext#getProcessVersion` and
`TaskInvocationContext#getProcessVersion`. So nothing more is asked there.

What your BPMS is asked ABOUT are the four awareness probes and the read-only viewer methods. The
probes are section 4. The viewer methods are `getProcessDefinitions`, `getBpmnXml` and
`getWorkflowHistory`. They have no phases and no transaction. Their defaults throw a message which
guides the developer. So a BPMS which cannot serve them says so by not overriding them. Definition
ids are yours and native. The core puts them into a namespace per adapter id before the application
sees them, and takes the namespace off again before it calls you back. An empty list or `null` means
"this adapter does not know it". The core turns that into the exception the SPI documents. A BPMS
which records no history of its elements reports that history as `null`. That is an answer, not an
error.

The remaining methods are switches. Each of them is small, and each of them changes what the core
does:

|                  Method                  |    Default    |                                                                                                                                                                                                                                    What it decides                                                                                                                                                                                                                                     |
|------------------------------------------|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `deliversTasksAtLeastOnce()`             | `false`       | say `true` where your BPMS learns the outcome of a task only AFTER the application committed locally. That is every remote BPMS, and an embedded one on a datasource of its own. The default is right only where the delivery shares the application's transaction, because a repeated delivery there proves that nothing was committed. It decides whether a missing delivery log is worth a guiding message at startup. It does not switch deduplication on; `getDeliveryId()` does. |
| `isPhaseTwoFailureRepeatable(Throwable)` | `true`        | `false` blocks the outbox entry after one attempt, instead of retrying it. Say it only for what your BPMS answers the same way every time: a malformed request, or an identifier which does not exist. Keep that list short. Repeating is the safe answer.                                                                                                                                                                                                                             |
| `workflowVisibilityDelay()`              | none          | how long an `UNKNOWN_TO_BPMS` of your BPMS may still turn into `ACTIVE`. Report a window where your probe reads a model which lags behind the engine. Make it no longer than it really needs: it is the due time an outbox entry gets before it asks again, and every one of those costs the entry an attempt.                                                                                                                                                                         |
| `workflowVisibilityDelay(workflowId)`    | the one above | the same window, asked for ONE workflow. This is the question the core actually asks. See section 4.2.                                                                                                                                                                                                                                                                                                                                                                                 |
| `canLocateWorkflows()`                   | `true`        | whether your workflow probe asks your BPMS or guesses. See section 4.                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `openTaskCount(module, process)`         | `null`        | how many tasks of a process your BPMS holds open. The core asks once at startup, so that an upgraded application learns how long its unrecorded deliveries can still surprise it. Answer only if your BPMS counts and you read the number. If you fetch the tasks to count them, the boot grows slower with every year the application runs (decision 19).                                                                                                                             |

## 3. What the core hands you, and what you have to hand back

### 3.1 The collaborators arrive in one object

Your constructor takes an `AdapterCollaborators`, which the platform integration builds for one
adapter id. Never take a collaborator by setter. This SPI was reshaped because of a defect. A
registrar forgot one setter. The adapter deployed its files and ran its tasks, but it never reported
the end of a workflow, and nothing failed anywhere (decision 28).

Five collaborators are always there. A set built without one of them throws and names your adapter
id (`AdapterCollaboratorsTest#aMissingMandatoryCollaboratorIsRefused`):

`workflowTaskWiring()` is what you call while you read a BPMN file. `workflowTaskInvoker()` is where
a delivered task goes at runtime. The same core object implements both. They are two interfaces,
because a mandatory call which an adapter can forget will be forgotten one day. For a year, the
Camunda 7 adapter left out the check that every `@WorkflowTask` method matched a task somewhere. So a
typo in a task definition stayed silent until a workflow reached that task. That check belongs to the
core now, and section 3.2 ends with it.

`scoping()` is `NameClashAvoidanceSupport`. It is how you keep the identifiers of two workflow
modules apart. `workflowAggregateSync()` tells you which values of an aggregate your BPMS may see.
`preCommitRegistrar()` is where you hang a check which has to run right before the caller's
transaction commits. That makes the window smaller in which its answer can go out of date.

Scoping needs its own paragraph. The core only ever speaks plain identifiers, and everything about
them which is specific to a BPMS is yours. `modeFor(module, process, adapterId)` answers which of
three modes applies. What a mode MEANS is up to your BPMS.

- `BY_ADAPTER` uses your BPMS' own isolation: a tenant on both Camunda adapters, and whatever plays
  that part on yours. Every adapter reports it as the default. Version 1 did this, and an
  application which upgrades without touching its configuration has to find its running workflows
  again.
- `USE_PREFIX` puts the workflow module id in front of the identifiers your BPMS sees, and takes it
  off again on the way back. This is the answer where a vendor charges a license per tenant.
  `scopedProcessId` and its siblings give you the one form, `plainProcessId` and its siblings the
  other.
- `NONE` scopes nothing. Every adapter reports it per workflow module, and names the alternatives its
  own BPMS offers.

While you deploy, refuse a mode which your BPMS cannot serve. Do not put every workflow module into
one scope instead. Your isolation may be configured while no level asks for `BY_ADAPTER`. Then hand
that property key to `validateNoneNameClashStrategy`. The setting and the mode contradict each
other, and nobody wants the setting to be ignored without a word. On your side, it comes down to one
habit. Scope on the way out, at every boundary your BPMS sees. Unscope on the way back, and that
includes the identifiers of an inbound delivery.

One more call on the support belongs to the same subject. Make this one after your deploy command
returned. `validateNoCollidingProcessIds` is the one check here which ends a boot. If two BPMN
processes share one identifier, your BPMS holds one definition and loses the other. Hand over the
processes of the workflow module you are deploying, with their PLAIN ids. The core remembers what
your earlier calls in this boot passed, per adapter id. So it does not matter how many processes
your collection holds. A collision with a module deployed before this one is found either way, and
your other adapter id never sees these processes.

That check asks you one question back. It is the only question of this SPI which is about your
isolation and not about your models. `ownIsolationSeparatesWorkflowModules(one, another)` answers
whether your BPMS would put those two workflow modules into different scopes of its own. The core
needs it because `by-adapter` adds no prefix. The identifiers stay plain. So two modules which your
tenant keeps apart look exactly like two modules which collide, and only you can tell the two cases
apart. Answer about the scope you would REALLY deploy those two modules to, not about the
configuration you read. A `tenant-id` can be resolved per adapter, per workflow module and per
workflow. So resolve it for each of the two, and compare the results. Two modules can land in one
tenant without a single line of the application saying so. A module which would reach your BPMS
without any scope has a scope like any other, and two of those are not separated. The default
answers `false`, which means "my isolation separates nothing". That is the right answer for a BPMS
without isolation. It is also the safe side while you have not written the method yet, because the
check then refuses instead of letting a collision through.

`validateNoCollidingProcessIds` compares what this boot deploys against itself. It never sees an
identifier which another application put into your BPMS years ago. If you can ask your BPMS about the
identifiers it holds, ask it for the ones this workflow module is about to deploy. Hand the hits to
`reportIdentifiersTheBpmsAlreadyHolds(adapterId, workflowModuleId, found)`. A class of its own is the
right size for this. It holds the query and whatever you can find out about a hit, plus the one call
from `deployResources`. If you cannot ask your BPMS, call nothing. Silence means "not asked"
everywhere in this SPI. There is no value for "cannot tell", because the core forms no verdict of its
own here.

Each finding is an `IdentifierHeldElsewhere`, and you fill every part of it. The `kind` says which
scoped form it is. A `bpmnProcessId` comes with a task definition and with nothing else. The
identifier is the PLAIN one, without your prefix and without your tenant. The core builds the form
your BPMS sees, so the warning can name both forms and the property which produced them. `heldBy` is
a sentence you write, because only you know what your BPMS can say about a holder. On one engine
that is a deployment name and a source, on the next a resource name and a definition key. And
`certainlyForeign` says whether you can PROVE that the holder is not an earlier deployment of this
application. Usually you cannot. Our own previous version matches an identifier equal to ours first,
and no engine records which application deployed a definition. So answer `false`. The message then
says so, and does not present your guess as a fact.

None of this may fail a deployment. Wrap the query so that a failure is logged at debug level and
nothing else, the way the checks around it already do. Where your filter takes a list, ask once per
workflow module, not once per identifier. A finding is a warning on the core's side too, because the
deployment which holds the name may belong to an application which runs correctly.

You can ask your BPMS this way about a BPMN process id and a DMN decision id, if it keeps a
repository to search. A message name, a signal name, a BPMN error code, an escalation code and a task
definition are in no index. That does not mean nobody can answer. Those names live in the model, and
your BPMS hands its models back. Whether a task definition is scoped at all depends on your BPMS: it
is local to a process on Camunda 7, and a job type for the whole cluster on Camunda 8. But it costs
one model read per version your BPMS holds. A start which does that grows slower with every year the
application runs, and decision 19 of this repository's DECISIONS.md forbids that. So do not sweep the
models for this. Answer the two questions below instead, where the reading is already paid for.

The first one costs you nothing at all. While you prepare a model, you scope every message name,
signal name, error code and escalation code through `scoping.scopedIdentifier(...)`. If your BPMS
scopes task definitions, you scope every one of them through `scoping.scopedTaskDefinition(...)`. So
you hold all of them, and the core holds none. Name the BPMN process on a task definition, and leave
it `null` on the rest. A task definition is scoped per process, unless the application switched that
off. Where your BPMS keeps task definitions local to a process, report none of them. The core needs
no flag for it. Where your BPMS subscribes to them across the whole cluster, this is the most
expensive line of the whole check. If two workflow modules share one job type, the worker of one
fetches the jobs of the other. Hand them over per workflow module once you deployed it, with
`reportIdentifiersTheModelsDeclare(adapterId, workflowModuleId, declared)` and one `ModelIdentifier`
per name, PLAIN and without your prefix. The core then warns where two workflow modules of the
application end up under one scoped form. Prefixing cannot produce that clash, but `none` and a single
configured tenant can. Under `by-adapter`, the core words the line as a question, because only you
know whether your isolation really separates the two. Pass a name once per module. The same name in
several of its processes is normal, and the core treats it that way.

The second one is `identifiersOfVersion(module, process, version)` on your `ProcessVersionCatalog`,
next to `tasksOfVersion` and `startEventsOfVersion`. It answers which of those names ONE version
declares which your BPMS still holds. Read it from the model your BPMS hands back, plain again. The
core asks it in the loop which already reads the model of that version. So on an engine which caches
parsed definitions, it costs nothing. If your read goes over the wire, keep the model while the core
works on that version, and drop it afterwards. The core asks several questions per version and keeps
no model of its own. Answer `null` where you cannot read a model your BPMS holds. Answer an empty
collection where you read one and found no such name.

Sometimes you hold the support in a place where it may be missing: a unit test, or a helper built
without a platform around it. Do not write `scoping == null ? plain : scoping.scoped…` yourself
there. The same six methods exist as STATIC ones which take the support as their first argument
(`NameClashAvoidanceSupport.scopedProcessId(scoping, module, process, adapterId)`). They answer the
plain identifier where there is nothing to scope by. Two copies of that check written by hand will
disagree one day. A disagreement here is a query which finds nothing, and it looks exactly like a
workflow which does not exist.

Two collaborators arrive as `Optional`, because an application which never asks for them has nothing
to report to: `workflowEndedInvoker()` and `bpmsInitiatedStartInvoker()`. Work without them. Both
platform integrations do provide them. So an adapter built without one is nearly always a
registration which left it out, and the build says so in a warning which names your adapter id.

Two things are not in the object, and they stay your own constructor arguments. The first is what
you resolve from your own configuration: a job timeout, a retry backoff, the variables your worker
fetches. The second is what your own extension contributes.

### 3.2 What you call while you deploy

These are calls on `WorkflowTaskWiring`. You make them from `wireBpmn`, per BPMN process. You are the
only one who can read your own BPMN dialect. That is why everything the core needs to know about a
model arrives here.

|                                      Call                                       |                                                                                                                                           Why it is yours                                                                                                                                           |
|---------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `validateTaskWiring(adapterId, module, process, tasks)`                         | every BPMN task has to be served by a `@WorkflowTask` method, or be marked with `implemented-externally=true`. That includes a user task and a listener. All defects are collected into one message. Call it for the claimed processes. A process nobody claims is left alone, see below            |
| `isImplementedExternally(adapterId, module, process, task)`                     | whether the application says that something else serves a task. Ask it where you refuse a shape of your own before the core sees the task. Hand the task to `validateTaskWiring` either way                                                                                                         |
| `isClaimedByAWorkflowService(module, process)`                                  | whether a workflow service claims the process. Ask it before you change a model, wire a task, open a worker or run a check of your own. A process nobody claims gets none of that                                                                                                                   |
| `taskParameterNames(module, process, taskDefinition)`                           | if your BPMS ships a variable payload with a delivery, you have to know the names before you subscribe. The answer is the sorted union over the methods which serve that element in different process versions                                                                                      |
| `extensionTaskParameterNames(module, process, keys)`                            | the same for the methods of the extensions, if your BPMS hands a task to ONE channel and an extension reads what that channel delivered. Pass the element id first, then the task definition, and fetch the union of both answers. A BPMS where an extension opens its own channel does not need it |
| `multiInstanceElementNames(module, process, taskDefinition)`                    | for which iterations the methods serving that element want the ITEM. Read your multi-instance elements. Refuse a model which hands over no item where a handler asks for one. An id your model does not know belongs to a caller, and is no finding                                                 |
| `workflowTaskCompletesAsynchronously(module, process, taskDefinition)`          | refuse a wiring which cannot keep a task open, while the application starts and not later as an incident                                                                                                                                                                                            |
| `workflowsShareTheWorkflowAggregate(module, process, otherProcess)`             | a called process which runs on the same aggregate has to get the caller's identity. One which runs on its own aggregate must not get it                                                                                                                                                             |
| `reportConcurrentTokenElements(module, process, elementIds)`                    | the elements which can put a second token into a running workflow. The core warns where the aggregate has no version attribute                                                                                                                                                                      |
| `reportCompensation(module, process, compensations)`                            | the compensation throw events which start MORE THAN ONE handler, each with its handlers. It is the same second token, drawn differently: from that event on, the workflow holds one token per handler. A throw event which starts one handler is not reported                                       |
| `registerProcessVersions(adapterId, module, process, catalog)`                  | only where your BPMS can place version tags. The core asks you again after the deployment, for the ids which the application declared without a model. See below                                                                                                                                    |
| `reportNoProcessVersionCatalog(adapterId, module, process, reported)`           | where your BPMS counts no versions. Registering nothing says "not asked yet". This call says "there is nothing to ask". Only the second lets the core name the methods which wait for a version that never comes                                                                                    |
| `unsharedWorkflowAggregateProperties(module, process, names, adapterDefault)`   | the identifiers your models read which the aggregate does not share. So the developer hears about it at startup                                                                                                                                                                                     |
| `unsharedWorkflowAggregatePaths(module, process, paths, adapterDefault)`        | the same question for a whole dotted path, if you can read one out of your model. The core walks the declared types and names the segment which stops the path. Where the types cannot decide, it says nothing                                                                                      |
| `declaredTypesOfWorkflowAggregatePaths(module, process, paths, adapterDefault)` | the type each of those paths ends at. Ask it where your BPMS stores a value in a format which may hand back another type. Only a path which reaches a shared value gets an answer, and you judge what the type costs                                                                                |
| `resolveWorkflowAggregateIdName(module, process)`                               | the variable name under which a BPMS without a business key stores the aggregate's id                                                                                                                                                                                                               |

A `BpmnTaskSpec` carries the activity id and the task definition. It also says whether the element
is a user task, and whether it is a listener on the element and not the element itself. Hand a
listener over with `BpmnTaskSpec.listener(...)`. A method serves a listener only by its task
definition, because the element id names the element, and the element may have a method of its own.
If nothing serves a task and nobody marked it, the start ends, and the message names the line to
copy. So do not write a refusal of your own for that case. If you have one anyway, for a shape your
BPMS cannot hand to a method, end it with `ImplementedExternally.howToMark(...)`. Then the developer
reads the same sentence on every BPMS.

The spec carries one more thing: the `name` attribute which the modeller wrote on the element. Fill
it. You walk that element anyway, and you are the only one who can read your BPMN dialect. Otherwise
an extension which needs the label a person reads in a task list would parse the same bytes a second
time. Nothing VanillaBP decides depends on it. The core keeps the names of the tasks you hand to
`validateTaskWiring`, and answers `ExtensionHandlers#bpmnTaskNameOf(module, process, activityId)`
with them. For an element without a name, the answer is nothing, not a guess. The constructors and
the `userTask` factory without a name did not change. So an adapter which does not read a name still
compiles and behaves as it did.

If your BPMS can start a workflow by itself, ask `BpmsInitiatedStartInvoker` to validate the start
events you found. Throw where your BPMS cannot report such a start at all. A workflow which runs
without an aggregate is worse than a failed deployment. That validation is also where the application
learns that a start event of its process has no `@WorkflowStartedByBpms` method. So the call matters
even for an application which wrote no such method. Ask `WorkflowEndedInvoker` whether a handler for
the end of a workflow exists at all, and attach your listener only where it does.

If you can read your model, report the messages which start a process with
`BpmsInitiatedStartInvoker.reportStartMessages(adapterId, module, process, names)`, once per
executable process. The names are the plain ones, so strip your prefix the way you do for signal
names. Report an empty collection for a process without a message start event. Leave out the start
events of event subprocesses. The core then refuses a `startWorkflowByMessage` whose message does not
start the process of the caller, before phase one. Sometimes you cannot name every message start
event of a process, for example because a name is an expression. Then make no call for that process.
The core does not check that process, and it says so once while the application starts. The same
happens to every process of an adapter which never makes the call.

Where your BPMS can narrow the start itself, do that too. A correlation which can only reach the
process it was meant for is still right when the check of the core does not apply.

If your BPMS counts no versions of a process, say so with `reportNoProcessVersionCatalog`. It costs
one call per process, and the application gets a clear answer for it. A method whose `version` names
a number or a range cannot run on such a BPMS. Until you say so, nobody knows whether the engine is
the reason or an adapter which was not finished yet. Then the messages about it point the wrong way.
`ReportedProcessVersion` is what a delivery of your BPMS carries instead: `VERSION_TAG` where the
engine hands over the tag of the model, `NONE` where it hands over nothing. The core names the
methods which never run there, as a warning. The same method can be the right one on another BPMS
the application is configured for.

At the end of `deployResources`, call `registerDeployedVersion` per process. Call it also when your
BPMS deployed nothing because nothing had changed. Only you can find out which version it ended up
with. The core needs that border between the model of this boot and the older ones.

The checks per module belong to the core, not to you. Once the last adapter of a workflow module has
finished deploying, the core makes four calls on `WorkflowTaskWiring`. An adapter makes none of them.

Before any of that, while the files are read, the core ends the start over a process which nobody
claims. The exception is a process which the application marked with
`vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`. Nothing is
deployed then. So if a process nobody claims reaches your `wireBpmn`, the application said that it
belongs to somebody else. Leave it as it was modelled:

- write nothing into its model beyond what the whole file needs (a prefix, for example),
- open no worker or subscription for it,
- attach no listener,
- run no check of your own which ends the start or warns because of it.

A process which somebody else deployed into the same BPMS gets nothing at all. The platform decided
this in decision 120 in the repository's DECISIONS.md.

The four calls run per workflow module, and their order matters.

`registerVersionsOfProcessesNobodyDeployed` comes first, once per adapter of the module. It brings
the core back to your deployment service after everything was deployed, with
`processVersionCatalogOf(module, process)`. It asks what your BPMS holds for a BPMN process which the
application declares without bringing a model for it. Renaming a BPMN process leaves exactly that
behind. The old id stays in the BPMS, with every version ever deployed under it and with the
workflows which still run on them. With `@WorkflowService(secondaryBpmnProcesses = ...)`, the
application says that it keeps serving them. Answer with the same catalog you would have registered
while wiring. Search by the id you would have deployed it under (your prefix, your tenant). Answer
`null` where your BPMS cannot be asked about the versions of a process. That is the default, and if
you answer `null`, nothing changes for your adapter.

A catalog is asked one question about the process and a few per version it holds.
`deployedVersionsOf` and `resolveVersion` place the version ranges which name a tag.
`activeInstanceCountOf` says how many workflows still run on a version. `whatOlderVersionsMiss` names
what a model your adapter did not rewrite will never get. The rest read a model your BPMS still holds:

- `tasksOfVersion` for the tasks of that version, so the core can tell whether the application still
  serves them,
- `startEventsOfVersion` for the start events your BPMS fires on its own in that version,
- `concurrentTokenElementsOfVersion` for the elements which can put a second token into one of its
  workflows.

Each of them is the same walk you already run while wiring, only over a model your BPMS holds. So
write it once and use it in both directions.

`startEventsOfVersion` exists for the id which a renamed process left behind. Nothing wires such an
id while an application starts. So nothing judges the `@WorkflowStartedByBpms` methods kept for it,
unless you can say what the held models start on. Meanwhile your BPMS may fire the old timer every
day. `concurrentTokenElementsOfVersion` is asked only about a version on which workflows still run.
It is asked because those workflows run longest. Say your newest model dropped a parallel gateway.
That gateway still forks every workflow started before the change. And an aggregate without a version
attribute loses an update there, just as it would in the model just deployed.

Every question which reads a model answers `null` where your BPMS cannot read that model. The check
then says nothing about that id, and does not judge it by an answer you do not have.

The same border holds for every check your adapter makes against a BPMN model. Its verdict must not
depend on which application version deployed the model it judges. Wherever your BPMS can be asked,
read what it holds for the ids the application declares. Where it cannot be asked, say nothing, and
do not refuse. Decision 38 of the platform's DECISIONS.md holds the rule, and also its other side: a
refusal judges the model which is being deployed. A model your BPMS already holds is only being read,
and gets a warning at most.

Serving those workflows is a second matter, and that one IS yours.
`taskWiringOfProcessesNobodyDeployed(module)` on `WorkflowTaskWiring` names the BPMN processes which
the application declares without a model. For each of them, it says what its `@WorkflowTask` methods
serve. You may ask it any time after the module was deployed. Read an entry as "what I build my own
identifier from", not as an identifier of your BPMS. Today it is the `taskDefinition` which a method
names, and what an application may name its wiring by can grow. Whether you have to act depends on
how your BPMS hands out work. Where a task is addressed by a name which does not carry the process
id, such a workflow is served without anybody doing anything. Then there is nothing for you to do
here. Camunda 8 under `use-prefix` is the other case. There, a job type is scoped by the process it
was deployed with. So the jobs of the workflows under the old id carry a name which no worker of the
renamed application asks for. Nobody notices, because a job nobody fetched has not failed. Open a
subscription per task definition of the old id, or wire the models which your BPMS still holds under
it. While you start, say what you cannot reach. An id may come without any task definition. A method
wired to a BPMN element id names none, and without a model there is nothing to read one from.

`validateNoUnwiredWorkflowTaskMethods` comes after those versions, on purpose. It is the reverse of
the wiring check: it holds every `@WorkflowTask` method against the tasks of the whole module. Until
the core knows what the BPMS still holds, a method kept for a renamed process looks exactly like a
method wired to nothing. The check judges what your `validateTaskWiring` calls marked as wired. That
is the second reason to make that call for every executable process of a file.

`resolveProcessVersions` places the version ranges which name a tag. It uses the catalogs you
registered while wiring, and the version you reported for this boot. It comes after the reverse
check, because a method which serves no task at all is the more basic defect.

`reportExtensionHandlerWiring` runs last and judges nothing. It writes down what the handler methods
of the extensions were wired to in this module. A developer whose extension method is never called
reads this line.

### 3.3 What you call at runtime

`WorkflowTaskInvoker.invokeWorkflowTask(module, process, context)` is the one call which matters.
You build a `TaskInvocationContext` per delivery and make that call on your own thread. The core
then does the rest: it finds the handler, opens a transaction, loads the aggregate, binds the
parameters, runs the method, saves the aggregate, writes the delivery record and hands you back a
`WorkflowTaskOutcome`. [Workflow-task processing](./README.md#workflow-task-processing) draws the
context, and the two other ones you build, next to the delivery they belong to.

What your context answers decides how much of VanillaBP works for your BPMS:

|           Method            |                                                                                                                                                                                                                               What it is for                                                                                                                                                                                                                                |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `getTaskDefinition()`       | routes to the `@WorkflowTask` method                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `getWorkflowAggregateId()`  | the aggregate's id in serialized form                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `getTaskId()`               | the id by which a method with a `@TaskId` parameter completes the task later                                                                                                                                                                                                                                                                                                                                                                                                |
| `getTaskKind()`             | which kind of task `getTaskId()` is the id of, `TASK` or `USER_TASK`. The two kinds of id live in separate namespaces. So the command of the other kind finds nothing under such an id, and applications really do call `completeTask` with the id of a user task. The kind travels into the record. VanillaBP reads it back from there to say which kind an id is when no BPMS knows it. Report it. Do not leave it to be read from your job type                          |
| `getTaskEvent()`            | created or cancelled, for the notifications which a user task produces                                                                                                                                                                                                                                                                                                                                                                                                      |
| `getDeliveryId()`           | the identity of the DELIVERY: the same across redeliveries of one task, different for a new task instance. Without it, the core cannot tell a redelivery from a new task, and runs the handler again. It still writes a record for that delivery, because the record also says which BPMS holds the task and which kind of id its id is. So leaving the id out costs the deduplication and nothing else                                                                     |
| `getActivationId()`         | the identity of the ACTIVATION: different between two activations of one element, and silent about redeliveries. It is not the delivery id under another name. Answering it with the delivery id is right only where your BPMS happens to name deliveries after element instances                                                                                                                                                                                           |
| `getBpmnElementId()`        | the `id` which a modeller wrote on the element being delivered. The core routes by it as well as by the task definition, because a method may be wired by either. So an adapter which leaves it out cannot serve a method wired by the element id. It also travels into the delivery record, and an extension uses it to find the task in the model. Wherever your BPMN task carries a task definition, `getTaskDefinition()` gives a different answer                      |
| `getWorkflowId()`           | your BPMS' own id of the running instance. Nothing is routed by it either, because VanillaBP addresses a workflow by its aggregate. It travels into the record, for whoever follows a task into the tools of your BPMS                                                                                                                                                                                                                                                      |
| `getAdapterId()`            | a delivery proves which BPMS holds the workflow, and the core remembers it                                                                                                                                                                                                                                                                                                                                                                                                  |
| `getBusinessKey()`          | the business key your BPMS keeps for this workflow, if it keeps one at all. A business key counts only where it carries the workflow aggregate's id. So the core compares the two, and refuses the delivery where they disagree. Leave it out where your BPMS has no such thing. Where your BPMS keeps the id IN the business key, answer it with the value you took the aggregate's id from (decision 69)                                                                  |
| `getProcessVersion()`       | matched against the `version` attribute of the annotations                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `getTaskParameter(name)`    | values which the MODEL produced, never values of the aggregate. Return the value the way your BPMS deserialized it, and convert nothing. The core converts it into the type the handler declared, so one handler does not answer differently per BPMS (see "What a `@TaskParam` may be declared as" in [README.md](./README.md))                                                                                                                                            |
| `getMultiInstances()`       | index, total and element of a multi-instance activity                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `runInCurrentTransaction()` | whether the delivery already runs inside a transaction in which the application's persistence takes part. Only in that case does the core join, instead of opening a transaction of its own. A transaction of your BPMS which that persistence does not join does not count                                                                                                                                                                                                 |
| `predatesDeployedVersion()` | whether this delivery belongs to a workflow which was already running before the version this boot deployed. Nothing is routed by it. It turns the report about a task which is open too long into an "at least". Such a task was open before VanillaBP ever wrote a record for it, and its age counts from the first delivery after that. Answer it by comparing two values you already hold, never by asking your BPMS. Answer `false` where your BPMS counts no versions |

Then act on the outcome:

- A completed task is completed with the shared values. Read them through
  `syncedWorkflowAggregateValues`, or through its `...InCurrentTransaction` variant where the
  handler's transaction is still open. An embedded engine needs the second.
- A BPMN error is thrown into the model by whatever means your BPMS has. The aggregate's changes stay
  committed.
- A pending completion means you leave the task open. The application completes it later by its task
  id.

WHICH values are shared is never your decision. `workflowAggregateSync()` answers that, from the
aggregate's `@SyncWithBPMS` model and the default your adapter declares. What is left to you is when
and where the values land. An embedded engine writes them inside its own transaction, at every point
where it touches the workflow. A remote one attaches them to every command it sends for that
workflow. Next to those values travels one technical variable. It is named after the aggregate's id
attribute, and `resolveWorkflowAggregateIdName` gives you its name. Where your BPMS has a business
key, the business key takes its place. It is not part of the shared map, and it does not depend on
the model at all. Write it with every command. That includes an aggregate annotated
`@NoSyncWithBPMS`. Such an aggregate shares nothing, and nobody could find its workflow afterwards
without the variable (decision 10).

Where your BPMS keeps a business key, write the aggregate's id into it whenever you start a workflow.
Report what it holds through `getBusinessKey()` whenever you hand a workflow back. The core enforces
one rule on that value: a business key counts only where it carries the workflow aggregate's id. A
workflow which was started without VanillaBP can carry a key which somebody else chose. Then two
values say different things about one instance. The core refuses such a delivery and does not pick
one of them. So nothing of yours has to notice it, and your BPMS handles the refusal like any failing
task. An empty answer contradicts nothing. For a BPMS without business keys, it is the whole answer
(decision 69).

Any exception which the core throws back at you means: the transaction was rolled back, and no record
was written. Apply your BPMS' retry rules, and never report the task as completed. The core already
logged a version conflict on the aggregate once, with a guiding message. VanillaBP does not retry it
on purpose, because a handler may have called a remote API before the commit failed.

One of those exceptions has a type of its own, because it is not about the application which got it.
`DeliveryOfAnUnknownWorkflowException` says that the application holds no workflow aggregate with the
id your delivery named. Where a BPMS serves more than one application, the usual reason is that the
workflow belongs to another one of them. Its message explains that to whoever reads your BPMS. So put
the message where such a reader looks: an incident, an error text, whatever your BPMS shows. Its
getters carry the same facts one by one, for an adapter which wants to log or count them without
parsing a sentence. Do not raise the retries for it. If nobody notices such a refusal, two
applications keep taking each other's work for months. That is exactly why the core counts every one
of them as `vanillabp.task.deliveries.unknown.workflow`.

A user task is the one delivery which you may have to finish yourself. It can reach you without a
handler: the application marked it as served elsewhere, or its method does not ask for the event you
deliver. So ask `workflowTaskHandlerExists(module, process, taskDefinition)` before you deliver.
Where the answer is no, complete the notification in your BPMS and say so at TRACE level. Do not
deliver it anyway. The core would have no method to route it to. And it writes no delivery record for
a task which nobody served. That is why `TaskDeliveryLog#openTasksOfAggregate` answers the open work
of the APPLICATION, and not the open work of the workflow (decision 54).

Two more inbound notifications exist. Both are optional, because not every BPMS can produce them.
`WorkflowEndedInvoker.workflowEnded(...)` reports that a workflow ended, as completed or as canceled.
Where your BPMS cannot tell the two apart, report the weaker fact, and do not invent the difference.
Treat a missing aggregate as something to skip, not as an error.
Name the workflow in that notification (`WorkflowEndedContext.getWorkflowId()`), and you get one
thing for free. The core reads the tasks which it still believes are open in that workflow. It
reports every one of them to the application as `TaskEvent.Event.CANCELED`, before `@WorkflowEnded`
runs. On a BPMS which cannot say per element what it took away, this is the only way an application
hears about those tasks. Use the same value you report in `TaskInvocationContext.getWorkflowId()`,
because that is what the core wrote into the records.

Leave it empty where your BPMS cancels each element by itself, and say so in your documentation.
Camunda 7 fires an END execution listener per element, and that includes process termination. So it
delivers the cancellation out of the engine's own transaction, and a derived cancellation on top
would report the same task twice. On neither side does the kind of the end play a part. On Camunda 8,
a terminate end event ends the instance as COMPLETED, and it takes an open task with it. So the core
does not read the kind before it derives (decision 73).

`BpmsInitiatedStartInvoker.startWorkflowByBpms(...)` reports the start of a workflow. Report EVERY
start event of the process, the plain one and the message one included, and hang your listener on
every one of them. What a start means does not come from the kind of its start event. It comes from
the state of the workflow, and the core does that reading:

- The BPMS holds a name for the workflow, and a workflow aggregate carries it. Then the workflow is
  already ours. Nothing is built, and `BpmsInitiatedStartResult#created()` says `false`. That is the
  application's own start. It is also what stops a second delivery of the same notification from
  building a second aggregate.
- The BPMS holds no name. Then somebody started the workflow without VanillaBP. The application's
  `@WorkflowStartedByBpms` method builds the aggregate and gives it its id, and your adapter writes
  that id into the BPMS.
- The BPMS holds a name which no workflow aggregate carries. Then the start is refused. VanillaBP
  names a workflow, and nobody else does.

Tell the core where the name is. `getBusinessKey()` is the answer of a BPMS which keeps a business
key of its own, and the core reads it first. A BPMS without one keeps the name in the process
variable which is named after the workflow aggregate's id attribute. The core reads it from
`getVariables()`, so there is nothing extra to report there.

Writing the name back is your adapter's work. Camunda 7 sets the business key of the instance.
Camunda 8 completes the listener job with `BpmsInitiatedStartResult#variables()`, which already
carries the id under `workflowAggregateIdName()`.

There is no start time to report. The time a start event fired is the application's own value. The
application models a process variable, fills it by an expression and reads it as a `@TaskParam`.
Report none, and invent none.

Some processes have a start event which the BPMS fires by itself: a timer, a signal, a condition.
Such a process without a `@WorkflowStartedByBpms` method never gets past the deployment (see decision
69). A process with only plain or message start events needs no such method until somebody starts it
without VanillaBP. That start then says so when it happens.

A called process is not a workflow of its own. The application declares it in
`@WorkflowService(secondaryBpmnProcesses = ...)`. An instance of it runs on the aggregate of the
process which called it. So its start and its end do not reach the application. Two questions tell
your adapter which processes these are. The core can answer both after `validateTaskWiring` of that
process:

- `BpmsInitiatedStartInvoker.startsAWorkflowOfItsOwn(module, process)` answers `false` for a called
  process. Do not report its start. Where you write the start listener into the model, leave it out.
  Where your listener is attached at runtime and has other work to do, such as handing the caller's
  name on to the called instance, ask when it fires, and skip only the report.
- `WorkflowEndedInvoker.workflowEndedHandlerExists(module, process)` answers `false` for a called
  process. A listener which only reports a cancelation may stay, so that the core cancels the open
  tasks of that instance. The core then reports those tasks and does nothing else.

The id which a renamed process left behind is declared as a secondary process too, but no model of
this boot carries it. Its workflows were started under that id, so both questions answer `true` for
it. Decision 124 in the `DECISIONS.md` of the platform repository says why.

### 3.4 Letting the core work cancellations out for you

A remote BPMS usually cannot say per element what it took away. Then your adapter calls
`reportTasksTheBpmsNoLongerHas(module, process, wakeUp, probe)` right after it handed a delivery
over, and the core does the rest. It reads the tasks which it still believes are open in the SAME
workflow, and asks your probe about them. It reports the ones which are gone to the application as
`TaskEvent.Event.CANCELED`. Nothing else in VanillaBP ever tells such an application about those
tasks.

The core asks your probe `stillExists(workflowId, taskId, taskDefinition)`. The probe has three
answers about one task:

|    Answer     |               What it means               |                              What follows                              |
|---------------|-------------------------------------------|------------------------------------------------------------------------|
| `GONE`        | the BPMS does not have this task any more | the application is told the task was canceled and its record is closed |
| `STILL_THERE` | the BPMS still has it                     | nothing                                                                |
| `CANNOT_SAY`  | you cannot tell a refusal from an outage  | nothing                                                                |

The third one is the one which matters. Answer it wherever your API leaves you guessing. Do not turn
a failure into "gone". That would cancel every open task of an instance whenever your engine has a
short hiccup. An adapter whose API has no typed exceptions answers `CANNOT_SAY` for every failure.
That costs nothing, because "still there" and "cannot say" lead to the same nothing (decision 74).

What you owe the call, and what it owes you:

- The question has to be ONE round trip which names the task. It runs on the thread of the delivery,
  up to `vanillabp.delivery.max-open-tasks-checked` times per wake-up. So a search against a read
  model is the wrong answer here.
- The core never probes the task of the wake-up itself, and never a record which another adapter
  wrote. For a BPMN process without an asynchronous task, it never probes anything at all.
- A probe which throws counts as "cannot say", and the core reports it once. The delivery which led
  there is done, and it is not lost over a question which nobody asked for.
- If you supply no probe, nothing changes. Every adapter written before this call existed behaves
  exactly as it did. That is what makes the call an addition and not a change.

The third argument lets you refuse per record instead of per BPMN process. Some BPMS answer about some
kinds of task and not about others. On Camunda 8, a user task which the engine manages lives in a
namespace of its own. Hand its key to a job command, and it answers "not found" for a task which is
perfectly alive. The two ids alone do not say which kind of record you are asked about. So an
adapter without the task definition has to answer `CANNOT_SAY` for every record of a process which
holds one such user task. The plain service tasks of that same process then lose their derived
cancellation too. With the definition, you answer `CANNOT_SAY` for the elements you cannot ask
about, and you ask your BPMS about the rest. A probe which implements only
`stillExists(workflowId, taskId)` is asked through the default, and decides as it always did.

The argument is the definition and not `TaskKind`, even though the delivery record states the kind
outright. The kind cannot carry the answer which matters most here: whether THIS element may be
probed at all. On Camunda 8, a user task which carries an `updating` listener that nobody serves must
be left alone. The empty update fires that listener and leaves the task in the state `UPDATING`. And
that is a property of single elements of the model. An adapter which holds only `USER_TASK` would have
to refuse every user task of a process with one such element. That is the refusal per BPMN process
which this argument was added to remove.

Do not read a negative answer as more than it is. `GONE` is the only answer which cancels the
application's task. The same rule holds for the election next to it. A workflow which ended is
`COMPLETED`, never `UNKNOWN_TO_BPMS`, because the unknown answer sends the next operation of a
migration to the wrong BPMS.

Do not call it from an adapter whose BPMS cancels each element by itself. The application would hear
about the same task twice.

### 3.9 Saying what a start found

What your adapter notices while the application starts no longer goes into a line of its own.
VanillaBP collects every finding of the start and says it once, at the end of the start, as one block
grouped by topic. A start with a finding of the platform and one of yours writes one block, not two.
A start with nothing to notice writes nothing at all.

Ask for a bean of `io.vanillabp.integration.spi.startup.StartupReport` wherever you need it. It exists
on both platforms, one instance per application, and it has four methods:

```java
report.warn(
    StartupTopic.CONFIGURATION,
    "camunda8 adapter '%s'".formatted(adapterId),
    """
        Camunda 8 adapter '%s' has 'request-timeout: %s'. That value is the deadline of every \
        request this adapter sends. Raise it to at least PT1S; the default is PT10S."""
        .formatted(adapterId, timeout));
```

- `notice` is for something which the start survives, but which still asks the developer for
  something.
- `warn` is for something which is wrong.
- `error` is for something which is wrong enough to be written at error level.
- `refuse` is for a reason why the start cannot go on.

A refusal is collected. VanillaBP throws it at the end of the start, together with every other
reason. So a developer who got two things wrong learns both in one start. Sometimes a check of yours
cannot let the start go on, because its next question depends on the one it just proved
unanswerable. Such a check throws where it stands, and says so in its javadoc.

Three rules make the block work. All three are about the two strings you hand over.

The SCOPE is what the finding is about, written the way the developer knows it: a workflow module, a
BPMN process, your adapter id, a property key. Never write it into the message. The same finding
arrives once per workflow module, per BPMN process and per adapter id. The block folds two findings
of one topic with the same text into ONE entry which names both scopes. A message with the adapter id
inside cannot be folded. An application with three adapter instances then reads the same paragraph
three times.

The MESSAGE is the whole sentence, and it ends with what to do. It is not formatted for a width, and
it has no placeholders. The block decides what to do with it.

The TOPIC is the artifact where the fix lies, taken from `StartupTopic`. The developer scans for it,
because it tells them which file to open.

A report about your adapter doing its work does NOT belong here: which BPMS it reached, how many
workers it started, which listeners it set up. Those stay where they are. The three framed reports of
the existing adapters are of that kind.

A finding which is reported after the block was written goes into the log where it was found. A
refusal is then written, not thrown, because the start it was meant to stop is over by then. See
decision 97 in the repository's `DECISIONS.md`.

## 4. The promises a probe makes

The election walks the prioritized adapters and stops at the first `ACTIVE`. So it is exactly as
right as the answers it gets. The core cannot check any of this. Which adapters may be asked is the
core's business. Which workflows an adapter owns is only yours.
[Awareness contract](./README.md#awareness-contract-workflowawareness) in the core's README draws the
walk twice: once as it runs inside the caller's transaction, and once as it runs when an outbox entry
is dispatched.

Answer for the scope you are given, not for what your instance holds. Every probe takes a
`WorkflowScope`: the workflow module, and the plain BPMN process ids which the calling process
service serves. Anything outside it is `UNKNOWN_TO_BPMS`, and never `ACTIVE`. The unknown answer is
what lets the walk reach the adapter which really holds the workflow (decision 4). A task id alone is
not enough evidence, and neither is an aggregate id alone. Two adapter ids may address one backend.
That setup is supported, and it is how a workflow module moves from one scoping to another. There,
the keys are global and do answer. Two workflow modules of one backend may carry the same aggregate
id, because aggregate ids are unique per aggregate type, not across an application.
`ElectionScopeContractTest` holds this from the core's side. It shows the walk reaching the holder,
the walk stopping at an adapter which claims more than it holds, and a workflow of another module of
the same adapter not being claimed.

A probe must never move the workflow forward. It is a question asked before an operation is routed.
It is asked of adapters which do not hold the subject at all. So a probe which completes a task or
correlates a message moves somebody else's workflow. A command which moves nothing forward is still
allowed where it is the only way to ask, and it has to be scoped before it is sent. Renewing a lock is
the example. A job-timeout update or an empty user-task update answers exactly, renews a lock and
moves nothing forward.

The two negative answers mean different things, and a lot depends on the difference.
`UNKNOWN_TO_BPMS` means that a successful query found nothing. Only this answer allows the walk to go
on to the next adapter. `BPMS_UNAVAILABLE` means that you could not ask. It must never go on to the
next adapter, because the workflow may well live in your BPMS. If you mix these up, a migration sends
operations to the wrong place, and nothing else will catch it. `COMPLETED` is a real answer and not a
kind of unknown. The BPMS asked is the one which was responsible, and the operation simply comes too
late.

The redispatch probe must never be optimistic. The core asks `awarenessOfWorkflowForRedispatch`
before it dispatches a recovered or repeated `START_WORKFLOW` entry. The answer "known" SKIPS that
start. So a wrong "known" loses a workflow. A wrong "unknown" only costs the duplicate which the
at-least-once delivery allows anyway. The core asks you only where it has no row saying that your
adapter started the workflow of that aggregate. Such a row answers the question without you. The
default delegates to the ordinary workflow probe. That is right only where that probe is an honest
query. Where it is not, override this one and answer `UNKNOWN_TO_BPMS`.
`OutboxRedispatchMitigationTest#retriedStartEntryDoesNotStartASecondWorkflow` shows the probe doing
its job.

The core calls the form with four arguments, which adds the moment the start was planned. An
aggregate may carry a second workflow once its first one ended. The first one is no evidence that the
second one started. So override the form with four arguments, and count only the workflows which
your BPMS started at or after that moment, by the start time it reports. Do not move the moment back
to allow for a clock which is behind. That turns a duplicate into a lost workflow. The moment is
`null` for an entry which was planned before the moment was recorded. Then every workflow of the
aggregate counts. The default ignores the moment and asks the form with three arguments. So an
adapter which overrides only that one still takes the first workflow for the second.

`canLocateWorkflows()` needs an honest answer for the same reason. Say your BPMS cannot be asked
whether it holds a workflow. Then the only answer which keeps an application with a single BPMS
working is an optimistic `ACTIVE`. Next to a second adapter, that answer routes operations by list
order and not by evidence, and takes the workflows of the other BPMS. If you say `false` here, the
core can refuse that combination while the application starts. The message names your adapter, the
workflow module and the ways out. One way out is `vanillabp.election.guessing-adapters: ACCEPTED`,
for an operator who accepts the risk. The core fetches the answer after you deployed. So an adapter
which only learns what its BPMS can do while it deploys may answer from what it found out by then.
`GuessingAdapterStartupTest` holds the refusal, and `AcceptedGuessingAdapterStartupTest` the
accepted variant.

Report a visibility delay where your reads lag behind. A remote BPMS whose probe reads an exported
read model answers "unknown" for a workflow which exists. That is what the read model knows, not a
defect to hide. `workflowVisibilityDelay()` is how you say so, and
[4.2](#42-the-window-may-be-answered-per-workflow) is how you say it per workflow.

Decision 5 says where the core waits and where it does not. It is worth knowing, because it
explains what your answers cost:

|                  Your answer                  |                 Phase one does                  |       The dispatch of an entry does       |                      A read does                       |
|-----------------------------------------------|-------------------------------------------------|-------------------------------------------|--------------------------------------------------------|
| `ACTIVE`                                      | the operation runs                              | the operation runs                        | you answer                                             |
| `COMPLETED`                                   | a no-op with a warning                          | the entry is consumed                     | you answer, an ended workflow can be viewed            |
| `UNKNOWN_TO_BPMS` for a task                  | the caller gets a task-not-found error at once  | the entry is consumed, the task is gone   |                                                        |
| `UNKNOWN_TO_BPMS` for a workflow, with a hint | the operation is planned and the caller returns | repeats the entry, due in your window     | waits out your window, then a workflow-not-found error |
| `UNKNOWN_TO_BPMS` for a workflow, no hint     | a workflow-not-found error at once              | the entry is consumed as stale            | a workflow-not-found error at once                     |
| `BPMS_UNAVAILABLE`                            | an exception naming your adapter, at once       | retried twice, then the entry is repeated | retried twice, then the exception                      |

Phase one never sleeps. It runs inside the caller's transaction, and it holds a database connection
and the locks on the aggregate. For everything in the table above, the waiting happens where no
application transaction is open. A read has no second place to go. So it waits itself, limited by the
same hint and the same window. One caller outside that table waits on an application thread as well:
the election which an extension asks for. A reporting extension calls it while its own transaction is
still open. This module's `README.md` measures what that costs, and your window is the number it is
measured against.

A hint exists only where VanillaBP knew the answer without asking anybody. Either it started the
workflow itself, or a delivery for that workflow arrived from that BPMS. For an operation on a
workflow, the row written when the workflow started is a hint too, where the election cache has
none. That row is kept in the application's database. So it is still there after a restart, on a
second node and after the cache forgot. Those are exactly the moments when a read model which stopped
catching up would otherwise make a running workflow look unknown. Every row of that table is a case
of `WorkflowLocatorTest`. The read column is also a case of
`ViewerApiTest#readWaitsForAnEventuallyConsistentAdapterToCatchUp`.

One question about a task may not reach you at all. Where the CALL names a task, and the delivery of
that task wrote a record which names your adapter, phase one routes by that record and does not probe
(decision 30). A record of a task which is still open answers for every operation. A closed one
answers only for the operations which end that task, because the workflow may well run on. The
dispatch of the entry probes as it always did. So a workflow which changed its BPMS in between is
still found. Where no record exists, the walk runs exactly as it always did. The record is either
right or silent.

The other half of that question is yours. Where the question does reach you, and your phase one finds
the task gone, throw `io.vanillabp.spi.process.TaskNotFoundException`. The core raises that type where
its own probe finds no BPMS which holds the task. So an application catches one type, whichever of
the two answered. Your check is often the only thing which can find out. VanillaBP writes the closing
mark itself, so a task which somebody completed outside VanillaBP still has a record which says it is
open.

### 4.1 The election may hand you the workflow's own id

`awarenessOfWorkflow` has a fourth argument, and every election about a workflow passes it. That is
both phases of an operation on the workflow (correlating a message, pushing a changed aggregate), the
election an extension asks for, and a read of the viewer API. The argument is your BPMS' own id of
the workflow, taken from what VanillaBP already holds: the row written when the workflow started, the
record of a task delivery and the election cache. It is `null` where nobody knew one. Nothing new is
asked of your BPMS for it.

Implement it where your engine answers by key faster than your read model does. On a Camunda 8
cluster, that is the difference between 20 ms and up to two seconds.

There is one rule, and it is the whole contract: you may make a YES shorter, and you may not turn a
negative answer into `UNKNOWN_TO_BPMS`. An engine forgets a workflow the moment it ends. So "the
engine does not know the key" does not tell `COMPLETED` from `UNKNOWN_TO_BPMS`. Only the second of the
two lets the election move on to the next BPMS, and in a migration setup that sends the next operation
to the wrong one. Where the key answers nothing, fall back to the answer you would have given without
it.

If you do not implement it, nothing changes. The default drops the id and asks the question you
already answer.

Phase two of an operation on a workflow gets the id as well, as `PhaseTwoRequest#workflowId()`, where
VanillaBP wrote down that YOUR adapter started that workflow. Use it where your BPMS addresses a
workflow by its key, and you would otherwise search for that key. Pushing a changed aggregate does
that. The same rule holds. It is a hint, and where your BPMS does not know that key, do what you
would have done without it. It is `null` for a task operation, for a workflow which another adapter
started, and wherever no start row exists.

Where `workflowId()` is `null` and your adapter has to search for the workflow, say what you found.
`PhaseTwoRequest#reportFoundWorkflow(String)` takes the id of the workflow the aggregate IS, the top
parent on a BPMS with call activities, as for a start. The core then writes the row which was
missing. So the next operation gets the id and does not search again. That is how a workflow which
version 1 started, which has no row, gets one. The core writes it in a transaction of its own, only
where no row exists, and never over a row. A search which answers from a read model which is behind
may name an instance which ended, and a row which is there was written by somebody who knew. Do not
report an instance you only guessed, and do not report anything where you did not search. If you do
not call it, nothing changes, and every operation on such a workflow searches as before.

A push of a changed aggregate into the scope of a task also gets the row of that task, as
`PhaseTwoRequest#taskRecord()`, where YOUR adapter delivered the task. The row exists only for a task
which your adapter left open. `taskClosedAt` says whether the application closed it since. The row
names your BPMS' id of the workflow the task runs in, the BPMN element and the kind of task. So you
can address the task without searching for it, and you know whether the task is at rest. You can
safely ask your engine about a task at rest. A task without such a row may be in the hands of a
handler right now. It is a hint as well, and it is `null` wherever there is no such row.

### 4.2 The window may be answered per workflow

`workflowVisibilityDelay(workflowId)` is the same window, asked for one workflow. The core calls it
wherever it waits. The id comes from what VanillaBP already holds. `null` means that nobody knew one,
and that is a normal answer.

One window for everything has to be the longest one you ever need. For most of what the core waits
for, that price is wrong. The window exists for workflows which were just started. We measured three
Camunda 8 clusters in September 2026: the engine knows a new instance after 16 to 19 ms, while the
search finds it after 167 to 1324 ms. Those are the workflows for which your probe ends up answering
`ACTIVE`. A workflow which the engine no longer holds is the other case. It is old enough to have
been in the read model for a long time. With one window for the whole adapter, a case which needs one
second waits the ten seconds a young workflow may need.

You may make the wait shorter for a workflow you already know something about, and answer today's
window for the rest. You may not let a shorter window change what you answer afterwards. A shorter
window only ends the waiting sooner, and the answer after it is the honest one. A workflow which
ended is `COMPLETED`, never `UNKNOWN_TO_BPMS`, because an ended workflow read as unknown sends the
next operation of a migration to the wrong BPMS.

If you do not implement it, nothing changes. The default drops the id and answers what
`workflowVisibilityDelay()` answers.

## 5. What you must never assume

Every line here is a mistake which an adapter has made or nearly made.

### About threads

Phase two runs on the thread of the outbox dispatcher. There is no request scope, no security
context, no MDC (mapped diagnostic context, the logging context) of the caller, and nothing which the
application bound to the thread. Whatever phase two needs travels in the call's arguments. That is
why the activation id is an argument, and not something you read from the thread.

Your inbound threads are yours, and you decide how many there may be. Camunda 8 runs four platform
threads by default. One blocking handler once stopped that adapter from polling at all, and every
running handler holds a database connection.

`stopWorkflowProcessing` races the handlers which are still inside the application. Decide the
policy and write it down in your README. Camunda 8 waits for its handlers and for the cluster to
release its workers. It reports no job as failed while the shutdown runs.

The core may call your probes from any application thread and from the dispatcher at the same time.

### About deliveries

Every delivery may come again: after a crash, after a lock timeout, or because your completion
command failed after the local commit. The core answers a repeated delivery from its record only
where you report a stable delivery id. Without one, the handler runs again, and the application's
own idempotency is the only safety net.

Report no delivery id where your BPMS gives you nothing stable to report. The record is written
either way, under a key which says in words that it deduplicates nothing. So the two other answers
of the record keep working: which BPMS holds a task the application names later, and which kind of id
that id is. An id invented per delivery would be worse than none. It would look as if it
deduplicates, and it would never deduplicate.

Two deliveries of one task at the same moment both run. Only your BPMS' lock prevents it. When it
happens, the core writes a warning which names the task, and it counts it. A record can only speak
about work which is already committed, and neither of the two had committed when the other read
(`InboundIdempotencyTest#twoDeliveriesAtTheSameTimeAreNamedAndCounted`).

A phase-two call may arrive twice. It may arrive for a task which is already gone. And for everything
except a start and a signal, it may arrive after the workflow moved to another adapter. Answer
honestly. The core probes again.

A rebuilt BPMS which restarts its key range makes old delivery records answer new deliveries.
Document the step an operator has to take for your BPMS. This one is an assumption, not something a
test holds. It follows from the fact that the delivery id is your BPMS' own identity. A BPMS which
never reuses a key after a rebuild would prove it wrong.

### About ordering

Outbox entries have no order. A pushed aggregate and a task completion which were scheduled in one
transaction may reach you in either order.

An entry which waited may hold a younger call than the one which was planned first. A caller whose
call carries its own state can ask for that with `PhaseTwoCall.replacingWhatIsStillWaiting()`. The
store then puts the younger call into the waiting entry, and does not drop it. No operation of yours
can ask for it, because it is reserved for the operations an extension registers. What reaches your
phase two looks exactly as it always did. What changes for you is how often it arrives. The reports
of one workflow which piled up during an outage now reach the handler as one. Where a dispatch had
already taken the entry, they reach it as two.

Phase two of a start may run before or after the first inbound delivery of that workflow arrives,
maybe on another node.

A message may be correlated before the subscription which waits for it exists. Do not treat "no
subscription" as an error, unless your BPMS does.

`deployResources` of every adapter of a workflow module completes before any
`startWorkflowProcessing` runs. On the way down, extensions stop first and adapters last.
`DeploymentServiceTest#extensionWiringServicesAreStoppedBeforeAdapters` and
`MultiAdapterDeploymentTest#bothAdaptersDeployAndStart` hold both orders.
`ShutdownReverseOrderTest` holds the way down against an application which has started.

You are the first one at a model. Your `wireBpmn` runs before any extension sees that process. Your
`startWorkflowProcessing` runs before any extension is started. The extensions build on that
promise, because they hook in relative to what you put there. So an extension may add a listener
after your last one, and if you move your own listeners around, theirs move with them. The order of
the extensions among themselves is their business, and it is not promised.
`DeploymentServiceTest#theAdapterIsFirstOnTheWayUp` holds it. An extension author reads it on the
wiki page [Extensions](https://github.com/vanillabp/adapter-platform-integration/wiki/Extensions).

### About time

Nobody waits for phase two. Say you reject a call because your BPMS has not made the workflow
searchable yet (`PhaseTwoRetryLater`, with the window your BPMS may need). That does not park the
dispatching thread for that window. The attempt ends, and the entry is planned again. The calls of
every other workflow go out while it waits.

So the window you name is a due time. Every store which VanillaBP ships makes the entry due after it:

- the relational store of the core,
- the two MongoDB stores,
- the gruelbox store, which a Spring Boot application adds as an artifact of its own. There the
  window is written onto the entry after the failed attempt.

None of them makes your window shorter, and none of them makes it longer. So a window longer than
`vanillabp.outbox.attempt-frequency` is waited out, and a shorter one is not waited past. That is
what lets your documentation name a number (decision 49). A store adds no wait of its own on top.
Its poller sleeps until the earliest entry is due, and it learns the new due time when your attempt
ends. `vanillabp.outbox.poll-interval` (ten seconds by default) only caps that sleep, for a node
which went away while it held work. A store written somewhere else decides on its own whether it
polls or plans its wake-up, and one which polls adds its poll on top.

Name a window which your BPMS really needs, because the entry sits for that long. A window of ten
seconds for a read model which is one second behind costs nine seconds per call.

A failure which says nothing about a moment is retried at a growing distance instead, starting at
`vanillabp.outbox.attempt-frequency`. (An application which runs the gruelbox store keeps that one
distance.) That failure is counted, and after `vanillabp.outbox.block-after-attempts` of them the
entry is blocked.

`PhaseTwoRetryLater` is not counted. A read model which is behind is no failure of the entry. So the
stores which VanillaBP writes itself leave the attempts as they are. They block the entry only once
`vanillabp.outbox.wait-for-visibility-at-most` has passed since it was written. If that is not set,
it is the time which the attempts take with the growing backoff, about four hours. That ends a
workflow which never becomes searchable (decision 49 in the repository's DECISIONS.md). Because the
answer is not counted, the store does not remember it either, and the next dispatch looks like a
first one. So throw it only before your operation reached the BPMS.
`WaitingForAReadModelUsesNoAttemptsTest` holds it for the relational store, and
`MongoWaitingForAReadModelUsesNoAttemptsTest` for the MongoDB store of each platform.
`ARejectedDispatchIsPlannedAgainTest` holds it on Spring Boot, and
`NotVisibleWorkflowDoesNotStallDispatchTest` for the core.

Your adapter may know how long a wait may last. Then pass that moment as the deadline of
`PhaseTwoRetryLater`. It replaces `wait-for-visibility-at-most` for that entry, in both
directions: the entry is blocked at the deadline, earlier or later than the outbox setting would
block it. The Camunda 7 family uses it for a message time-to-live. A correlation which finds no
subscription asks for another attempt, and its deadline is the moment the correlation was planned
plus the time-to-live. That moment reaches phase two as `PhaseTwoRequest#plannedAt()`, and it stays
the same for every attempt of the entry. End the wait yourself when the deadline has passed, with
a `PhaseTwoPermanentFailure` which says what ran out. The block of the store is only the net for a
window which ends after the deadline. `WaitingForAReadModelUsesNoAttemptsTest` and
`MongoWaitingForAReadModelUsesNoAttemptsTest` hold the deadline as well (see the addendum to
decision 49 in `DECISIONS.pending/2060.md`).

## 6. Registering your adapter per platform

The dummy adapters of this repository are the templates, one per platform. They are small. The
platform's own tests use them, and so those tests keep them up to date:

* Spring Boot: `spring-boot-integration/integration-tests/dummy-adapter`
* Quarkus: `quarkus-integration/integration-tests/dummy-adapter`

[What the platform hands an adapter](./README.md#what-the-platform-hands-an-adapter-adaptercollaborators)
draws which bean goes where on each platform, and which interfaces you never implement yourself.

### Spring Boot

Two auto-configurations, listed in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

The first one announces your adapter type. It extends `AdapterConfigurationBase`, and it runs before
the platform validates the configured types. It declares nothing else, because it has to be
constructible very early.

The second one registers your beans per id through a `BeanRegistrar`. Use
`AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment, ADAPTER_TYPE, …)`. The set of ids
comes from the runtime configuration. At registration time, that configuration is not yet bound into
configuration-properties beans, and the helper binds the core tree from the environment for you. It
also derives the ids which the core derives by convention. A simple read of the configured sections
would miss those.

For each id, register one element bean of your `MigratableProcessService` and one of your
`AdapterDeploymentService`, named after the id. Take the collaborators from
`AdapterBeanRegistrarSupport.collaborators(supplierContext, adapterId)`.

Never register a bean of type `List<AdapterDeploymentService<…>>` on Spring Boot. The platform
collects element beans through `ObjectProvider` streams. A list bean breaks that collection as soon
as a second adapter type is on the classpath, and that is exactly the migration setup.

### Quarkus

Your adapter is a Quarkus extension of its own, with a runtime module and a deployment module.

The runtime module's `META-INF/quarkus-extension.yaml` declares `dependencies: [vanillabp]`. Under
`capabilities.provides`, it declares the capability `io.vanillabp.adapter.<adapterType>`, where the
suffix is exactly your adapter type. The VanillaBP extension checks that, no matter what your groupId
is. The extension's `name` can be anything. The convention is `vanillabp-<type>`.

The deployment module produces a `FeatureBuildItem`, and two build items which announce your
producers: `VanillaBpMigratableProcessServiceBuildItem` and
`VanillaBpAdapterDeploymentServiceBuildItem`. Each carries your adapter type and the class name of
the producer. The VanillaBP extension registers the announced bean classes, so you do not register
them again.

The runtime module holds those producers. Unlike on Spring Boot, the platform expects one
`@Singleton` producer method. It returns a `List` with one instance per configured adapter id. Two
conventions come with that, and both are part of the contract:

- The list's element type is the SPI interface with both type parameters written literally as
  `Object`, whatever your model and context classes are. CDI does not match differing type arguments
  reliably across modes, and the platform looks the beans up by the exact type.
- The producer is `@Singleton`, not normal-scoped. A deployment service usually has no constructor
  without arguments, so CDI cannot build a client proxy for it.

Build the collaborators with `AdapterCollaboratorsSupport.collaborators(adapterId, …)` from the
runtime support module.

Your configuration overlay is a runtime `@ConfigRoot @ConfigMapping(prefix = "vanillabp")` in the
runtime module. Two things about it will cost you a day if you learn them the hard way.

- Every key your adapter reads has to be modelled in that mapping. The platform no longer ignores
  unknown keys under `vanillabp.*`. That is how a typo becomes a startup failure.
- Never `@Inject` a `@ConfigMapping` interface. Injecting it turns the mapping into a static-init
  one. Then the whole `vanillabp.*` tree is validated before any adapter extension registered its
  runtime overlay. So every key of an adapter fails the startup, and the message points at the key,
  not at the injection which caused it. Read the mapping instead, through
  `ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)`.

### On both platforms

The platform's core properties always decide which adapter ids exist. Use your overlay maps only to
look up an id you already know. Never iterate them to find ids. Overrides by environment variables
can create map entries which no configuration ever declared. And an id which exists only in an
overlay is invisible to the platform's validation.

## 7. What an adapter repository brings

Your repository is yours. This section describes what the adapters in this workspace look like, so
that whoever reads one recognizes the next.

The module layout follows the platform's split. A `core` module in plain Java holds the SPI
implementations and all of your BPMS logic, with no Spring or Quarkus imports. A `spring-boot` module
and a `quarkus` module (itself split into `runtime` and `deployment`) only construct and register
what `core` defines. If you find yourself writing BPMS logic in a platform module, it belongs in
`core`.

The documentation is split the other way round from version 1. The wiki is for users, and it holds
two kinds of content. The first is the configuration of your adapter, including what a BPMN model has
to look like for your BPMS and how much of a workflow aggregate reaches it. The second is the
deviations: one sentence per gap between your BPMS and the contract which VanillaBP promises on all
platforms. The `README.md` is for contributors. It holds the reasons, the alternatives you
considered and how the SPI works. A mode which you chose on purpose and which conforms fully is not
a deviation. Document it with the configuration which switches it on.

One rule is about what you publish, because it decides what a reader of your documentation sees and
what an application has to fetch. A published artifact never makes an application fetch Lombok or
MapStruct (decision 81 of this repository). So no POM names either tool at a scope which a consumer
resolves. And no bytecode calls a method of either tool, reads a field of it, or names one of its
types in a signature. An annotation may stay where its retention is `CLASS`. Watch the javadoc either
way, because it does not run these tools. Say a configuration class has accessors only because of
`@Getter`. Then it is published as a class without any accessor, and the person who reads it cannot
call what you shipped. Both tools are welcome in your tests and in your build tools.

`DECISIONS.md` holds the numbered decisions which several places in your repository rely on. It is
the only thing your code may cite, in the plain form `see decision 7 in the repository's
DECISIONS.md`. A citation into the log of another repository does not resolve. A decision which spans
two repositories gets an entry in each, written from that repository's side. An entry is superseded,
not edited. It keeps its number and names the entry which replaced it, because a citation which
shipped in an older release still points at it.

Name what your BPMS cannot do, in a `GAPS.md` or on a deviations page. Let the deployment fail where a
missing capability would otherwise produce a workflow without an aggregate.

`UPGRADE.md` says what an application has to do when it moves from VanillaBP 1 to version 2.0 of your
adapter. A change between two snapshots gets no entry there. Its end state belongs in your wiki, and
a reason which several places rely on belongs in your `DECISIONS.md`.

You need release lines only where the client library you compile against decides the lowest BPMS
version your build accepts. Camunda 8 is that case. The client is the minimum cluster version, so
otherwise every bugfix could only be delivered together with a cluster upgrade. That is why the
adapter builds one artifact per cluster minor version from one source tree. Where the engine is
embedded or sits behind a stable API, a pinned version and a documented range in the README are the
whole answer. If you do need lines, build them as profiles over one tree, not as maintenance
branches. Keep the API which VanillaBP sees the same on every line. And write into your README: if
the difference per line keeps growing, the scheme has turned into a branch scheme, and you should
split it on purpose.

Testing follows one rule. It is easy to state and easy to skip. Your user is the migration adapter.
So test at the SPI boundary, against your real BPMS: the deployment pipeline, the two phases, the
probes. You need an end-to-end test at the level of a developer, through `ProcessService`, only as an
example, because the platform's own tests already cover most behaviour which users see. The platforms
are the one place where the rule is turned around. Run your example end-to-end flow on Spring Boot
AND on Quarkus, against the real engine. A correct neutral core says nothing about whether the glue
of a platform ever calls it. Measure instruction coverage separately per platform. Never feed the
execution data of one platform into the report of the other. A low number for a platform names the
features which that platform never runs. Mixing the data destroys the only thing the number is good
for.

### Booting an application in a test without your BPMS

Some of what you have to test has nothing to do with your BPMS: that your extension registers, that
your configuration binds, that your bean reaches the application at all. Starting your engine for
those tests is expensive, and it hides the thing you wanted to see.

For those tests, VanillaBP publishes a BPMS double. It is an adapter which implements the whole SPI
and answers whatever the test told it to answer. On Spring Boot it is one artifact:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-spring-boot</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

On Quarkus it is the extension pair, and both belong in the module which runs the tests:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-quarkus</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-quarkus-deployment</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

Configure it as an adapter of the type `dummy`, and the application starts.
[`bpms-double/README.md`](../bpms-double/README.md) says what the double answers, how a test steers
it, and what about it is promised across releases.

This does not replace the tests against your own BPMS. Everything about YOUR adapter is proven
against the real thing. The double is for the tests where a BPMS is in the way.

## 8. The checklist before your first pull request

### The floor, and how far above it the checklist sits

Two different questions get mixed up here, so we answer them one by one. What STOPS an application
from starting is a short list, and the platform checks every item of it. What makes an adapter good
is the checklist after it, and nothing checks most of that.

This is the floor. Clear it, and an application starts with your adapter. Miss an item, and the boot
ends and names you:

|                                                     What is checked                                                     |     Where you satisfy it     |
|-------------------------------------------------------------------------------------------------------------------------|------------------------------|
| The five mandatory collaborators are in the `AdapterCollaborators` you were built with                                  | your registration, section 6 |
| Your `phaseOperations()` map carries the seven operations `requiredOfEveryAdapter()`                                    | section 2.2                  |
| A phase-two outbox and a transaction runner exist for the application                                                   | the platform's, not yours    |
| Your part descriptor does not pair you with a platform you were not built against                                       | section 2.1                  |
| On Quarkus: an extension publishing the capability `io.vanillabp.adapter.<type>`, and a configured section of that type | section 6                    |
| On Spring Boot: an `AdapterConfigurationBase` bean announcing your type                                                 | section 6                    |
| Every `@WorkflowTask` method of a module is wired to a task, which needs your `validateTaskWiring` calls                | section 3.2                  |

Three more cases end the boot. In each of them, your adapter says so; the platform does not judge
you:

- two ids of your type which `validateDistinctAdapterInstances` cannot tell apart,
- a `canLocateWorkflows()` of `false` next to a second adapter, in a workflow module whose
  `guessing-adapters` is not `ACCEPTED`,
- a name-clash mode which you refused while deploying.

One case is judged by the platform from your answer: two ids of any type whose
`getAddressedSystem()` gives the same key.

Everything else is either a warning or silence. A missing `registerDeployedVersion` switches the
version checks off. A `processVersionCatalogOf` left at `null` keeps the check for renamed processes
quiet. If you do not supply an `OpenTaskProbe`, the application never hears about the tasks which a
workflow took with it. A `checkHealth()` which you do not override reports nothing. None of that
fails anything. That is exactly why the checklist below exists, and why the Process-Engine-API
adapter writes every one of them down in a `GAPS.md`.

### The checklist

1. One `MigratableProcessService` and one `AdapterDeploymentService` per configured adapter id. Two
   ids of your type are refused at boot where nothing tells them apart. Configuration lives under
   `vanillabp.adapters.<id>.*`. It is validated at startup, with messages which name the keys to add.
   An application without configuration still starts.
2. The pipeline in order: `readBpmn`, then `prepareBpmn` (which rewrites once per file), then
   `wireBpmn` with the wiring calls, then `deployResources`, then `startWorkflowProcessing` and
   `stopWorkflowProcessing`. Every name-clash mode is either served or refused with a message. The
   checks per module which follow the deployment belong to the core, and you call none of them. Two
   things come back to you there. `processVersionCatalogOf` answers for an id which the application
   declares without deploying it, scoped the way you scope every other id.
   `ownIsolationSeparatesWorkflowModules` answers whether your BPMS would put two given workflow
   modules into scopes of its own. That decides whether two plain process ids which reach it as one
   string are a collision. You can also ask about those declared ids with
   `taskWiringOfProcessesNobodyDeployed`. Do that wherever your BPMS hands out the work of a renamed
   process under a name which your subscriptions do not carry.
3. Everything about versions, where your BPMS has versions at all: `registerDeployedVersion` per
   process at the end of `deployResources`, a catalog registered while wiring, and
   `processVersionCatalogOf` for the ids nobody deployed. A BPMS which counts no versions does the
   opposite, just as much on purpose: `reportNoProcessVersionCatalog` per process, nothing
   registered, `processVersionCatalogOf` left at `null`, `predatesDeployedVersion()` answered
   `false`, and the gap written down. The Process-Engine-API adapter is that case. You may not do
   neither. Then the core cannot tell "this BPMS has no versions" from "this adapter is not
   finished", and it points a developer at the wrong thing.
4. A handler for each operation your BPMS can serve. Only the operations which allow it are left
   out. Phase one asks, and phase two acts. Phase two is idempotent and throws on anything except
   "already gone".
5. Probes are scoped and never move a workflow forward. `UNKNOWN_TO_BPMS` and `BPMS_UNAVAILABLE` are
   mapped honestly. The redispatch probe is never optimistic. A visibility delay is reported where
   your reads lag behind. `canLocateWorkflows()` answers `false` where your BPMS cannot be asked about
   a workflow at all.
6. Inbound contexts carry the delivery id, the activation id, the adapter id and the process version.
   Identifiers are unscoped on the way in. Act on the outcome. Send the shared values, plus the
   variable named after the aggregate's id, with every command. Never report a task as completed
   after an exception.
7. Phase-two failures are classified as permanent only in narrow cases. Your shutdown policy is
   written down. Your inbound threads are limited.
8. Your gaps are written down honestly. A deployment fails where a missing capability would produce
   a workflow without an aggregate.
9. Both platforms, with the example end-to-end flow running on each of them against your real BPMS.

## Where to look next

The SPI's own javadoc is the reference. Wherever the two overlap, the javadoc has more detail than
this document. Start with `MigratableProcessService`. Its type javadoc holds the full contracts of
the election and of the two phases.

The [`README.md`](./README.md) of this module is the contributor documentation of the core. It says
what the election does with your answers, how the outbox dispatches, what the platform hands you and
why. Read it when you want to know why the SPI looks the way it does.

[`DECISIONS.md`](../DECISIONS.md) of this repository holds the reasons which several places rely on.
This document points at entries 3, 4, 5, 10, 17, 19, 26, 28, 29, 30, 38, 49, 54, 69, 73, 74, 81, 97,
120 and 124.

If something here is wrong, or if you need a promise which this SPI does not make, tell us. We
finished the SPI before any adapter written outside this repository existed. We did that on purpose,
so that the shape you build against is one we are willing to live with.
