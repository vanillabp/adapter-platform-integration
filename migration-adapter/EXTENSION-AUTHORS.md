# Writing a VanillaBP extension

This document is for a team outside this repository which builds an extension of VanillaBP. Read it
once from top to bottom. It describes the interface you implement and when the core calls you. It
says what each call may see and where your settings live. And it says what the core promises you and
what you must not do.

Everything here comes from the code of this repository or from a numbered entry of its
[`DECISIONS.md`](../DECISIONS.md). Where a statement is only an assumption, it says so. Every promise
names the test which holds it. So if a promise stops being true, a build turns red here, and you do
not find out in your extension.

If you want to connect VanillaBP to a BPMS (business process management system) instead, read
[`ADAPTER-AUTHORS.md`](./ADAPTER-AUTHORS.md). Adapters and extensions are the two kinds of plug-in
which share one interface. The two documents describe its two sides.

## Vocabulary

A *process* is the BPMN model. A *workflow* is one running instance of it. Use the two words
strictly this way, because the SPI (service provider interface) does.

A *workflow module* is one deployable unit of BPMN files plus the code which serves them. The file
`META-INF/workflow-module` marks it, and the content of that file is the module id. A *workflow
aggregate* is the application's own entity. It holds all state of one workflow.

An *adapter type* is one BPMS implementation as a whole. A short constant such as `camunda7` names
it. An *adapter id* is one configured instance of that type. An application may configure several
ids of one type at the same time. This is the migration feature, and it is why most rules below are
written per id.

Below, the *core* means `migration-adapter`, the part of VanillaBP which does not depend on a
platform. The *platform integration* is the Spring Boot or Quarkus layer which runs the core.

The word *extension* belongs to the contributors. A user of VanillaBP does not know it. Users know
VanillaBP adapters, and if they run the Business Cockpit, they know Business Cockpit adapters. The
configuration speaks their language: the section of your settings carries your own name, not the
word `extensions` (decision 53). So when you write documentation for your users, use the name they
know you by where this document says "extension".

## 1. What an extension is, and what it is not

An adapter and an extension both implement `ExtensionWiringService<BPMN, PC>`, because both take
part when a workflow module is deployed. Everything else about them is different. The line between
them is the question who owns the model.

|                    |                      BPMS adapter                       |                                    Extension                                    |
|--------------------|---------------------------------------------------------|---------------------------------------------------------------------------------|
| Interface          | `AdapterDeploymentService`, which extends the one below | `ExtensionWiringService`                                                        |
| The BPMN model     | it reads, prepares and deploys it                       | it sees what the adapter read, and may add to it                                |
| The BPMS           | every workflow operation runs against it                | it never talks to a BPMS on VanillaBP's behalf                                  |
| How many per model | one per configured adapter id                           | any number                                                                      |
| Examples           | `camunda7`, `camunda8`, `process-engine-api`            | the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit) |

The SPI was shaped around the Business Cockpit, and this document uses it as an example. But
nothing here needs it. An application which runs your extension needs VanillaBP, one adapter and
your artifact.

Version 1 had no such SPI. Back then, the Business Cockpit adapter was built on whatever parts of the
platform integration happened to be public: `AbstractTaskWiring`, `TaskHandlerBase` and a second
configuration-properties bean of its own. Every change to one of those classes changed the
extension. An extension for Quarkus was not possible at all. Version 2 gives you a described contract
instead of a base class, and everything below follows from that (decision 35).

Four things come before the interface.

You never talk to a BPMS on VanillaBP's behalf. The adapter deploys, starts workflows, completes
tasks and answers where a workflow lives. If you need a BPMS of your own, you connect to it yourself.
Before you do, you ask the core which BPMS holds the workflow (section 3).

Your own SPI stays yours. The annotation your users write, the service they inject and the types your
methods take do not belong in the VanillaBP core. The core knows nothing about user-task details. An
application which does not use your extension must not see any of your annotations. The core gives
you a way to describe your annotation. VanillaBP then runs the annotated methods with the mechanics
of `@WorkflowTask` (section 5).

You ship both platforms. An extension which exists only on Spring Boot does not count, because
VanillaBP promises the same behaviour on Quarkus and on any platform which comes later. That is why
this repository has one sample extension per platform.

You depend on the two SPI artifacts and on the core, and on no platform integration. These are:

* `io.vanillabp:vanillabp-extension-spi`, which has no dependencies at all,
* `io.vanillabp:vanillabp-integration-spi`, if you touch the outbox or the transaction,
* `io.vanillabp.adapter:migration-adapter`, for `MigrationAdapterProperties`.

The sample extension of each platform is built exactly this way, and its POM says so. With a
dependency on `vanillabp-spring-boot-integration` it would no longer prove anything.

## 2. The six calls of the wiring service

`ExtensionWiringService<BPMN, PC>` has six methods. Three of them say who you are. The core uses them
to decide whether you take part at all. The other three are the calls in which you act.

|                           Method                            |                 When the core calls it                  |                                   What it is for                                   |
|-------------------------------------------------------------|---------------------------------------------------------|------------------------------------------------------------------------------------|
| `getModelType()`                                            | while it matches you against an adapter                 | the BPMN model type you understand, for example Camunda 7's `BpmnModelInstance`    |
| `getProcessContextType()`                                   | while it matches you against an adapter                 | the adapter's processing-context type you expect                                   |
| `getOrder()`                                                | once, while the wiring services are sorted              | your position among the EXTENSIONS of that model type, ascending, default `0`      |
| `wireBpmn(module, filename, bpmnProcessId, model, context)` | per executable BPMN process, after the adapter wired it | inspect the model, find your own annotated methods, add what you need to the model |
| `startWorkflowProcessing(module, context)`                  | after every adapter of every module was started         | open what consumes your wiring, a listener or a worker of your own                 |
| `stopWorkflowProcessing(module, context)`                   | on a graceful shutdown, before any adapter is stopped   | close what you opened; the default does nothing                                    |

