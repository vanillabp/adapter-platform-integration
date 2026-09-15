# Writing a VanillaBP extension

This document is for a team building an extension of VanillaBP, from outside this repository. Read
it from top to bottom once. It describes the interface you implement, when the core calls you, what
each call may see, where your settings live, what the core promises you and what you must not do.

Everything stated here is taken from the code of this repository or from a numbered entry of its
[`DECISIONS.md`](../DECISIONS.md). Where a statement is an assumption rather than something the code
holds, it says so. Every promise names the test which holds it, so a promise which stops being true
turns a build red here rather than surfacing in your extension.

If you are connecting VanillaBP to a business process management system instead, read
[`ADAPTER-AUTHORS.md`](./ADAPTER-AUTHORS.md). An adapter and an extension are the two kinds of
plug-in which share one interface, and the two documents describe the two sides of it.

## Vocabulary

A *process* is the BPMN model, a *workflow* is one running instance of it. Use the two words
strictly this way; the SPI does.

A *workflow module* is one deployable unit of BPMN files plus the code serving them, identified by
the file `META-INF/workflow-module` whose content is the module id. A *workflow aggregate* is the
application's own entity holding all state of one workflow.

An *adapter type* is one BPMS implementation as a whole, named by a short constant such as
`camunda7`. An *adapter id* is one configured instance of that type, and an application may
configure several ids of one type at once. That multiplicity is the migration feature, and it is the
reason most rules below are written per id.

The *core* below means `migration-adapter`, the platform-neutral part of VanillaBP. The *platform
integration* is the Spring Boot or Quarkus layer which brings the core to life.

The word *extension* is a word of the contributors. A user of VanillaBP does not know it. They know
VanillaBP adapters, and if they run the Business Cockpit they know Business Cockpit adapters, and
the configuration speaks their language: the section your settings live in carries your own name,
not the word `extensions` (decision 53). Where this document says "extension", say what your users
call you when you write their documentation.

## 1. What an extension is, and what it is not

An adapter and an extension both implement `ExtensionWiringService<BPMN, PC>`, because both take
part in deploying a workflow module. Everything else about them differs, and the line between them
is who owns the model.

|                    |                      BPMS adapter                       |                                    Extension                                    |
|--------------------|---------------------------------------------------------|---------------------------------------------------------------------------------|
| Interface          | `AdapterDeploymentService`, which extends the one below | `ExtensionWiringService`                                                        |
| The BPMN model     | it reads, prepares and deploys it                       | it sees what the adapter read, and may add to it                                |
| The BPMS           | every workflow operation runs against it                | it never talks to a BPMS on VanillaBP's behalf                                  |
| How many per model | one per configured adapter id                           | any number                                                                      |
| Examples           | `camunda7`, `camunda8`, `process-engine-api`            | the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit) |

The Business Cockpit is the extension this SPI was shaped around, and it appears in this document as
an example. Nothing here needs it. An application running an extension of yours needs VanillaBP, one
adapter and your artifact.

Version 1 had no such SPI. What called itself a Business-Cockpit adapter back then was built on
whatever of the platform integration happened to be public: `AbstractTaskWiring`, `TaskHandlerBase`,
a second configuration-properties bean of its own. Every change to one of those classes was a change
to the extension, and an extension for Quarkus could not exist at all. Version 2 hands you a
described contract instead of a base class, and the shape of everything below follows from that
(decision 35).

Four things are worth stating before the interface.

You never talk to a BPMS on VanillaBP's behalf. The adapter deploys, starts workflows, completes
tasks and answers where a workflow lives. Where you do need a BPMS of your own, you connect to it
yourself, and you ask the core which one holds a workflow before you do (section 3).

Your own SPI stays yours. The annotation your users write, the service they inject, the types your
methods take: none of that belongs in the VanillaBP core. The core knows nothing about user-task
details, and an application not using your extension must see none of your annotations. What the
core gives you is a way to describe your annotation so that VanillaBP runs those methods with the
mechanics of `@WorkflowTask` (section 5).

You ship both platforms. An extension which exists only on Spring Boot does not exist, because
VanillaBP promises the same behaviour on Quarkus and on the platform after it. The sample extensions
of this repository are one per platform for that reason.