`BPMN` and `PC` are the adapter's types, not yours. You declare which types you can work with. The
core calls you only where both of your declared types are assignable from the adapter's types. So a
Camunda 7 extension is not touched while a Camunda 8 module is deployed. An extension declared
against `Object` and `Object` sees every BPMS but can read no model. That is the honest shape for an
extension which only needs to know that a module was deployed. The price is in the second type. An
extension which wants to add something to the adapter's processing context has to name that context
type, and so it becomes specific to one BPMS.

Matching is all-or-nothing on purpose. Both calls in the pipeline use the same rule. So an extension
is either wired and started, or neither. `DeploymentServiceTest#extensionWiringServicesAreFilteredAndCalled`
and `#subtypeExtensionIsNeitherWiredNorStarted` hold this in the core.
`DeploymentPipelineTest#nonMatchingExtensionUntouched` holds it against a booted application.

### The order, up and down

The adapter of the BPMS runs before every extension. It has wired a BPMN process before you see that
process. It processes workflows before you are started. This is the only promise about order which
VanillaBP makes to you. It lets you place what you add relative to what the adapter put there, for
example behind the last listener of a kind. You do not have to guess a position. On the way down the
order is mirrored. Extensions are stopped first and the adapters last, so nothing is stopped while
something else still feeds it. Decision 52 covers both halves.
`DeploymentServiceTest#theAdapterIsFirstOnTheWayUp` and
`DeploymentPipelineTest#extensionsWiredInOrder` hold the way up.
`DeploymentServiceTest#extensionWiringServicesAreStoppedBeforeAdapters` and `ShutdownReverseOrderTest`
hold the way down.

Two details of the shutdown come from the code, so do not guess them. The core walks the workflow
modules in reverse order. It walks the extensions in reverse wiring order, so the extension which was
wired last is stopped first. And this pass is complete before the adapters begin. When the first
adapter is asked to stop, every extension of every workflow module is already stopped. Starting
works the same way in the other direction. Every adapter of every workflow module is started before
the first extension. So when your `startWorkflowProcessing` runs, workflows may already be running.

The order of two extensions among themselves is not promised. They are sorted by `getOrder()`,
ascending. Two extensions which do not know each other cannot agree on a number. So two extensions
with the same order run in the order in which the platform happened to collect their beans. Nothing
promises that this order stays the same. VanillaBP gives out no first and no last position, on
purpose. Such a scheme breaks as soon as a second extension wants to be last (decision 52).
`DeploymentServiceTest#wiringServicesAreSortedByOrder` holds the sorting. Nothing holds more than
that, and that is the point.

### What `wireBpmn` may do to a model

The adapter owns the model. It parsed the model and rewrote it for its BPMS, and it deploys it after
you have seen it. What you add has to survive that. In practice you add relative to what the adapter
left behind, and you change nothing it put there. On Camunda 8 the Business Cockpit adds its
`creating` listener behind the last `creating` listener, and the rest behind everything. On Camunda 7
the adapter offers `parseListenersAfter` and `parseListenersBefore` for the rare element which has to
be seen untouched (decision 52). If something has to see an element before the adapter touched it,
it needs an entry point of its own, and you have to ask us for one.

Two things about the model belong to the adapter alone. Identifiers are scoped at the BPMS boundary
and nowhere else (decision 9). So do not prefix, tenant or rename anything on its way out. And the
adapter reads what a model declares, because only the adapter can read its own BPMN dialect. If you
need something the adapter already read, ask the core for it. Do not parse the file a second time.
`ExtensionHandlers#bpmnTaskNameOf(module, process, activityId)` answers the `name` a modeller wrote
on an element. The core keeps it from what the adapter handed over during wiring.