You depend on the two SPI artifacts and on the platform-neutral core, and on no platform
integration. That is `io.vanillabp:vanillabp-extension-spi`, which has no dependency at all,
`io.vanillabp:vanillabp-integration-spi` where you touch the outbox or the transaction, and
`io.vanillabp.adapter:migration-adapter` for `MigrationAdapterProperties`. The sample extension of
each platform is built exactly that way, and its POM says so: adding a dependency on
`vanillabp-spring-boot-integration` would make it stop proving anything.

## 2. The six calls of the wiring service

`ExtensionWiringService<BPMN, PC>` has six methods. Three of them say who you are, and the core asks
them to decide whether you take part at all. The other three are the calls you act in.

|                           Method                            |                 When the core calls it                  |                                   What it is for                                   |
|-------------------------------------------------------------|---------------------------------------------------------|------------------------------------------------------------------------------------|
| `getModelType()`                                            | while it matches you against an adapter                 | the BPMN model type you understand, for example Camunda 7's `BpmnModelInstance`    |
| `getProcessContextType()`                                   | while it matches you against an adapter                 | the adapter's processing-context type you expect                                   |
| `getOrder()`                                                | once, while the wiring services are sorted              | your position among the EXTENSIONS of that model type, ascending, default `0`      |
| `wireBpmn(module, filename, bpmnProcessId, model, context)` | per executable BPMN process, after the adapter wired it | inspect the model, find your own annotated methods, add what you need to the model |
| `startWorkflowProcessing(module, context)`                  | after every adapter of every module was started         | open what consumes your wiring, a listener or a worker of your own                 |
| `stopWorkflowProcessing(module, context)`                   | on a graceful shutdown, before any adapter is stopped   | close what you opened; the default does nothing                                    |

`BPMN` and `PC` are the adapter's types, not yours. You declare which ones you can work with, and
the core asks you only where both of your declared types are assignable from the adapter's types. A
Camunda 7 extension stays untouched while a Camunda 8 module is deployed. An extension declared
against `Object` and `Object` sees every BPMS and can read no model, which is the honest shape for
an extension which only needs to know that a module was deployed. The trade-off is in the second
type: an extension which wants to contribute something to the adapter's processing context has to
name that context type and becomes BPMS-specific by doing so.

Matching is all-or-nothing on purpose. Both calls in the pipeline use the same rule, so an extension
is either wired and started, or it is neither. `DeploymentServiceTest#extensionWiringServicesAreFilteredAndCalled`
and `#subtypeExtensionIsNeitherWiredNorStarted` hold it in the core, and
`DeploymentPipelineTest#nonMatchingExtensionUntouched` against a booted application.

### The order, up and down

The adapter of the BPMS runs before every extension. It has wired a BPMN process before you see that
process, and it is processing workflows before you are started. This is the one ordering promise
VanillaBP makes to you, and it is what lets you place what you add relative to what the adapter put
there, for example behind the last listener of a kind, instead of at a position you have to guess.
On the way down the order is mirrored: extensions are stopped first and the adapters last, so
nothing is stopped while something else still feeds it. Decision 52 carries both halves,
`DeploymentServiceTest#theAdapterIsFirstOnTheWayUp` and
`DeploymentPipelineTest#extensionsWiredInOrder` hold the way up, and
`DeploymentServiceTest#extensionWiringServicesAreStoppedBeforeAdapters` plus `ShutdownReverseOrderTest`
the way down.

Two details of the shutdown are worth reading off the code rather than assuming. The core walks the
workflow modules in reverse order, and it walks the extensions in reverse wiring order, so the
extension which wired last is stopped first. And the pass is complete before the adapters begin:
every extension of every workflow module has been stopped when the first adapter is asked to stop.
Starting is the same shape the other way round. Every adapter of every workflow module is started
before the first extension is, so by the time your `startWorkflowProcessing` runs, workflows may
already be running.