A process which no `@WorkflowService` claims reaches your `wireBpmn` too, because the core hands you
every process of the file. The adapter leaves such a process as it was modelled (decision 120 in the
repository's DECISIONS.md). An extension should do the same: report nothing about it and add nothing
to it. To find out, ask
`WorkflowTaskWiring#isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)`. Your extension gets `WorkflowTaskWiring` the way an adapter does, as a bean on
Spring Boot and on Quarkus.

## 3. An extension hangs on every configured adapter

The core builds one processing context per workflow module and adapter id. Your three calls are made
against that context. So in an application with two configured adapters, you are called twice, once
for each adapter. `wireBpmn` runs once per adapter per BPMN process. `startWorkflowProcessing` and
`stopWorkflowProcessing` run once per adapter per workflow module. This is not an edge case. Two ids
of one BPMS type side by side is what a migration looks like, and VanillaBP exists for this setup.

So you follow the same rule as an adapter. Prepare per adapter id, not once. Open your workers per
adapter id. Keep everything you remember separated by adapter id, because you must not answer a
workflow of one BPMS with what you learned about the other. The Business Cockpit is the example. It
registers one bridge per configured adapter id, the way an adapter registers one set of beans per
id.

There is one thing the SPI does not do for you here: none of the six calls names the adapter id.
`wireBpmn` and `startWorkflowProcessing` get the workflow module and the adapter's processing
context, and nothing else. There are two ways around this.

* You tell the adapters apart by the context object itself, because the core holds one per workflow
  module and adapter id. You key your state by that object. Section 8 says why this is an
  assumption and not a promise.
* You register one instance of something of your own per adapter id. An extension which bridges to
  a BPMS does this anyway. Then each instance knows its id. This is the safe way, and the Business
  Cockpit uses it.

For the second way, the set of ids comes from
`MigrationAdapterProperties#adapterIdsOfType(adapterType)` and from nowhere else. Three rules decide which ids one type serves. Two of them cover an id which
is only named in `prioritized-adapters`, and an application which configures nothing at all. Every
consumer which built the list again on its own missed one of the three (decision 39). This method
exists so that nobody derives the ids from the sections they can see.

### Which BPMS holds this workflow

Before you talk to a BPMS about a running workflow, ask `WorkflowElection#adapterIdOfWorkflow`. Both
platforms provide it as a bean. During a migration the answer differs from workflow to workflow. If
you asked the first-priority adapter instead, you would be wrong for every workflow which has
already moved.

This is the election (the choice of the BPMS which holds a workflow) which every operation of
VanillaBP uses, in its form for reading. A workflow which has ended is a normal answer here, the same
way the viewer API reads its history. A workflow which no BPMS knows, and a BPMN process this
application does not serve, lead to errors which name what was asked and guide you on.

Before you call it, you have to know about the waiting. A hint may say that an adapter holds the
workflow while that adapter's read model has not caught up yet. Then the election waits out that
adapter's window and does not answer "unknown", because nobody asks again for an extension. On a
Camunda 8 cluster that window is ten seconds. A read of the viewer API waits on a thread which does
nothing else. But an extension often asks from inside a transaction of the application. Reporting a
change out of a service task is exactly that case. The wait then holds the transaction open, together
with its database connection and its locks. The section
[What an election costs a caller which holds a transaction](./README.md#what-an-election-costs-a-caller-which-holds-a-transaction)
of the core's README has the numbers.
`ExtensionElectionAndConfigurationTest#theElectionAnswersTheExtension` on Spring Boot and
`ExtensionEnablementTest` on Quarkus run this against a booted application.

`WorkflowElection#locationOfWorkflow` gives the answer of the same election as a `WorkflowLocation`.
That is the adapter id and, next to it, the BPMS' own id of the workflow. VanillaBP writes a record
per task delivery which keeps the workflow id next to the adapter id. So you get the whole row, not
half of it. It runs exactly the election above and costs exactly the same.

`null` as the workflow id is a normal answer. It means nothing VanillaBP holds knew the id, because
no delivery was recorded for that workflow or its record expired.

You may write the id into your own records and print it in a log line beside ours. You may also hand
it back to VanillaBP later, so that an adapter can ask its engine by key and does not have to wait
for a read model. You must not address the BPMS with it. The shape of that id belongs to the
adapter. Sending commands to an engine behind the adapter's back is outside everything this platform
promises. And you must not read a non-null id as "this workflow is still running". The record is
history. The election is the answer about now.

Both methods first read the row which was written when the workflow started. If that row names an
adapter which is still configured for the workflow, then that adapter and the workflow id of the row
are the answer. No BPMS is asked and nothing waits. So a report out of your application's transaction
does not hold that transaction open. The adapter of that row started the workflow, and a workflow
does not change its BPMS. For the same reason the two methods do not throw after the workflow ended,
as long as the row exists. Without the row, the election runs as described above.

`DerivedCancelationTest#theElectionCarriesTheWorkflowId` runs it against a booted application on
both platforms.

At one moment there is no row and no hint at all. That is the `@WorkflowStartedByBpms` method of the
application, which builds the aggregate of a workflow the BPMS started. The row is written after the
method returned, and on Camunda 8 the cluster does not show the workflow yet. A report about that
aggregate from inside the method finds nobody, and the election throws. So ask
`WorkflowElection#isInsideTheStartOf(aggregate)` first, and skip the report if it answers `true`.
Nothing is lost. When the start is done, it hands the aggregate's values to the BPMS. If you observe
starts, you see this one then. The answer is `true` only for an aggregate which the method is
building: one without an id, or with an id its persistence does not know. An aggregate which already
exists gets `false`, because the method may return one with other workflows and open tasks.
`ProcessService#aggregateChanged` applies the same rule and does nothing for such an aggregate.
`AReportInsideTheStartOfAWorkflowTest` holds the rule.

`WorkflowElection#workflowIdOf` answers the id and NOTHING else. It elects nothing: no BPMS is asked
and nothing waits. Ask it when you want the id for your own report, not when you are about to talk
to an engine. It reads what VanillaBP stored: first the row written when the workflow started, then
the rows of that workflow's task deliveries. So it answers from the moment the workflow came into
existence. In that window a BPMS which answers from a read model reports nothing at all.

`Optional.empty()` covers several situations and does not tell them apart:

* this application never started that workflow,
* the start happened before the row existed,
* the adapter names no id,
* the period `vanillabp.delivery.workflow-start-retention` passed,
* or there is no store for that aggregate.

So empty means "VanillaBP does not know". It never means "there is no such workflow". A BPMN process
which no `@WorkflowService` of this application declares is refused with an `IllegalStateException`,
the same way the election refuses it. VanillaBP writes nothing down for such a process, so asking
about it is a mistake, and an empty answer would hide that mistake. `workflowStartOf` refuses it the
same way. What the paragraphs above say about what you may do with the id holds here as well.

`WorkflowElection#workflowStartOf` reads the same rows. It answers the id together with the version
of the process definition the workflow runs on, as a `WorkflowStart`. Ask it when you pick an
implementation per version. It is empty in exactly the cases in which `workflowIdOf` is empty. It
also names the adapter which started the workflow. A workflow does not change its BPMS, so that
adapter holds it until its end, and you learn the adapter without an election. The adapter is empty
if VanillaBP knows the id only from its election cache.

An empty version inside a non-empty answer means one of two things, and `versionsAreReported` tells
them apart. If it is `true`, the adapter of that workflow reports versions, and the version may
still come. A later report of the same start brings it, and so does a task of that workflow which
waits for your application. If it is `false`, the adapter has no versions or said nothing about
them, and you would wait forever.

## 4. Where your settings live

Your settings are written at four levels. Each level has two positions: what the level says in
general, and what it says for one adapter. That makes eight positions. Here they are, from the least
specific to the most specific one.

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

`<section>` is the name your settings live under. If VanillaBP binds them for you, it is
`extensions.<extension>`. If you bind a tree of your own, it is whatever name you gave yourself. Both
cases follow below.

Three rules decide what a scope reads:

1. The most specific position which writes a key wins.
2. What one adapter is told beats what the same level says in general.
3. A more specific level beats the adapter section of a less specific level. So a value written for
   the workflow beats a value written for one adapter at the workflow module.

The positions are merged key by key, not as whole blocks. So a workflow may change one value and
keep what the application said about the rest. The level is called `tasks`, and a task is named by
its task definition. One key of the core, `implemented-externally`, already takes the element id
there as well. Where both are written, the element id wins (decision 119 in the repository's
DECISIONS.md). Your settings keep the task definition until this general change reaches them.

The adapter positions exist for the same reason as section 3. An application which migrates from one
BPMS to another runs two adapters, and you hang on both of them. A value which has to differ between
them needs a place where it can be written (decision 53).

An adapter setting uses the same resolution. It is written once in the core (decision 7 and
`#resolveForAdapter`). So a change to "most specific wins" reaches both or neither. Decision 48
forbids you to write it a second time in your own code. It forbids this because it had already
happened. The Business Cockpit read the flat keys, matched the suffixes by hand and decided on its
own what "most specific" meant, once per platform. The two versions drifted apart before anybody
noticed.

### Reading them

`MigrationAdapterProperties` is a bean of both platforms, and it reads your settings for you. Your
extension id is a parameter of the call. It is not bound to a view of these properties, because the
properties are one bean of the platform, and your extension is not a bean of the platform at all.
You ask with your id, the way an adapter asks with its adapter id.

|                                 Call                                  |                     What it answers                      |
|-----------------------------------------------------------------------|----------------------------------------------------------|
| `resolveForExtension(module, process, task, adapter, extension, key)` | one value, over all eight positions                      |
| `resolveForExtension(module, process, task, extension, key)`          | one value, over the four positions which name no adapter |
| `extensionProperty(module, extension, key)`                           | one value, asked about the workflow module alone         |
| `extensionProperties(module, process, task, adapter, extension)`      | the whole section, merged key by key                     |
| `extensionProperties(module, extension)`                              | the whole section, asked about the workflow module alone |

Any id may be `null`. A level nobody configured is skipped and is no error. So the same method
answers the question for the whole application, the module, the workflow and the task.

`SettingsResolutionTest` and `ExtensionSettingsResolutionTest` hold the eight positions and the three
rules in the core. `ExtensionSettingsPositionsTest` exists once per platform. It holds them against a
booted application which runs two adapters of one BPMS type, with each position written once. So
every assertion says which position won. The second adapter shows that a value of one adapter never
reaches the other.

### Binding your own section

VanillaBP binds the section `vanillabp.extensions.<extension>` itself, as flat text. It is the right
place for an extension without a past. The calls above read it, and you have no work to do.

But the section name is yours. The Business Cockpit is configured below `vanillabp.cockpit`, where
version 1 configured it. So an application which moves to version 2 swaps a dependency and changes
no configuration. Also, flat text is no longer enough as soon as your settings have lists or nested
groups. You cannot build those out of a map of strings.

So bind your own typed tree, and still let the core walk over the levels. `SettingsLevel<S>` in
`io.vanillabp.integration.extension.spi.settings` is one level of your tree. It has three methods:

* `settings()` for what the level says,
* `settingsOfAdapter(adapterId)` for what it says about one adapter,
* `levelBelow(id)` for the next, more specific level.

`S` is your own type, whatever a section is for you. The core answers with the
`Map<String, String>` it binds itself. `SettingsResolution` then walks the tree.
`positions(...)` answers the sections which say something, least specific first.
`resolve(positions, valueExtractor)` reads the value which the most specific one writes. And one call
does both. The extractor form has the same shape as `resolveForAdapter`.

The order of the eight positions lives in that one class and nowhere else, and that is the whole
point (decision 53). What your keys MEAN is your business. So is binding and validating them as
typed values, the way an adapter binds the keys below its adapter id.

### The Quarkus trap

On Quarkus, some registered `@ConfigMapping` has to declare every key below `vanillabp`. Unknown keys
are no longer ignored as a whole. So a key which nothing binds is not just left unread. It ends the
startup, and the message names the key. A typo in your section is reported this way, which is the
good half. The other half is a duty. Every position you offer your users has to be part of a mapping
you registered. If nothing binds a position, an application cannot write it.
`UnknownExtensionSettingsKeyTest` holds both halves. It uses a section spelled `extension` below an
adapter, where the binding declares `extensions`.

Spring Boot does not detect unknown keys, so there is nothing to match this there. We accept the
difference. It means that a mistake of this kind shows up on Quarkus first.

One more thing about Quarkus costs you a day if you learn it the hard way. Never `@Inject` a
`@ConfigMapping` interface. Injection turns the mapping into a static-init mapping. SmallRye then
validates the whole `vanillabp.*` tree before any adapter extension registered its runtime overlay.
So every adapter-specific key fails the startup, and the message points at that key and not at the
injection which caused it. Read the mapping instead, through
`ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)`.

## 5. What the core offers beyond the pipeline

Decision 35 names four things VanillaBP gives an extension instead of a base class. You have read
about two of them already: the election in section 3 and the configuration in section 4. Here are the
other two, plus the outbox and what the boot says about you. You set the parameters of each of
them. None is shaped after one particular extension, and so nothing in the core knows what a
user-task detail is.

### Your own annotation

Your users put an annotation of yours on the methods of their `@WorkflowService` classes. VanillaBP
runs those methods with the mechanics of `@WorkflowTask`. It finds them, binds their parameters,
loads the workflow aggregate, calls the method and saves, all in one transaction. You describe what
your annotation means with a `HandlerContract`. You register it with `ExtensionHandlers#register`,
which is a bean on both platforms.

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

If your annotation has a version attribute, name it as well. Then your methods are chosen by the
version of the BPMN process an event came from. The selection is the same as for `@WorkflowTask`:

```java
.versions(annotation -> List.of(((UserTaskDetailsProvider) annotation).version()))
.callsCarryTheProcessVersion()
```

The version specifications are the same as for `@WorkflowTask(version = …)`, including version
tags. The tags are resolved through the BPMS. A method which names no version serves every version.
So an application which knows only one generation of its model writes nothing, and nothing changes
for it. Two methods for one key end the boot only if their versions overlap. This lets a user keep
one method per generation. Name the version on every call which has one
(`HandlerCall.Builder#processVersion`), spelled the way the BPMS reports it. And say
`callsCarryTheProcessVersion()` once. Then a method which VanillaBP could never reach is reported
while the application boots, and not only when the first event does not arrive. Whether a version is
there at all is for you to answer, not the application. A Camunda 8 job carries the version of its
process. A Camunda 7 process definition knows it. An engine behind the Process-Engine-API fills a
version tag only where it wants to. So an extension with one half per BPMS answers for the half in
use (decision 56).

A version attribute has a trap. VanillaBP checks the wiring before the deployment, when nobody knows
the version yet. But a call picks its method by version. So a method whose range misses the version
of the deployed model passes the start. After that, no call of that version finds it.
`@WorkflowTask` ends the start in that case (decision 126). Your contract can ask for the same:

```java
.callsCarryTheProcessVersion()
.theDeployedVersionNeedsAMethod("The cockpit shows such a task without the details of its provider.")
```

The core knows your methods, but not the elements of the model they are for. So name the elements in
your `wireBpmn`, once for each BPMN process:

```java
extensionHandlers.elementsOfTheDeployedModel(
    UserTaskDetailsProvider.class, workflowModuleId, bpmnProcessId,
    List.of(List.of("Activity_Review", "reviewForm"),   // a user task: element id, task definition
            List.of()));                                // the process itself
```

Each element is a list of keys. Use the keys you pass to `HandlerCall.Builder#lookupKeys` for that
element, in the same order. An empty list stands for the process. Only a method which serves every
key serves it. When the BPMS has reported the version, the core takes the methods which a call for
that element may reach:

* If there are none, the core leaves the element alone, because "no method" is a valid answer.
* If one of them covers the version, all is well.
* Otherwise the start ends, with the same message as for `@WorkflowTask`:

```text
Version '1' of BPMN process 'loan_approval' (workflow module 'loan-approval') is the version adapter 'camunda7'
deployed during this start. Some of its elements are served by NO method of this application in this version:
  - element 'Activity_Review' (also matched by 'reviewForm'): @UserTaskDetailsProvider method
    'blueprint.LoanApprovalDetails#review' of extension 'business-cockpit' (version '>1'), which does not
    cover version '1'.
For @UserTaskDetailsProvider of extension 'business-cockpit': The cockpit shows such a task without the details of
its provider.
Do one of these:
  - widen the version range of the method so it covers version '1', e.g. version = ">=1".
  - add a method with the same annotation for version '1'.
  - deploy the model the method serves.
```

The sentence in `theDeployedVersionNeedsAMethod(...)` is optional. It tells the developer what the
gap costs. Without it, the message says that the extension goes on without a method. The flag needs
`callsCarryTheProcessVersion()`. A call without a version never picks a method by version. So a
contract which has the flag, but not the other one, ends the boot.

The elements are kept for each workflow module and BPMN process, not for each adapter, because
`wireBpmn` names no adapter. Each adapter checks them against the version which its own BPMS gave
the model. If two adapters deploy different models of one process, they share what you reported.
Decision 127 in the repository's `DECISIONS.md` gives the reasons.

The full description of the contract is in the section
[The extension's own annotation: handler contracts](./README.md#the-extensions-own-annotation-handler-contracts)
of the core's README. While you design yours, the most important thing is what an invocation does.
You name the keys your event is about, in the order you prefer. The first key which some method
serves wins. The element id belongs first. It is the identity everything moves to, and the
`taskDefinition` attribute goes away later (decision 51). Zero matches are allowed and get an empty
result, so you decide what to do instead. Two methods which serve the same key of one BPMN process
end the boot, and the message names both. The only exception is when the versions they serve keep
them apart.

If you can, say `neverSavesTheWorkflowAggregate()` on a contract whose methods only read. Without it,
every application which uses your extension shows a warning at every start. The warning is about a
second writer which cannot happen. Nobody can act on such a warning, so people learn to skip it
(decision 50).

Register the contract wherever it suits you. Your bean may exist before or after the workflow
services were scanned. That depends on what else the application does. The registry applies a
contract which arrives later to the classes registered so far (decision 36,
`ExtensionHandlerRegistryTest#aContractMayArriveAfterTheScan`).

The registry also knows things about the application which you would otherwise have to scan for:

* `workflowAggregateOf(module, process)` answers which aggregate class a BPMN process works on.
* `bpmnProcessesOf(module)` names every process which a `@WorkflowService` declares in a workflow
  module.
* `bpmnTaskNameOf(module, process, activityId)` answers the name a modeller wrote on an element.

Ask the registry, and do not scan the beans again. A second scan has to unwrap the proxies of a
platform you should not need to know about. And it reads a different set of methods than VanillaBP
does.

### Your own service per workflow aggregate

`ProcessService<A>` is injected with the workflow aggregate as its type argument. A service of yours
can be injected the same way. Contribute an `AggregateServiceFactory`. It names the interface you
offer and builds one instance for an aggregate. The platform then builds one bean per workflow
aggregate of the application.

Unlike `ProcessService`, injecting your service is optional. The bean is built on first use. So if an
application never asks for your service, your factory never runs. The `AggregateServiceContext`
which a factory gets carries:

* the aggregate class and its persistence,
* the workflow module and the BPMN process,
* the handler methods of the application,
* the election.

Your interface has to take exactly one type parameter, the workflow aggregate. On Spring Boot the
interface is read from the factory's bean definition. On Quarkus it is read from the Jandex index, so
your factory has to be indexed. A runtime module with a Jandex index brings that along. A module
without one names the class with an `AdditionalIndexedClassesBuildItem`.

### Operations of your own in the outbox

Some work has to happen after the application's transaction committed. It belongs in the outbox,
the same one VanillaBP uses. Phase one is that transaction, and phase two is the moment after its
commit. So the names say when the work runs, not who runs it. A call to a BPMS is one kind of
phase-two work, and an operation of yours is another. Both are entries in the same store.

Build the operation with `PhaseOperation.extensionOperation(name)`. It enforces a namespace of the
form `my-extension:NOTIFY`. Register the operation together with your own dispatch on the
`PhaseOperationRegistry`, which is a bean on both platforms. Scheduling then works as it does for an
operation of the core. Build the call with `PhaseTwoCall.of(operation, ...)` and hand it to the
outbox, inside the business transaction. Dispatch goes straight to your handler. It skips the
adapter election which the core's operations run through.

You do not decide which store the entry belongs in. If you picked a `PhaseTwoOutbox` bean yourself,
it would work until the first application with two persistences.
`PhaseTwoOutboxResolver#resolveFor(workflowAggregateClass)` answers the store into which the
workflow's own entries go. That is the whole point, because only an entry in the transaction of the
aggregate is committed together with it. A `null` answer means the application has no outbox at
all. `#remediesDescription()` then says what to add for the platform in use. So you can end your own
boot with a message a developer can act on.

The transaction follows the same rule. `TransactionRunnerResolver#resolveFor(workflowAggregateClass)`
answers the runner which the workflow's own writes go through. That may well be a runner the
application contributed. If you open a transaction of your own, you lose three things of that
runner: `beforeCommit`, the rollback-only verdict, and the detection of optimistic-locking
failures.

`PhaseOperationRegistryTest` holds what the registry enforces, and `ExtensionOperationDispatchTest`
runs an extension operation through both platforms.

### What a workflow is waiting for

VanillaBP writes down every task delivery it processed. It does this in the transaction which saves
the workflow aggregate. The store behind it is a `TaskDeliveryLog`, and you may READ it.
`TaskDeliveryLogResolver#resolveFor(workflowAggregateClass)` answers the log into which that
aggregate's records were written. `TaskDeliveryLog#openTasksOfAggregate(module, process, aggregateId)`
answers the tasks which the application has not finished yet, oldest first. An extension asks this
before it shows what a case is waiting for. The log gives a durable answer, and you do not need a
memory of your own. The Process-Engine-API half of the Business Cockpit kept one. It lost it on
every restart, and on every task which arrived at another node.

Each record names:

* the task, by `taskId`,
* the element of the model, by `bpmnElementId`,
* the BPMS' workflow, by `workflowId`,
* the adapter which delivered the task, by `adapterId`.

`taskDefinition` answers a different question than `bpmnElementId`. It carries a Camunda 8 job type
or a Camunda 7 topic wherever the model names one.

There are two limits. Both matter before you build a screen on this.

First, the records are the work the APPLICATION was handed. A user task for which nobody wrote a
`@WorkflowTask` method leaves no record, and it is in no answer here. The delivery itself is not
what is missing. The adapter hands such a task to VanillaBP, and finishes the notification itself
when no method serves it. What is missing is the outcome which a record carries. A task the
application was never asked about has no outcome (decision 54).

Second, a record is deleted once `vanillabp.delivery.retention` passed without the task being handed
out again. So the log says what the store still knows, not what the BPMS holds. An empty list
therefore means "nothing recorded". It never means "nothing open".

Inject the RESOLVER, not a `TaskDeliveryLog` bean. The reason is the same as for the outbox above. An
application may run two persistences, and you cannot tell from outside which store serves an
aggregate. The resolver is a bean on Spring Boot (`vanillaBpTaskDeliveryLogResolver`) and on Quarkus
(`TaskDeliveryLogResolverProducer`). A `null` answer means the application configured no store at
all. Your own boot may report that state. `DeliveryLogUsingExtension` is a small model of exactly
this. `ExtensionEnablementTest` injects it on Quarkus, and `ExtensionElectionAndConfigurationTest`
on Spring Boot.

Do not write into the log. The records are the core's bookkeeping of what really ran. An entry which
somebody else put there would answer a redelivery with work which never happened.

### What the boot says about you

Once a workflow module is deployed, the registry writes one line per extension, workflow module and
BPMN process. The line names your methods and the keys which each of them serves. The catch-all is
marked as the method which serves every element. A developer whose method is never called reads
this line. A contract registered after the module was deployed writes its own line when it arrives.
A BPMN process for which you have no method is not named at all
(`ExtensionHandlerRegistryTest#theBootSaysWhatWasWired`, `ExtensionHandlerWiringReportTest` per
platform).

A method which the scan cannot reach is reported too, for your annotation exactly as for
`@WorkflowTask`. A handler which is not public is lost this way. So is an override which did not
repeat the annotation. For your extension the result is quieter than a task nobody serves, and
worse: the application simply behaves as if the method had never been written
(`HandlerMethodsNobodySees`, `ExtensionHandlerMethodsNobodySeesTest`).

Check yourself what your contract cannot describe. `validatingAnnotation(check)` runs a check of
yours once per occurrence, while the scan holds the method. If the check refuses by throwing, the
boot ends. The message puts the annotation, the class, the method and your extension in front of
what your check said. Without this you walk the classes of the application a second time, and that
walk never sees the same methods.

## 6. What an extension must not do

Every rule here is either a mistake which somebody made once, or a promise which does not exist.

Do not put your SPI into the VanillaBP core. An SPI shaped after the cockpit in the core, with
`getUserTaskDetails` and similar methods, would have turned the next extension into a change to
VanillaBP (decision 35). Your annotation and your service interface live in your artifact. The core
learns about them through the contract and the factory.

Do not change what the adapter owns. You see the model after the adapter prepared and wired it, and
you add relative to what it left there. If you rewrite its listeners or rescope its identifiers, you
break a promise the adapter made to its BPMS. And you cannot even tell whether you do, because only
the adapter reads its own BPMN dialect.

Do not rely on the order of two extensions. `getOrder()` sorts the extensions of one model type, and
nothing more is promised. If you build on running after another extension, you build on the order
in which a platform happened to collect beans (decision 52). You may rely on the adapter being
first.

Do not address the first-priority adapter. Ask `WorkflowElection#adapterIdOfWorkflow` for each
workflow. During a migration the answer differs from workflow to workflow. A wrong answer talks to
a BPMS which does not hold the workflow.

Do not derive the adapter ids yourself. `MigrationAdapterProperties#adapterIdsOfType` is the one
place where the three rules live. A copy which drops one rule leaves the adapter registered and your
bridge missing (decision 39).

Do not write a second resolution for your settings. The eight positions and "most specific wins"
belong to the core. Decision 48 was written about an extension which parsed the flat keys itself.

Do not open a transaction or pick an outbox store of your own if the work belongs to a workflow
aggregate. Use the two resolvers, for the reasons in section 5.

Do not assume you are started before anything runs. Every adapter of every workflow module processes
workflows before your `startWorkflowProcessing` is called. So whatever has to be in place earlier
belongs into `wireBpmn`.

## 7. Registering per platform

The sample extension of each platform is the template. Both are small copies of the Business Cockpit.
Each has an annotation of its own, a service per aggregate, the election, an outbox operation and
its own settings. The platform's own tests run both of them.

* Spring Boot: `spring-boot-integration/integration-tests/sample-extension`
* Quarkus: `quarkus-integration/integration-tests/sample-extension`

On Spring Boot you contribute beans. Usually they come from an auto-configuration which is ordered
after `io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration`. Order it
by NAME, as the sample does. Then your module needs no dependency on the platform integration. A
wiring service is a bean of type `ExtensionWiringService`. The adapters' deployment services are
wiring services too, and they appear in the same collection. Where only extensions are meant, the
platform filters them out.

On Quarkus your extension is a Quarkus extension, with a runtime module and a deployment module. The
runtime module produces the beans. Use `@Singleton` for any bean without a no-arg constructor,
because such a bean cannot get a client proxy. The deployment module registers those producers with
an `AdditionalBeanBuildItem` marked `setUnremovable()`. The platform looks the beans up through
`Instance`, and without this mark ArC would remove them. Unlike an adapter, an extension announces no
build item of its own.

## 8. What the core promises you

|                                                          Promise                                                          |                                                                              What holds it                                                                               |
|---------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| The adapter wired a process before you see it, and is started before you are                                              | decision 52, `DeploymentServiceTest#theAdapterIsFirstOnTheWayUp`, `DeploymentPipelineTest#extensionsWiredInOrder`                                                        |
| You are stopped before the adapter is                                                                                     | `DeploymentServiceTest#extensionWiringServicesAreStoppedBeforeAdapters`, `ShutdownReverseOrderTest`                                                                      |
| An extension whose types do not match is neither wired nor started                                                        | `DeploymentServiceTest#extensionWiringServicesAreFilteredAndCalled`, `#subtypeExtensionIsNeitherWiredNorStarted`, `DeploymentPipelineTest#nonMatchingExtensionUntouched` |
| Extensions are sorted by `getOrder()`, and nothing beyond that is promised                                                | decision 52, `DeploymentServiceTest#wiringServicesAreSortedByOrder`                                                                                                      |
| Eight positions, most specific wins, adapter section beats its own level                                                  | decisions 48 and 53, `SettingsResolutionTest`, `ExtensionSettingsResolutionTest`, `ExtensionSettingsPositionsTest` per platform                                          |
| A value of one adapter never reaches another one                                                                          | `SettingsResolutionTest#anotherAdapterReadsTheGeneralSections`, `ExtensionSettingsPositionsTest#theSecondAdapterReadsItsOwnValues`                                       |
| A key below `vanillabp` which no mapping declares ends a Quarkus start, naming the key                                    | `UnknownExtensionSettingsKeyTest`                                                                                                                                        |
| Your annotation is run with the mechanics of `@WorkflowTask`                                                              | `ExtensionHandlerRegistryTest`, `ExtensionHandlerTest` (Spring Boot), `ExtensionEnablementTest` (Quarkus)                                                                |
| A contract registered after the scan finds the same methods                                                               | decision 36, `ExtensionHandlerRegistryTest#aContractMayArriveAfterTheScan`                                                                                               |
| The first offered key some method serves wins, and the catch-all stays the fallback                                       | decision 51, `ExtensionHandlerRegistryTest#theFirstKeyServedWins`, `#aNamedKeyAlwaysBeatsTheCatchAll`                                                                    |
| A contract which never writes is not warned about                                                                         | decision 50, `ExtensionHandlerRegistryTest#aReadingContractDoesNotSaveTheAggregate`                                                                                      |
| The boot says which method serves which key, and names a method the scan cannot see                                       | `ExtensionHandlerRegistryTest#theBootSaysWhatWasWired`, `#anInvisibleExtensionHandlerIsReported`, `ExtensionHandlerWiringReportTest` per platform                        |
| The election answers which BPMS holds a workflow, and refuses with a guiding message where it cannot                      | `ExtensionElectionAndConfigurationTest`, `ExtensionEnablementTest`                                                                                                       |
| An operation of yours reaches your own handler                                                                            | `PhaseOperationRegistryTest`, `ExtensionOperationDispatchTest` per platform                                                                                              |
| The log of processed task deliveries is resolvable per workflow aggregate, and holds the tasks the application was handed | decision 54, `ExtensionElectionAndConfigurationTest`, `ExtensionEnablementTest`, `OpenTaskRetentionTest` per platform                                                    |

One statement in this document is an assumption, and no test holds it. You can tell two adapters
apart by the identity of the processing context. This works because the core builds one context per
workflow module and adapter id, which is what `DeploymentService` does today. But nothing promises
that an adapter hands out a separate object per id. So if your extension needs the id with
certainty, register per id from `adapterIdsOfType` instead.

## 9. The checklist before your first release

1. You keep one instance per configured adapter id, and your state is separated by id. Nothing is
   built on the first-priority adapter, and the set of ids comes from `adapterIdsOfType`.
2. You declare the model type and the processing-context type as widely as your work allows. Then
   every adapter you can serve matches you, and the ones you cannot serve leave you alone.
3. Everything you add to a model sits relative to what the adapter left there. You rewrite nothing
   of the adapter's.
4. You read your settings through the core's resolution. Your section is named after you. Your keys
   are bound and validated as typed values. On Quarkus every position is declared in a mapping.
5. A handler contract describes your annotation. Where the contract cannot describe what an
   attribute means, you have a check of your own. Where your methods only read, you say
   `neverSavesTheWorkflowAggregate()`.
6. You schedule outbox work through the resolved store, in the transaction of the aggregate, with an
   operation in your namespace and your own dispatch.
7. Your messages name the workflow module, the BPMN process and the property key. So when an
   application runs into a broken setup, its own log leads it out.
8. You ship both platforms, each with its own tests. Your dependency list holds the two SPI
   artifacts and the core, and no platform integration.

## Where to look next

The SPI's own javadoc is the reference. Wherever the two overlap, it has more detail than this
document. Start with `ExtensionWiringService`, whose type javadoc carries the contract about order.
For the settings, read `SettingsLevel` and `SettingsResolution`.

[`README.md`](./README.md) of this module is the contributor documentation of the core. Its section
[Extensions](./README.md#extensions) covers the subject of this document from the inside. The
sections around it explain why the SPI looks the way it does.

[`DECISIONS.md`](../DECISIONS.md) of this repository holds the reasoning which several places rely
on. This document points at entries 7, 9, 35, 36, 39, 48, 50, 51, 52 and 53.

The wiki page
[Extensions](https://github.com/vanillabp/adapter-platform-integration/wiki/Extensions) is the half
of the same subject which is written for users.

If something here is wrong, or if you need a promise this SPI does not make, tell us. The shape you
build against is one we are willing to live with. And the SPI was finished exactly for extensions
written outside this repository.