What is not promised is the order of two extensions among themselves. They are sorted by `getOrder()`
ascending, and two extensions which do not know each other cannot agree on a number, so two of the
same order run in the order the platform happened to collect their beans in. Nothing promises that
this order stays. VanillaBP deliberately hands out no first and no last position, because the second
extension wanting to be last is where such a scheme ends (decision 52).
`DeploymentServiceTest#wiringServicesAreSortedByOrder` holds the sorting, and nothing holds more
than that, which is the point.

### What `wireBpmn` may do to a model

The adapter owns the model. It parsed it, it rewrote it for its BPMS, and it will deploy it after
you have seen it. What you add has to survive that, which in practice means you add relative to what
the adapter left behind and you change nothing it put there. On Camunda 8 the Business Cockpit adds
its `creating` listener behind the last `creating` one and the rest behind everything; on Camunda 7
the adapter offers `parseListenersAfter` and `parseListenersBefore` for the rare element which has
to be seen untouched (decision 52). Something which has to see an element before the adapter touched
it needs an entry point of its own and has to ask us for one.

Two things about the model are the adapter's alone and stay that way. Identifiers are scoped at the
BPMS boundary and nowhere else (decision 9), so do not prefix, tenant or rename anything on its way
out. And what a model declares is read by the adapter, which is the only party able to read its own
BPMN dialect. Where you need something the adapter already read, ask the core for it rather than
parsing the file a second time: `ExtensionHandlers#bpmnTaskNameOf(module, process, activityId)`
answers the `name` a modeller wrote on an element, kept from what the adapter handed over while it
wired.

## 3. An extension hangs on every configured adapter

The core builds one processing context per workflow module and adapter id. Your three calls are made
against that context, which means an application with two configured adapters calls you twice for
each of them: `wireBpmn` once per adapter per BPMN process, `startWorkflowProcessing` and
`stopWorkflowProcessing` once per adapter per workflow module. This is not an edge case. Two ids of
one BPMS type side by side is what a migration looks like, and it is the setup VanillaBP exists for.

What follows for you is the same rule an adapter follows. Prepare per adapter id rather than once.
Open your workers per adapter id. Keep whatever you remember separated by adapter id, because a
workflow of the one BPMS must not be answered with what you learned about the other. The Business
Cockpit is the example: it registers one bridge per configured adapter id, the way an adapter
registers one set of beans per id.

One thing the SPI does not do for you here, and this is the place to say it plainly. None of the six
calls names the adapter id. `wireBpmn` and `startWorkflowProcessing` are given the workflow module
and the adapter's processing context, and that is all. Two ways lead out of it. You can tell the
adapters apart by the context object itself, since the core holds one per workflow module and
adapter id, and key your state by it; section 8 says why that is an assumption rather than a
promise. Or you register one instance of something of your own per adapter id, which is what an
extension bridging to a BPMS does anyway, and then each instance knows which id it is for. That is
the safe way, and it is what the Business Cockpit does. The id set for it comes from
`MigrationAdapterProperties#adapterIdsOfType(adapterType)` and from nowhere else: three rules decide
which ids one type serves, an id merely named in `prioritized-adapters` and an application
configuring nothing at all are two of them, and every consumer which reimplemented the list dropped
one of the three (decision 39). Deriving the ids from the sections you can see is the mistake this
method exists to prevent.

### Which BPMS holds this workflow

`WorkflowElection#adapterIdOfWorkflow` is the question to ask before you talk to a BPMS about a
running workflow. Both platforms provide it as a bean. During a migration the answer changes per
workflow, so addressing the first-priority adapter instead would be wrong for every workflow which
has already moved.

It is the election every operation of VanillaBP uses, in its reading shape. A workflow which ended
is a regular answer here, the way the viewer API reads its history. A workflow no BPMS knows, and a
BPMN process this application does not serve, are guiding errors naming what was asked.

What you have to know before you call it is the waiting. Where a hint says an adapter holds the
workflow and that adapter's read model has not caught up, the election waits that adapter's window
out rather than answering "unknown", because nobody repeats the question for an extension. On a
Camunda 8 cluster that window is ten seconds. A read of the viewer API waits on a thread which is
doing nothing else, while an extension often asks this from inside a transaction of the application:
reporting a change out of a service task is exactly that case, and the wait then holds that
transaction open together with its database connection and its locks. The section
[What an election costs a caller which holds a transaction](./README.md#what-an-election-costs-a-caller-which-holds-a-transaction)
of the core's README has the numbers.
`ExtensionElectionAndConfigurationTest#theElectionAnswersTheExtension` on Spring Boot and
`ExtensionEnablementTest` on Quarkus run this against a booted application.

## 4. Where your settings live

Your settings are written at four levels, and each level has two positions: what the level says, and
what it says for one adapter. That makes eight, and here they are from the least specific to the
most specific one.

```
vanillabp.<section>                                                                   (least specific)
vanillabp.adapters.<adapter>.<section>
vanillabp.workflow-modules.<module>.<section>
vanillabp.workflow-modules.<module>.adapters.<adapter>.<section>
vanillabp.workflow-modules.<module>.workflows.<workflow>.<section>
vanillabp.workflow-modules.<module>.workflows.<workflow>.adapters.<adapter>.<section>
vanillabp.workflow-modules.<module>.workflows.<workflow>.tasks.<task>.<section>
vanillabp.workflow-modules.<module>.workflows.<workflow>.tasks.<task>.adapters.<adapter>.<section>   (most specific)
```

`<section>` is the name your settings live under. Where VanillaBP binds them for you it is
`extensions.<extension>`, and where you bind a tree of your own it is whatever you called yourself.
Both cases are below.

Three rules decide what a scope reads. The most specific position which writes a key wins. What one
adapter is told beats what the same level says in general. And a more specific level beats the
adapter section of a less specific one, so a value written for the workflow outranks a value written
for one adapter at the workflow module. The positions are merged key by key rather than as blocks,
so a workflow may change one value and keep what the application said about the rest. The level is
called `tasks` and a task is named by its task definition.

The adapter positions exist for the same reason section 3 does. An application migrating from one
BPMS to another runs two adapters, you hang on both of them, and a value which has to differ between
them needs a place to be written (decision 53).

This is the resolution an adapter setting uses, written once in the core (decision 7 and
`#resolveForAdapter`), so a change to "most specific wins" reaches both or neither. Writing it a
second time in your own code is what decision 48 forbids, and it forbids it because that had already
happened: the Business Cockpit read the flat keys, matched the suffixes by hand and decided on its
own what "most specific" meant, once per platform, and the two spellings were drifting apart before
anybody noticed.

### Reading them

`MigrationAdapterProperties` is a bean of both platforms and it reads your settings for you. Your
extension id is a parameter of the call rather than something bound to a view of these properties,
because the properties are one bean of the platform and your extension is not a bean of the platform
at all. You ask with your id, the way an adapter asks with its adapter id.

|                                 Call                                  |                     What it answers                      |
|-----------------------------------------------------------------------|----------------------------------------------------------|
| `resolveForExtension(module, process, task, adapter, extension, key)` | one value, over all eight positions                      |
| `resolveForExtension(module, process, task, extension, key)`          | one value, over the four positions which name no adapter |
| `extensionProperty(module, extension, key)`                           | one value, asked about the workflow module alone         |
| `extensionProperties(module, process, task, adapter, extension)`      | the whole section, merged key by key                     |
| `extensionProperties(module, extension)`                              | the whole section, asked about the workflow module alone |

Any id may be `null`, and a level nobody configured is skipped rather than being an error, so the
same method answers the global, the module, the workflow and the task question.

`SettingsResolutionTest` and `ExtensionSettingsResolutionTest` hold the eight positions and the three
rules in the core. `ExtensionSettingsPositionsTest` exists once per platform and holds them against a
booted application which runs two adapters of one BPMS type, each position written once, so every
assertion says which position won and the second adapter says that a value of one adapter never
reaches the other.

### Binding your own section

`vanillabp.extensions.<extension>` is the section VanillaBP binds itself, as flat text. It is the
right place for an extension without a past, and the calls above read it with no work on your side.

The section name is yours, though. The Business Cockpit is configured below `vanillabp.cockpit`,
which is where version 1 configured it, so an application moving to version 2 swaps a dependency and
changes no configuration. Flat text also stops being enough as soon as your settings have lists or
nested groups, which cannot be assembled out of a map of strings.

So bind your own typed tree and hand the walk over the levels to the core anyway. `SettingsLevel<S>`
in `io.vanillabp.integration.extension.spi.settings` is one level of your tree, with three methods:
`settings()` for what the level says, `settingsOfAdapter(adapterId)` for what it says about one
adapter, and `levelBelow(id)` for the next more specific level. `S` is whatever a section is for you,
your own type; the core answers with the `Map<String, String>` it binds itself. `SettingsResolution`
then walks it: `positions(...)` answers the sections which say something, least specific first;
`resolve(positions, valueExtractor)` reads the value the most specific one writes; and one call does
both. The extractor form is the same shape `resolveForAdapter` has.

The order of the eight positions lives in that one class and nowhere else, which is the whole point
(decision 53). What your keys MEAN stays your business, and so does binding and validating them,
typed, the way an adapter binds the keys below its adapter id.

### The Quarkus trap

On Quarkus every key below `vanillabp` has to be declared by some registered `@ConfigMapping`.
There is no blanket ignore of unknown keys any more, so a key nothing binds does not merely go
unread: it ends the startup, naming the key. A typo in your section is reported that way, which is
the good half. The other half is a duty: every position you offer your users has to be part of a
mapping you registered, because a position nothing binds is not a position an application can write.
`UnknownExtensionSettingsKeyTest` holds both halves with a section spelled `extension` where the
binding declares `extensions`, below an adapter.

Spring Boot detects no unknown keys, so there is no counterpart there. The asymmetry is accepted,
and it means a mistake of this kind shows up on Quarkus first.

One more thing about Quarkus will cost you a day if you learn it the hard way. Never `@Inject` a
`@ConfigMapping` interface. Injecting it turns the mapping into a static-init one, and SmallRye then
validates the whole `vanillabp.*` tree before any adapter extension registered its runtime overlay,
so every adapter-specific key fails the startup with a message pointing at that key rather than at
the injection which caused it. Read the mapping instead, through
`ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)`.

## 5. What the core offers beyond the pipeline

Decision 35 names four things VanillaBP gives an extension instead of a base class. Two of them have
been read already: the election of section 3 and the configuration of section 4. Here are the other
two, plus the outbox and what the boot says about you. Each of them is parameterized by you rather
than shaped after one extension, which is why nothing in the core knows what a user-task detail is.

### Your own annotation

Your users write an annotation of yours on the methods of their `@WorkflowService` classes, and
VanillaBP runs those methods with the mechanics of `@WorkflowTask`: it finds them, binds their
parameters, loads the workflow aggregate, invokes, saves, in one transaction. You describe what your
annotation means with a `HandlerContract` and register it with `ExtensionHandlers#register`, a bean
of both platforms.

```java
HandlerContract
    .of("business-cockpit", UserTaskDetailsProvider.class)
    .lookupKeys(annotation -> keysOf((UserTaskDetailsProvider) annotation))
    .coreParameters(WORKFLOW_AGGREGATE, TASK_PARAM, MULTI_INSTANCE)
    .parameterBinder(parameter -> parameter.getType().equals(PrefilledUserTaskDetails.class)
        ? Optional.of(HandlerContext::getPayload)
        : Optional.empty())
    .deliversReturnValue()
    .build();
```

The contract is described in full under
[The extension's own annotation: handler contracts](./README.md#the-extensions-own-annotation-handler-contracts)
in the core's README. What matters most while you design yours is what an invocation does. You name
the keys your event is about, in the order you prefer them, and the first key some method serves
wins; the element id belongs first, because that is the identity everything moves to and the
`taskDefinition` attribute goes away later (decision 51). Zero matches are legal and answered with an
empty result, so what to do instead stays your decision. Two methods serving one key of one BPMN
process end the boot naming both.

Where you can, say `neverSavesTheWorkflowAggregate()` on a contract whose methods only read. Without
it every application using your extension reads a warning at every start about a second writer which
cannot happen, and a warning nobody can act on is one people learn to skip (decision 50).

Register the contract wherever it is convenient. Whether your bean exists before or after the
workflow services were scanned depends on what else the application does, and the registry applies a
contract arriving later to the classes registered so far (decision 36,
`ExtensionHandlerRegistryTest#aContractMayArriveAfterTheScan`).

The registry also knows things about the application you would otherwise scan for.
`workflowAggregateOf(module, process)` answers which aggregate class a BPMN process works on,
`bpmnProcessesOf(module)` names every process a `@WorkflowService` declares in a workflow module, and
`bpmnTaskNameOf(module, process, activityId)` answers the name a modeller wrote on an element. Ask
instead of scanning the beans again: a second scan has to unwrap the proxies of a platform you
should not have to know about, and it reads a different set of methods than VanillaBP does.

### Your own service per workflow aggregate

`ProcessService<A>` is injected with the workflow aggregate as its type argument, and a service of
yours can be injected the same way. Contribute an `AggregateServiceFactory`, which names the
interface you offer and builds one instance for an aggregate, and the platform builds one bean per
workflow aggregate of the application.

Injecting it is optional, unlike `ProcessService`: the bean is built on first use, so an application
never asking for your service never runs your factory. The `AggregateServiceContext` a factory is
handed carries the aggregate class and its persistence, the workflow module and the BPMN process,
the handler methods of the application and the election.

Your interface has to take exactly one type parameter, the workflow aggregate. On Spring Boot the
interface is read off the factory's bean definition. On Quarkus it is read off the Jandex index,
which means your factory has to be indexed: a runtime module with a Jandex index brings that along,
and one without says which class matters with an `AdditionalIndexedClassesBuildItem`.

### Operations of your own in the outbox

Work which has to happen after the application's transaction committed belongs in the outbox, the
same one VanillaBP uses. Build the operation with `PhaseOperation.extensionOperation(name)`, which
enforces a namespace of the form `my-extension:NOTIFY`, and register it together with your own
dispatch on the `PhaseOperationRegistry`, a bean of both platforms. Scheduling then works as it does
for a core operation: build the call with `PhaseTwoCall.of(operation, ...)` and hand it to the
outbox, inside the business transaction. Dispatch goes straight to your handler, without the
adapter election the core operations run through.

Which store the entry belongs in is not your decision, and picking a `PhaseTwoOutbox` bean yourself
would be right until the first application with two persistences.
`PhaseTwoOutboxResolver#resolveFor(workflowAggregateClass)` answers the store the workflow's own
entries go into, which is the whole point, since only an entry in the transaction of the aggregate is
committed with it. A `null` answer means the application has no outbox at all, and
`#remediesDescription()` says what to add for the platform in use, so you can end your own boot with
a message a developer can act on. The transaction follows the same rule:
`TransactionRunnerResolver#resolveFor(workflowAggregateClass)` answers the runner the workflow's own
writes go through, which may well be one the application contributed, and opening a transaction of
your own loses `beforeCommit`, the rollback-only verdict and the optimistic-locking recognition of
that runner.

`PhaseOperationRegistryTest` holds what the registry enforces, and `ExtensionOperationDispatchTest`
runs an extension operation through both platforms.

### What the boot says about you

Once a workflow module is deployed, the registry writes one line per extension, workflow module and
BPMN process naming your methods and the keys each of them serves, with the catch-all marked as the
one serving every element. It is what a developer reads whose method is never called. A contract
registered after the module was deployed writes its own line when it arrives, and a BPMN process you
have no method for is not named at all
(`ExtensionHandlerRegistryTest#theBootSaysWhatWasWired`, `ExtensionHandlerWiringReportTest` per
platform).

A method the scan cannot reach is reported too, for your annotation exactly as for `@WorkflowTask`.
A handler which is not public, and an override which repeated no annotation, are lost the same way,
and for your extension the consequence is quieter than an unserved task and worse than one: the
application simply behaves as if the method had never been written
(`HandlerMethodsNobodySees`, `ExtensionHandlerMethodsNobodySeesTest`).

What your contract cannot describe, check yourself. `validatingAnnotation(check)` runs a check of
yours once per occurrence, while the scan holds the method, and a check refusing by throwing ends
the boot with the annotation, the class, the method and your extension in front of what it said.
Without it you walk the classes of the application a second time, and that walk never sees the same
methods.

## 6. What an extension must not do

Every line here is either a mistake which was made once or a promise which does not exist.

Do not put your SPI into the VanillaBP core. A cockpit-shaped SPI in the core, `getUserTaskDetails`
and friends, would have made the next extension a change to VanillaBP (decision 35). Your annotation
and your service interface live in your artifact, and the core learns about them through the
contract and the factory.

Do not change what the adapter owns. You see the model after the adapter prepared and wired it, and
you add relative to what it left there. Rewriting its listeners or rescoping its identifiers breaks
a promise the adapter made to its BPMS, and you are not the party which can tell whether it does:
only the adapter reads its own BPMN dialect.

Do not rely on the order of two extensions. `getOrder()` sorts the extensions of one model type and
nothing more is promised, so anything you build on running after another extension is built on the
order a platform happened to collect beans in (decision 52). What you may rely on is the adapter
being first.

Do not address the first-priority adapter. Ask `WorkflowElection#adapterIdOfWorkflow` per workflow.
During a migration the answer differs per workflow, and a wrong one talks to the BPMS which does not
hold it.

Do not derive the adapter ids yourself. `MigrationAdapterProperties#adapterIdsOfType` is the one
place the three rules live, and a copy which drops one leaves the adapter registered and your bridge
missing (decision 39).

Do not write a second resolution for your settings. The eight positions and "most specific wins" are
the core's, and an extension parsing the flat keys itself is what decision 48 was written about.

Do not open a transaction or pick an outbox store of your own where the work belongs to a workflow
aggregate. Use the two resolvers, for the reasons in section 5.

Do not assume you are started before anything runs. Every adapter of every workflow module is
processing workflows before your `startWorkflowProcessing` is called, so whatever has to be in place
earlier belongs into `wireBpmn`.

## 7. Registering per platform

The sample extension of each platform is the template. Both are built like the Business Cockpit in
miniature, with an annotation of their own, a per-aggregate service, the election, an outbox
operation and their own settings, and both are exercised by the platform's own tests.

* Spring Boot: `spring-boot-integration/integration-tests/sample-extension`
* Quarkus: `quarkus-integration/integration-tests/sample-extension`

On Spring Boot you contribute beans, usually from an auto-configuration ordered after
`io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration`. Order it by
NAME, as the sample does, so your module needs no dependency on the platform integration. A wiring
service is a bean of type `ExtensionWiringService`; the adapters' deployment services are wiring
services too and appear in the same collection, and the platform filters them where only extensions
are meant.

On Quarkus your extension is a Quarkus extension, with a runtime module and a deployment module. The
runtime module produces the beans, `@Singleton` for anything without a no-arg constructor, since such
a bean is not client-proxyable. The deployment module registers those producers with an
`AdditionalBeanBuildItem` marked `setUnremovable()`, because the platform looks the beans up through
`Instance` and ArC would otherwise remove them. Unlike an adapter, an extension announces no build
item of its own.

## 8. What the core promises you

|                                               Promise                                                |                                                                              What holds it                                                                               |
|------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| The adapter wired a process before you see it, and is started before you are                         | decision 52, `DeploymentServiceTest#theAdapterIsFirstOnTheWayUp`, `DeploymentPipelineTest#extensionsWiredInOrder`                                                        |
| You are stopped before the adapter is                                                                | `DeploymentServiceTest#extensionWiringServicesAreStoppedBeforeAdapters`, `ShutdownReverseOrderTest`                                                                      |
| An extension whose types do not match is neither wired nor started                                   | `DeploymentServiceTest#extensionWiringServicesAreFilteredAndCalled`, `#subtypeExtensionIsNeitherWiredNorStarted`, `DeploymentPipelineTest#nonMatchingExtensionUntouched` |
| Extensions are sorted by `getOrder()`, and nothing beyond that is promised                           | decision 52, `DeploymentServiceTest#wiringServicesAreSortedByOrder`                                                                                                      |
| Eight positions, most specific wins, adapter section beats its own level                             | decisions 48 and 53, `SettingsResolutionTest`, `ExtensionSettingsResolutionTest`, `ExtensionSettingsPositionsTest` per platform                                          |
| A value of one adapter never reaches another one                                                     | `SettingsResolutionTest#anotherAdapterReadsTheGeneralSections`, `ExtensionSettingsPositionsTest#theSecondAdapterReadsItsOwnValues`                                       |
| A key below `vanillabp` which no mapping declares ends a Quarkus start, naming the key               | `UnknownExtensionSettingsKeyTest`                                                                                                                                        |
| Your annotation is run with the mechanics of `@WorkflowTask`                                         | `ExtensionHandlerRegistryTest`, `ExtensionHandlerTest` (Spring Boot), `ExtensionEnablementTest` (Quarkus)                                                                |
| A contract registered after the scan finds the same methods                                          | decision 36, `ExtensionHandlerRegistryTest#aContractMayArriveAfterTheScan`                                                                                               |
| The first offered key some method serves wins, and the catch-all stays the fallback                  | decision 51, `ExtensionHandlerRegistryTest#theFirstKeyServedWins`, `#aNamedKeyAlwaysBeatsTheCatchAll`                                                                    |
| A contract which never writes is not warned about                                                    | decision 50, `ExtensionHandlerRegistryTest#aReadingContractDoesNotSaveTheAggregate`                                                                                      |
| The boot says which method serves which key, and names a method the scan cannot see                  | `ExtensionHandlerRegistryTest#theBootSaysWhatWasWired`, `#anInvisibleExtensionHandlerIsReported`, `ExtensionHandlerWiringReportTest` per platform                        |
| The election answers which BPMS holds a workflow, and refuses with a guiding message where it cannot | `ExtensionElectionAndConfigurationTest`, `ExtensionEnablementTest`                                                                                                       |
| An operation of yours reaches your own handler                                                       | `PhaseOperationRegistryTest`, `ExtensionOperationDispatchTest` per platform                                                                                              |

One statement of this document is an assumption rather than something a test holds. Telling two
adapters apart by the identity of the processing context works because the core builds one context
per workflow module and adapter id, which is what `DeploymentService` does today. Nothing promises
that an adapter hands out a distinct object per id, so an extension which needs the id with
certainty registers per id from `adapterIdsOfType` instead.

## 9. The checklist before your first release

1. One instance of whatever you keep per configured adapter id, and state separated by id. Nothing
   built on the first-priority adapter, and the id set read from `adapterIdsOfType`.
2. Model type and processing-context type declared as widely as your work allows, so every adapter
   you can serve matches you and the ones you cannot leave you alone.
3. Everything you add to a model placed relative to what the adapter left there, and nothing of the
   adapter's rewritten.
4. Your settings read through the core's resolution, your section named after you, your keys bound
   and validated typed, and every position declared in a mapping on Quarkus.
5. Your annotation described by a handler contract, with a check of your own where the contract
   cannot describe what an attribute means, and `neverSavesTheWorkflowAggregate()` where your
   methods only read.
6. Outbox work scheduled through the resolved store, in the transaction of the aggregate, with a
   namespaced operation and your own dispatch.
7. Messages which name the workflow module, the BPMN process and the property key, so an application
   reaching a broken setup is led out of it by its own log.
8. Both platforms, each with its own tests, and a dependency list which holds the two SPI artifacts
   and the core and no platform integration.

## Where to look next

The SPI's own javadoc is the reference, and it is more detailed than this document wherever the two
overlap. Start with `ExtensionWiringService`, whose type javadoc carries the ordering contract, and
with `SettingsLevel` and `SettingsResolution` for the settings.

[`README.md`](./README.md) of this module is the contributor documentation of the core. Its section
[Extensions](./README.md#extensions) is this document's subject seen from the inside, and the
sections around it explain why the SPI looks the way it does.

[`DECISIONS.md`](../DECISIONS.md) of this repository is where the reasoning lives which several
places rely on. This document points at entries 7, 9, 35, 36, 39, 48, 50, 51, 52 and 53.

The wiki page
[Extensions](https://github.com/vanillabp/adapter-platform-integration/wiki/Extensions) is the
user-facing half of the same subject.

If something here is wrong, or if you need a promise this SPI does not make, tell us. The shape you
build against is the one we are willing to live with, and an extension written outside this
repository is exactly what it was finalised for.
