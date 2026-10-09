# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 7 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision which spans the platform and an adapter gets an entry in each
affected repository, each written from that repository's side, because a pointer into another
repository is the fragile kind this log exists to avoid.

An entry says what was decided, why, and what that means for the code. Anything longer is
documentation and belongs in the [wiki](https://github.com/vanillabp/adapter-platform-integration/wiki),
which an entry may link.

### 1. A class opens its fields one by one, not as a whole

The process service, the delivery store and the handlers of the core hold dozens of fields,
most of them collaborators nobody outside the class needs. Which of them a caller may read
belongs to the surface of the class, so an accessor is declared per field, and `@Getter` on the
class is refused even where an IDE offers it: it would publish the current field list and then
keep publishing whatever field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class is what keeps that offer from
coming back.

### 2. A workflow is progressed after the caller's transaction committed

Every operation which moves a workflow forward is planned inside the transaction the application
called from and executed after that transaction committed, through the phase-two outbox. The
reason is that the application's write and the BPMS command cannot be committed together: a
remote BPMS has no transaction to join, and even an embedded engine cannot repeat a command which
lost a concurrency conflict inside the caller's transaction, because the conflict leaves that
transaction rollback-only. Advancing the process while the application rolls back is the failure
this avoids; a workflow which the application believes it started and which the BPMS never saw is
the other.

The outbox dispatches at-least-once, so everything phase two does has to be repeatable. Where an
operation can be identified, `PhaseOperation` gives it an idempotency key, and where it cannot
(a broadcast signal has nothing to deduplicate on, and `AGGREGATE_CHANGED` reads its values at
dispatch time) the entry carries none and the residual duplicate is documented rather than hidden.
A test which called VanillaBP has to wait for the BPMS to catch up instead of reading its state in
the next line.

What such a key answers is decided by entry 22, which supersedes this paragraph in that one
respect: a key deduplicates an operation which is still planned, not one which already reached the
BPMS. Everything else here stands.

### 3. Phase one asks, phase two acts

The part of an operation which runs in the caller's transaction only ASKS: does the parked task
still exist, is a subscription waiting for this message, is such a message declared in a deployed
model. It never advances anything. That keeps a guiding error synchronous, thrown where the
application made the call and where a stack trace still points at business code, while everything
which changes the BPMS waits for the commit and is retried by the outbox.

An adapter answers phase one as exactly as its BPMS allows. An embedded engine answers from the
caller's transaction for free; a remote BPMS answers what its API can answer without a round trip
which would be wrong anyway, and says in its own README where it stays silent.

### 4. An adapter answers the election only for its own scope

Locating the BPMS which holds a workflow is a walk over the prioritized adapters, and the walk
stops at the first `ACTIVE`. It is therefore exactly as right as the answers it gets, which is why
the duty sits with the adapter and is written down in the election contract of
`MigratableProcessService`: an adapter answers `ACTIVE` only for a workflow deployed under ITS
scope, and `UNKNOWN_TO_BPMS` for everything else. Neither a task key nor an aggregate ID is proof,
because two adapter ids may address one backend (that is the migration from tenants to prefixes)
and two workflow modules of one backend may carry the same aggregate ID. `WorkflowScope` is what
the core hands the probes so they can tell.

`BPMS_UNAVAILABLE` never falls through to the next adapter: the unavailable BPMS is the one most
likely to hold the workflow, so the walk fails by name. Phase one fails at once, and the dispatch
and a read retry briefly first (decision 27). There is no
fallback in this walk at all, because a fallback means operating on a workflow which belongs to
somebody else.

### 5. What the election remembered is a hint, never an answer

`WorkflowAdapterCache` shortcuts the walk with the adapter which answered last time, and the same
association is written down whenever VanillaBP knows it for certain: when a start is scheduled,
after phase two, and on every inbound delivery. A hit is still probed, never trusted, and a stale
hit repairs itself by falling through to the full walk.

The hint also turns the meaning of an unknown answer around. An adapter which SHOULD hold the
workflow and does not report it is a reason to wait out that adapter's
`workflowVisibilityDelay` and look again, because an eventually consistent BPMS needs a moment
after the start. The same answer without a hint is a workflow nobody ever heard of, and fails
immediately.

A hint sits under the BPMN process id of the process which was running when VanillaBP learned the
answer, and that is not always the id the application addresses. A task of a secondary process is
delivered to the instance of THAT process, while every `ProcessService` call reaches the primary
one. The write stays where it is, because it names the process which really ran and because nothing
then has to be migrated. The READ asks for every id the workflow service serves, the reading
instance's own id first. All of those processes belong to one workflow aggregate, so a hint written
under any of them says something about the same workflow, and the own id goes first because a walk
which just elected wrote its answer there. A hint which turns out to be stale is dropped, and one
whose workflow ended is marked, under the id it was read from: writing that under the own id would
leave the entry which will be read again next time saying the old thing.

### 6. A processed delivery is written down, and a redelivery is answered from the record

A BPMS which delivers at-least-once will deliver a task twice, and the second delivery must not
run the `@WorkflowTask` method again. The record is written in the SAME transaction as the change
to the aggregate, so a handler which rolls back leaves no record and runs again on the next
delivery, which is the behaviour a rollback promises. The record carries the outcome including a
BPMN error code, so the redelivery can be answered without the handler.

Two timestamps, not one: `RECORDED_AT` is the moment the handler ran and is what the age of an open
task is measured from, `LAST_SEEN_AT` is refreshed by every redelivery of a still open task and is
what the retention deletes by. Without the second one the record of a task which the BPMS keeps
delivering would expire under the retention while the task is still open, and the next delivery
would run the handler a second time.

### 7. An adapter setting is resolved from the most specific place which sets it

Anything an adapter can be told about a single task is resolvable at four levels, task before
workflow before workflow module before adapter, and the most specific value wins. That is one
resolution rule in `MigrationAdapterProperties.resolveForAdapter`, not one per property, which is
why a new adapter-specific key costs a key and nothing else.

Precedence between levels is not the same question as precedence between sources. A workflow
module's own configuration file supplies DEFAULTS which the application always outranks, while a
more specific KEY still beats a less specific one no matter which file either came from.

### 8. What a convention can derive is not configured, and what is wrong is said at startup

An application configures what deviates from the convention. The adapter section of the single
adapter type on the classpath, the workflow module sections, and the location its BPMN files are
read from are all derived in `MigrationAdapterProperties.normalize(ClasspathFacts)` before
validation runs, so a zero-configuration application boots.

Everything which is nevertheless wrong is reported when the application boots, never first when a
workflow runs, and every message names the property key the reader would write, with the values it
found. An unconfigured application still starts and is led to a working setup by its own log
rather than by the documentation.

### 9. Identifiers are scoped at the BPMS boundary and nowhere else

A workflow module keeps its identifiers apart from those of other modules either by a namespace of
the BPMS or by a prefix, and which of the two is the module's configuration. So the registries of
the core, the `ProcessService`, the BPMN files and the configuration all stay keyed by the PLAIN
identifiers, only the call into the BPMS carries the scoped ones, and everything coming back is
translated before the core sees it. `NameClashAvoidanceService` is the one place which resolves
the mode and builds the scoped form.

No code may assume either shape, and an adapter which cannot separate modules at all says so at
deployment time instead of deploying two modules on top of each other.

### 10. The sync model decides what leaves for the BPMS, and only that

`@SyncWithBPMS` and `@NoSyncWithBPMS` on an aggregate class, on an attribute or on a nested type
decide which of its values a command carries, and `AggregateSyncSupport` is the single
implementation of that model, including the inheritance along that chain and the validation which
runs at startup. Nothing else is added: a correlated message carries no content of its own, and a
value excluded from the model stays out of every command.

The reason the model exists at all is portability. A value which a BPMS expression reads has to be
in the payload on a remote BPMS, so an application which relies on the engine reading its aggregate
live works on one BPMS and silently takes the wrong branch on the next.

Beside the shared values travels the variable named after the aggregate's ID attribute, and that
one is written no matter what the model says, because on a BPMS without a business key it is the
only way back from a process instance to the workflow.

### 11. When VanillaBP calls the application, a transaction for that aggregate is open

Loading an aggregate, running a handler and saving it are three calls which the application's
persistence cannot bracket by itself, and the outbox and the delivery log demand to be enlisted in
the same unit of work as the aggregate. So VanillaBP opens one, and it opens the RIGHT one:
`TransactionRunnerResolver` resolves per aggregate, taking the most specific
`TransactionRunnerAware` bean, then a `TransactionRunner` bean of the application, then the
platform default. A tie between two equally specific beans ends the start with both bean names.

Because it is resolved per aggregate, an application whose storage brings its own transactions can
supply one, which is what makes a deployment without a relational database possible. Whether a
transaction is running is asked of that same runner, never of the platform, or an application with
its own storage would be refused a `startWorkflow` it can perfectly well do.

### 12. A failure of phase two is classified by the adapter and judged by the core

Only the adapter can read its BPMS's errors, and only the core knows what to do with the verdict.
So `MigratableProcessService.isPhaseTwoFailureRepeatable` answers one question, defaulting to
repeatable, and `MigrationProcessService.runPhaseTwo` turns a `false` into a
`PhaseTwoPermanentFailure` which every store blocks the entry on immediately instead of retrying
it as often as `vanillabp.outbox.block-after-attempts` allows, fifty times by default. The same cut
runs through the concurrent-token check and the transaction annotations: the adapter or the platform
integration reports facts, the core decides.

Repeating a failure which will never succeed ends in a blocked entry either way, and every attempt
on the way there writes a log line nobody learns anything from, so the classification errs towards
repeatable and each adapter's README lists what it calls permanent and why.

### 13. A delivery whose version the BPMS did not report is served only by an unrestricted method

*Superseded by decision 20: a range may now reach a method from the `@BpmnProcess` of its class, and this entry names the method annotations only.*

`@WorkflowTask(version = ...)` and its two siblings match against the version of the DEPLOYED
process definition as the BPMS counts it, or against a version tag of the model. When a delivery
arrives without a version, only a method without a version range serves it, and if every method of
that task names a version the delivery fails with a guiding message.

The rule replaced an assurance that the first registered method wins, which rested on the order
`Class.getDeclaredMethods()` happens to return and which the language does not define. Version
ranges of one task must not overlap either, which is checked at startup, so a delivery is never
served by an arbitrary one of two candidates.

### 14. Two writers on one aggregate are made visible, not resolved

As soon as a process holds more than one token, two branches write the same aggregate, and a
persistence layer which writes the whole record lets the later commit undo what the earlier one
wrote. VanillaBP does not resolve that. It reports the version conflict with workflow module,
process, aggregate and task, and propagates the exception unchanged so the BPMS runs its own
retries.

There is deliberately no retry of our own. A handler may have called a remote API before its commit
failed, so repeating it silently would repeat that call and hide the conflict at the same time.
What VanillaBP does instead is warn once per process when the model can produce a second token and
the aggregate class has no version attribute, because there the conflict would not even be
detected.

### 15. The check of the versions a BPMS still holds is driven by the core

A BPMS keeps every version of a process it ever deployed, and instances keep running on the old
ones. Whether the application still serves them is a judgement with a message text, a policy and an
outfading configuration attached, so it lives in the core; an adapter only answers two questions on
`ProcessVersionCatalog` and reports through `registerDeployedVersion` which version THIS boot
deployed, which is the border between the application's own model and the older ones.

Building the same judgement in every adapter would give every BPMS its own wording and its own
gaps.

Which process ids are asked about is the core's question as well. A workflow module may declare a
process id no BPMN file of this boot carries any more, which is how a renamed process keeps being
served, and an adapter cannot arrive at that id on its own: it comes from the annotations of the
application, which only the core reads, and the models of this deployment name themselves and not
the id somebody renamed away from. What the BPMS still holds under such an id an adapter can read
perfectly well once it has been told the id. So the core asks every adapter of the module for the
catalog of such a declared-only id through `processVersionCatalogOf`, whose default answer is
nothing, and an adapter whose BPMS cannot be searched by process id stays as it is.

That is also why a `deployedVersion` of null carries two meanings now. Where the module deployed
the process, no version reported by the BPMS means there is no older one to speak of; where the id
was only declared, every version the BPMS holds under it is an older one, and the ordinary reports
run over all of them. Only the core can tell the two apart, because only it knows what the
application declared.

### 16. The four tables VanillaBP owns come from one schema artifact

The phase-two outbox, the payloads of its calls, the task delivery log and the claim of the
housekeeping are ours, so `vanillabp-schema` ships one database-neutral Liquibase changelog for them plus the SQL generated from it per database, and the
runtime DDL creates the same columns. Nothing else is shipped: gruelbox's table belongs to
gruelbox and the engine tables belong to the engine, both documented rather than copied.

Where the application creates the schema itself, the stores check at startup that their tables
exist, and name the table, the property and the artifact to apply. The outbox and the delivery log
check the columns as well. Checking only the
table is what let a later column slip through once.

*The title said "two tables" when the outbox and the delivery log were all there was. The payload
table came with decision 62 and the housekeeping table with decision 91, and the count was corrected
on 2026-10-06. What was decided did not change.*

### 17. An adapter id is an identity and is never renamed while anything is still open

Every persisted record which belongs to a workflow carries the adapter id it was written for: the
outbox entry so a pending call reaches the BPMS it was planned for, the delivery record so a
redelivery is recognised as the same delivery. Renaming an id therefore orphans them, and the
symptom shows up much later as a workflow which was persisted and never started, or as a handler
which runs a second time.

So the ids the stores still hold open work for are asked once at startup and compared with the
configured ones, and a mismatch warns with both readings and with the property which acknowledges
it. A warning, not a failed boot, because the entries are waiting rather than lost. A store which
cannot answer at startup returns nothing, and the same warning then comes at the first dispatch of
one of its entries (decision 47).

### 18. Reading a metric costs no more than reading a number

A gauge is read on every scrape, in every instance, so a gauge which counts rows in a table turns
monitoring into load. Every measurement which is not already in memory goes through
`CachedGaugeValue`, whose window is `vanillabp.metrics.gauge-cache`, and the wrapping happens once
in `MicrometerVanillaBpMetrics` so no store can forget it.

A store which cannot answer at all leaves a gap rather than reporting zero, because zero pending
calls is a statement somebody will act on. The same reasoning fixes the naming: the prefix is
`vanillabp.`, the place is a TAG and never part of the name, and nothing which is unique per
workflow ever becomes a tag, or every workflow would get its own time series.

### 19. A start asks for numbers, and asks as many questions on the last day as on the first

Booting an application puts questions to the BPMS and to the tables VanillaBP owns: which
versions the BPMS holds, how many workflows still run on an older one, how many tasks it is
holding open, which adapter ids the persisted state still names. Every one of them is answered
from data which keeps growing for as long as the application is in production, so every one of
them can turn a ten-second start into a two-minute one after two years, and nobody sees it coming.

The first half of the rule is that a start asks for a number, or for the existence of one row, and
never for the rows themselves. `COUNT(*)`, `DISTINCT`, a search which reads its `totalItems`, a
statement carrying a row limit: the reducing is the database's work or the cluster's. Reading the
first row of an unlimited result set is not that, however much it looks like it in the code, because
a JDBC driver decides for itself how much it transfers before `next()` answers and PostgreSQL's
reads everything.

The second half is that how many questions get asked belongs to the shape of the application rather
than to its history. Once per workflow module, per BPMN process, per adapter and per version a BPMS
holds is fine, since those numbers change when somebody deploys, and `outfaded-versions` is what an
operator bounds the last of them with. Once per running workflow, per open task or per record is
not, because nobody deploys to make that number grow.

Where a question cannot be asked that way it is switched off or made conditional on something an
operator understands, never on a time limit. A check which sometimes runs is worse than no check,
because its silence stops meaning anything.

The guard counts the questions instead of measuring the duration, which would only flicker on a
build runner. `StartupQuestionCostTest`, `OldProcessVersionsTest` and `PersistedAdapterIdTest` ask
the same start twice, once against a fresh installation and once against years of history, and
compare what was asked.

### 20. A version range belongs to a method or to a whole workflow service class

`@BpmnProcess(version = ...)` is the fallback of `@WorkflowTask`, `@WorkflowStartedByBpms` and
`@WorkflowEnded`. A method naming no range serves the range of the `@BpmnProcess` its process was
declared with; a method naming one keeps it word by word. This replaces decision 13, which promised
the same thing about the method annotations alone.

An application which brings two generations of a model had to repeat the range on every single
method, which is one statement written many times, and a method added later without the attribute
silently served every version. What a team wants there is one handler class per generation, and the
attribute which says that has sat on `@BpmnProcess` since version 1 with nothing reading it.

The method wins and the two ranges are not intersected. With an intersection, `version = "5-7"`
would mean different things depending on a declaration elsewhere in the file, and a range which
cannot be read off the annotation in front of you is worse than one repeated. Which declaration
applies is decided by the PROCESS a delivery came from, not by the class: a class declares one
`bpmnProcess` plus any number of `secondaryBpmnProcesses`, each with a version of its own, and one
method may serve elements of both. So the range is resolved per (class, BPMN process), which is how
handler methods are registered anyway.

A method which inherits a range is restricted, so it does not serve a delivery whose version the
BPMS did not report, exactly like a method naming a range itself. That is the consistent reading and
also the surprising one, since the method carries no attribute at all, which is why every message
about such a range names the declaration it came from. A complaint about something the reader cannot
see next to the method reads as a defect of VanillaBP.

Two classes for one process are the point of the feature and boot as long as their ranges are
disjoint; overlapping ones end the start naming both classes. The ranges compared are the EFFECTIVE
ones. Untouched by all of this: one `ProcessService` per workflow aggregate. Which process
`startWorkflow` starts is decided by the primary declaration, and different primary processes for
one aggregate remain ambiguous.

### 21. A workflow service is found because it is a bean

Spring Boot discovers the classes annotated by `@WorkflowService` among the bean definitions of
the application, not by scanning the classpath for them. The scan it replaces read every class
resource of every JAR, 42 816 of them in the demo it was measured on, to find a single workflow
service, and it cost 15.9 seconds of a 24.4 second start under `spring-boot:run` and 4.3 of 9.0
from the packaged JAR.

Being a bean is what a workflow service has to be anyway: the handler object of a task delivery is
resolved through the bean factory, so a class without a bean cannot serve a task no matter how it
was found. So the discovery asks nothing of an application it did not already have to bring, and
where the class sits stops mattering, which is the whole reason the scan existed.

The bean definitions are also the only place where the question has a correct answer. Whether a
service belongs to THIS run is decided by the active profile and by every other condition Spring
evaluates while it refreshes, so a class list read from the classpath, or written into an index
while the application was built, answers a different question: what could be a bean in some run.
An index the way the Quarkus integration uses Jandex cannot close that gap either, because Quarkus
decides its bean set while the application is built and Spring has no such closed world.

Scoping the scan instead of dropping it was measured and rejected. Both candidates, the packages
of `AutoConfigurationPackages` and the classpath roots carrying a `META-INF/workflow-module`
marker, lose the workflow services of a common library, which live in a root with neither, and
which the global workflow module picks up today.

The discovery runs as a `BeanDefinitionRegistryPostProcessor` ordered last rather than as an
imported `BeanRegistrar`, because a registrar runs while the configuration classes are being read
and would see only the definitions registered up to that point. A library's auto-configuration
contributes later, and that is the case this exists for.

A class carrying the annotation without being a bean is passed over by the discovery. It cannot be
told apart from a class another profile brings, and the application which really lost its handlers
is told so further down, by the report the deployment writes about the BPMN processes no workflow
service of this run claims. Where a process stays unclaimed, that report reads the class resources
once and names such a class with both readings (`WorkflowServicesWhichAreNoBeans`); a healthy boot
scans nothing. It names the process, what a workflow of it costs (it can be started
and gets no further than its first task) and the two ways out, writing the workflow service or
taking the process out of the file. What this gives up is the loudest symptom a forgotten workflow
service used to have: it does not end the boot any more, and whoever does not read the startup log
does not learn about it.

*The last paragraph is superseded by decision 120: a deployed process nobody claims
ends the boot again, unless the application marks it with
`...workflows.<process>.implemented-externally=true`. The report naming the class which is no bean
is part of that refusal now. The discovery itself is unchanged.*

### 22. An idempotency key says an operation is planned once, not that it ever happened

The key of a phase-two operation deduplicates against the entries which have not been dispatched
yet. What it protects is the window entry 2 opened: between the application's commit and the BPMS
command, where a crash has to leave the operation repeatable and a redelivery must not run it
twice. It stops answering a question nobody asked it, whether this operation ever happened before.

Before this, a dispatched entry kept blocking its key until the retention deleted it, seven days
by default. A second, entirely legitimate operation of the same key inside that window was
dropped: an offer round asking partner 42 again in the second round correlates the same message
name with the same correlation id for the same aggregate, and the workflow then waits for a message
which was never sent. The retention was never the screw to turn - shortening it makes the
collision rarer, not impossible - so the window ends where the reason for it ends, at the dispatch.

The stores carry that in the column or field the unique constraint spans: it holds the idempotency
key while the entry waits and the entry's own ID once the entry was dispatched, so the constraint
covers exactly the operations which are still planned and the derived key stays readable next to
it for whoever reads the table during support. It is never null, because not every database treats
two nulls as different values. gruelbox owns its table and has no such column, so that store
releases a dispatched entry when it meets one: the row has done its work and is deleted, in the
caller's transaction, which costs its trail and is the price of not owning the table.

The at-least-once guarantee moves nowhere: a redispatch reads the very entry which is not done
yet, so it is the store's own bookkeeping which carries it - gruelbox' attempt count, the
`STATUS`/`ATTEMPTS` columns of the stores VanillaBP wrote itself - and never the key. Which is why
the key is bounded to the smallest limit of the four stores, 250 characters, and hashed beyond it:
gruelbox refuses a longer unique request ID before any database sees it, and an aggregate ID a
domain model legitimately uses reaches that length.

A key names the operation it deduplicates, because completing a task and cancelling it are two
pieces of work on one task ID. The one deliberate exception is a workflow start by message, which
carries the plain start's key: a workflow is started at most once per aggregate, whichever of the
two started it.

What this does not fix is two operations planned in the same batch of work. Multi-instance
siblings of one aggregate share workflow module, BPMN process and aggregate ID - a called process
is a secondary workflow of the SAME aggregate - so three elements of a multi-instance call
activity are told apart by their correlation id alone, and business data does not have to differ.
All three are pending at the same moment, the first one wins, and the others are discarded. Telling
a sibling from a redelivery needs to know which activation asked, which nothing on the outbound
side reports today. So the discard is made audible instead of silent: the store logs the technical
half at DEBUG, and the core turns the outbox' answer into a WARN naming what was dropped and both
causes it cannot tell apart. The remedy the message asks for is the one which exists: vary the
correlation id per round or element.

Which activation planned a key is decided by entry 23, which supersedes this paragraph in that one
respect: siblings are told apart now, and the remedy above is what remains for a caller repeating
itself within one activation or outside any. Everything else here stands.

On Camunda 8 the cluster deduplicates as well, by the `messageId` the adapter derives from the same
values, for as long as the message time-to-live lasts. That net is the cluster's, it is longer than
this one, and no VanillaBP setting shortens it - which is why the adapter's message says so instead
of calling the refusal a redelivery.

### 23. A key names the activation which planned it

The idempotency key of a message correlation carries the activation of the BPMN element the
correlation was planned in, where there is one. It is the only key which does, and the reason is
that it is the only one which has to deduplicate PER activation.

What entry 22 left open was two operations planned in the same batch of work. A called process is a
secondary workflow of the SAME aggregate, so three elements of a multi-instance call activity agree
in workflow module, BPMN process and aggregate id, and a correlation id read from business data does
not have to differ either. All three were pending at the same moment, the first one won, and the
other two were dropped. Telling a sibling from a redelivery needs to know which activation asked,
and the BPMS knows: it is running one element instance per element.

So the adapter reports it, as the identity of the ACTIVATION rather than of the delivery. The two
contracts are opposite and were confused because one BPMS answers one value for both: a delivery
identity has to stay EQUAL while the BPMS repeats itself, so a redelivery can be answered from its
record, while an activation identity has to DIFFER between two activations of one element and says
nothing about redeliveries. Camunda 7 is the proof that they are two questions - it reports no
delivery id at all, because it delivers inside its own transaction, and still knows which activity
instance is executing.

The core reads it from the thread it invoked the handler on, so the application passes nothing and
its own signatures stay as they are. A task delivery opens that scope and so does a workflow the
BPMS started; the end of a workflow does not, because a workflow ends once. A handler which hands
work to a thread of its own sees no activation and gets the key every VanillaBP application had
before - absent rather than failing, so nothing which works today breaks on the upgrade.

The other keys deliberately do NOT carry it. A workflow is started at most once per aggregate,
whichever activation asks; a task is completed at most once, and its task id already names one
activation of one element; a broadcast signal and a correlation without a correlation id carry no
key at all, and giving them one would start deduplicating what is deliberately not deduplicated.

What this does not fix is a correlation planned outside any activation. A REST endpoint correlating
the same message name with the same correlation id twice for one aggregate is indistinguishable
from a repeat of itself, and the narrowed window of entry 22 stays the only answer there. The
warning says which of the two cases it is looking at, because the remedy differs.

### 24. What a number decides is what decides whether it gets a property of its own

The retention of a dispatched outbox entry and the retention of a task-delivery record are two
properties, `vanillabp.outbox.retention` and `vanillabp.delivery.retention`, because they answer two
different kinds of question. One is operational: how long can support still read what was
dispatched. The other is correctness: does a redelivery arriving later than this run the
`@WorkflowTask` method a second time. Nobody weighing disk space against a support trail should be
weighing a business method running twice at the same time.

They were one property, and rightly so, for as long as both governed a deduplication window. Entry
22 ended that: the outbound window ends with the dispatch, so on that side the number stopped
deciding anything a workflow depends on, while on the inbound side it went on being the only thing
between a late redelivery and business code running again. An installation shortening the number to
keep its outbox table small was shortening a correctness window with the same hand, and had no way
of seeing it.

The new property FOLLOWS the old one where it is not set. That keeps every installation on the
behaviour it had, including the ones which lowered the old number deliberately, and it means an
application which never cared about either notices nothing. Where exactly one of the two is moved
away from the default, the startup says which window applies to what - the trigger is "differs from
the default" rather than "was written down", because a bound property cannot tell those apart and
the case worth a message is the one where somebody moved a number.

It is read GLOBALLY, unlike the settings next to it in the same section. What deletes the records is
one cleanup per store, constructed with one period and deleting by age across the whole table
respectively collection, so a value per workflow module would have to be honored by a different
deletion in each of the four stores VanillaBP ships. A property which is bound per module and
silently ignored there is worse than not having one.

**And there is deliberately no check comparing it against what a BPMS can redeliver within.** That
was the alternative to splitting, and it fails on what an adapter can truthfully answer: it knows
the INTERVAL at which it hands unacknowledged work out again - the Camunda 8
`async-task-lock-renewal`, an hour by default - and not the horizon within which the last such
handout falls. The horizon is set by how long the application is stopped, because a stopped
application refreshes no record and the first cleanup run after it starts deletes what expired
meanwhile, and by whoever gets around to resolving an incident. A check against an interval of
minutes, guarding a risk measured in days, would be green in exactly the installations about to run
business code twice, and a green message which means nothing teaches its reader to ignore the next
one.

### 25. A workflow is located by asking, not by a registry

Every operation on an existing workflow asks the configured adapters which of them holds it,
and the answer is not written down anywhere persistent. A registry mapping workflow module,
BPMN process and aggregate id to an adapter id was considered and rejected: it would be a
second source of truth about something the BPMS already knows, it would need a schema, a
cleanup and a repair path of its own, and it would be wrong exactly when it matters, after a
crash or a migration.

What that costs is one question per operation, which is a query against a remote BPMS and
nothing at all against an embedded one, plus the walk over the adapters until one answers.
The first accelerator was `WorkflowAdapterCache`, whose entries are hints and never answers
(entry 5), so a lost hint costs a walk and never a wrong route. Two more came later, and both are
records of something that happened rather than a registry: the delivery record of a task (decision
30) and the start row of a workflow (decision 112).

The workload this is sized for is the one VanillaBP is built for, and that is a product
decision rather than a limit somebody forgot to lift. It is built for the business processes
of a normal company, fast or slow: a workflow may finish within seconds or wait for weeks.
What matters is not how quickly one workflow runs, but how many operations all of them add up
to, and VanillaBP is not built for thousands per second. An
application which really moves thousands of operations per second has to be optimised
anyway, and a project of that kind carries the budget to build what it needs; VanillaBP
buying that case with complexity everybody else pays for would be the wrong trade. So the
probe per operation stays, and an application in that other shape gets a design of its own
rather than a registry bolted on here.

Where the cost does show up first is a cluster of application nodes, and the lever there is
the cache rather than the design: a shared `WorkflowAdapterCache` (Redis, Hazelcast, whatever
the application already runs) is written once when the workflow starts and read by every
node, so the BPMS is asked once per workflow instead of once per node. That is the
recommended answer to "the election is our bottleneck", and it is the reason the cache SPI
is part of the integration SPI rather than an internal class.

Two consequences the code carries visibly. The re-dispatch of a workflow start reads the start
row first (decision 112) and probes `awarenessOfWorkflowForRedispatch` only where the row says
nothing, which is why that probe must never answer optimistically, and the residual duplicate window after a crash is
documented per adapter rather than closed. And an adapter which cannot be asked at all
answers optimistically, which is safe while it is the only BPMS configured and is the reason
a migration setup containing such an adapter routes by list order (see the wiki page
"BPMS migration").

### 26. There is no switch which lets an adapter act in phase one

`MigratableProcessService` used to ask every adapter whether it needs a two-phase commit for
starting workflows, and the core skipped the outbox for six operations where the answer was
`false`: the start, the start by message, a task completion, a cancellation, a correlated
message, a broadcast signal and a pushed aggregate. The method was named after starts, gated
much more than starts, and described a variant which acts in phase one and leaves phase two
empty. No adapter ever answered `false`, Camunda 7 deliberately so although it runs embedded
(decision 2 of that adapter's log: a command which loses a concurrency conflict cannot be
repeated inside the caller's transaction, because the conflict leaves that transaction
rollback-only). What the switch really offered was a way to build an adapter the core does
not run, and the next adapter is written by a team we are not sitting next to.

So the method is gone rather than renamed, and entry 3 holds without exception: phase one
asks, phase two acts, for every adapter and every operation. Two things follow from it which
were conditional before and are unconditional now. Every application needs a resolvable
`PhaseTwoOutbox`, and needs it at startup, because there is no adapter left which sends
nothing through it. And every application needs a transaction VanillaBP can run its work in,
because the aggregate and the outbox entry are written together or not at all.

The embedded fast path is not forbidden forever; it is simply not part of this SPI. Bringing
it back is a new decision here, with the core change which makes the core skip the outbox in
exactly the cases the adapter names, and it will not come back as a sentence in a javadoc
with no code behind it.

What the removal does NOT touch is the inbound direction. A BPMS which delivers a task inside
its own transaction still does, and `TaskInvocationContext.runInCurrentTransaction()` is where
an adapter says so.

### 27. Phase one asks once, and the waiting happens where no transaction is open

Locating the BPMS which holds a workflow is a question per operation (entry 25), and until now
that question was allowed to take its time wherever it was asked: an unreachable BPMS was retried
twice half a second apart, and an adapter which a hint said should hold the workflow was asked
again until its `workflowVisibilityDelay` was used up, ten seconds on Camunda 8. In phase one that
runs inside the transaction the application called from, which holds a database connection and
the locks on the workflow aggregate. One lagging exporter is then enough to park every caller in
the connection pool, and an application whose BPMS is slow stops being able to do anything at all,
including the work which has nothing to do with that BPMS.

So the walk asks how patient it may be. Phase one asks every adapter once and never sleeps. The
dispatch of a phase-two entry may ask an unreachable BPMS again, because no application transaction
is open there and a repetition costs an entry another attempt rather than a connection. It does not
wait for a read model which is behind: it gives the entry back with a due time (decision 49).

Three goals decide what happens to the answers, and they are Stephan's, written down here because
they are the reason the table below looks like it does:

1. a workflow NO BPMS knows has to raise `WorkflowNotFoundException` out of the `ProcessService`
   method the application called;
2. the one to three seconds Camunda 8's secondary storage lags behind in normal operation must not
   produce an error anywhere, while an exporter which stopped (a full Elasticsearch, say) should
   end up in a Camunda incident;
3. a BPMS which is not available right now produces an exception - and asking it is the only
   honest way to find out.

|                  The probe answers                  |                phase one does                |             the dispatch does              |                      a read does                       |
|-----------------------------------------------------|----------------------------------------------|--------------------------------------------|--------------------------------------------------------|
| `ACTIVE`                                            | the operation runs                           | the operation runs                         | the adapter answers                                    |
| `COMPLETED`                                         | warned no-op                                 | the entry is consumed                      | the adapter answers (an ended workflow is viewable)    |
| `UNKNOWN_TO_BPMS`, task operation                   | `TaskNotFoundException` at once              | the entry is consumed (the task is gone)   | -                                                      |
| `UNKNOWN_TO_BPMS`, workflow operation, hint present | the operation is planned, the caller returns | gives the entry back, due after the window | waits out the window, then `WorkflowNotFoundException` |
| `UNKNOWN_TO_BPMS`, no hint                          | `WorkflowNotFoundException` at once          | the entry is consumed (stale)              | `WorkflowNotFoundException` at once                    |
| `BPMS_UNAVAILABLE`                                  | exception naming the adapter, at once        | retried twice, then the entry is repeated  | retried twice, then the exception                      |

The read is the column this decision first forgot, and a red blueprint nightly is what
said so (story 176): the viewer of a workflow started seconds ago asked Camunda 8 while
its exporter was still behind, got the honest "unknown" and raised
`WorkflowNotFoundException` in the application, which goal 2 above forbids. Where a write
operation is planned in phase one and does its waiting in the dispatch, a read has no
second place to go: nothing repeats it, so it either waits itself or hands the caller an
error. It therefore waits like the dispatch does, bounded by a hint and by the adapter's
`workflowVisibilityDelay`. What that costs is the mirror image again: a read carrying a
stale hint answers after the window instead of at once, and the message then names the
adapter which was expected to hold the workflow, because an exporter which stopped looks
exactly like one which is behind.

What makes the hint the dividing line: a hint exists only where VanillaBP knew the answer without
asking anybody - it started the workflow itself, or a delivery for that workflow arrived from that
BPMS. It is therefore evidence that the workflow exists, which turns "unknown" from a wrong id
into a read model running behind. Without a hint and with a unanimous "unknown", nobody has ever
seen this workflow.

A task probe is not a search, which is why it keeps the fast answer. On Camunda 8 `awarenessOfTask`
updates the job's timeout and `awarenessOfUserTask` sends an empty user-task update; both are
engine commands which answer exactly and never touch the exporter. Only `awarenessOfWorkflow` -
message correlation, the aggregate push, the viewer, the re-dispatch probe - searches the
secondary storage, and only that branch needed a new answer.

Where an exporter outage becomes visible depends on what the work hangs on, and that boundary is
part of this decision rather than something to discover later. Work behind a JOB - a
`@WorkflowTask`, an asynchronous task whose completion never arrives - runs out of the job's
retries in the cluster and Camunda raises an incident, which is what goal 2 asks for. Work with no
job behind it, a correlation from a REST endpoint for instance, has nothing which could become an
incident: there the blocked outbox entry and the counter of blocked entries are the place it shows.

Goal 3 cannot be answered without asking. What is available without a question is the adapter's
last known health, and that is stale by construction - "was reachable n seconds ago" is either a
false alarm or a false calm. The probe itself is where unreachability surfaces, and it is one
question per operation, the price entry 25 accepts. Health may enrich the MESSAGE ("this adapter
has been reporting itself unreachable since 12:04"); it does not replace the question.

The residual this leaves is the mirror image of the old one and is named rather than closed: a
workflow operation carrying a stale hint - the workflow ended long ago and is out of the read
model - is planned instead of refused at the call. Its entry is repeated and finally blocked. It
used to be an exception after ten seconds of waiting; it is a blocked entry now, and the more
common case of an ended workflow which the BPMS still knows keeps answering `COMPLETED` and stays
a warned no-op.

### 28. An adapter is registered completely or it does not exist

The collaborators an adapter needs from the platform used to arrive one setter call at a time,
after the constructor had already returned a usable object. A registrar which forgot one produced
an adapter that deployed its BPMN files, ran its tasks and never reported a workflow end, and
nothing anywhere failed: the object was valid, its field was simply null. There was also no place
which said what a complete registration is - the list lived in whoever had written the last
registrar, and the three adapters had drifted apart in exactly the way that invites.

So the platform hands its collaborators over in one object, `AdapterCollaborators`, and an adapter
takes it in its constructor. Five of them are mandatory, because both platform integrations
provide them for every application: the wiring half and the runtime half of the task SPI, the
name-clash scoping, the aggregate sync and the pre-commit registrar. A set built without one of
them throws, naming the adapter id and what is missing, and says that no application can configure
this away - it is the registration code.

Two are handed over as `Optional`: the invoker for workflows which ended, and the one for
workflows the BPMS started by itself. An adapter has to work without them, because an application
which never asks for either has nothing to report to. Both platforms do provide them today,
though, out of the same core bean as the mandatory ones - so an adapter built without one is
nearly always a registration which left it out, and the build writes a WARN naming the adapter id
and the collaborator. That is the second line: the first one is a compile error the day a
registrar is written, the second one is a line in the log of the boot which caused it.

What is NOT in the object: what an adapter resolves from its own configuration (a job timeout, a
retry backoff, the variables a worker fetches) and what its own extension contributes (its
metrics). Those are the adapter's own arguments, and the platform has nothing to say about them.

The alternative was a check at `startWorkflowProcessing` asking each adapter which collaborators
had arrived. It is the smaller change and it would have caught the same defect, one boot later.
The parameter object was chosen because it changes every adapter's constructor, and the moment to
do that is before an adapter written outside this repository exists (Stephan, 2026-08-28).

### 29. An operation says everything about itself, an adapter says what it does

One two-phase operation used to be written down five times in the core: a pair of methods on the
adapter SPI, a constant carrying its persisted name and idempotency-key rule, a typed `schedule*`
default building its outbox call, a registration in the router, and an `execute*` body in the
process service which differed from its neighbour in a probe and a log line. An adapter wrote it
twice more. Four of those places knew nothing an operation needed to decide - they repeated what
the operation already was - and the escalation stories waiting in the roadmap would have added a
sixth.

An operation is therefore one `PhaseOperation`: its name, its key rule, the `Election` naming
which BPMS serves it, whether every adapter has to be able to serve it, whether the activation the
call was planned in travels with it, and the words it names itself with in a message a developer
reads. The core reads the probe, the patience of the election and the shape of the failure off the
election; the router dispatches by name; the process service runs everything through one `execute`
and one `executePhaseTwo`. Adding an operation is a constant here and a handler in each adapter
which can serve it, and a test adds one to prove it stays that way.

What an operation does NOT say is what it does, because that is not one answer: it is one per
BPMS. An adapter contributes a `PhaseOperationHandler` per operation - phase one asks, phase two
acts, which is entry 3 - and an operation missing from its map is an operation this BPMS has
nothing like. That is a legitimate answer for an operation which says so (a signal, a push into a
running instance) and a defect for the rest, so the boot refuses an adapter which cannot serve
what every adapter has to serve. The map is the statement and the boot reads it; asking the
adapter's class anything would be reflection, and reflection is a lie in a native image - a method
nobody registered looks like a method nobody wrote, and every adapter of a native application
would be refused. The first attempt did exactly that and the native build of this repository
caught it.

The reason to do it now rather than when the escalation stories arrive: the next adapter is built
by a vendor rather than by this team, so the shape they implement against has to be the one we
want to live with (Stephan, 2026-08-28). A compatibility bridge carried the three adapters this
repository ships across, one pull request each, and went with the pair of methods once they had
moved - so `phaseOperations()` is abstract and is the only way an adapter describes outbound work.

### 30. A task operation is routed by the record the delivery of that task wrote

Entry 25 buys the freedom to migrate a workflow between BPMS with one question per operation:
which adapter holds this? For a task that question was asked twice on Camunda 8 - once by the
election, as `newUpdateTimeoutCommand` against the job, and once again a moment later by the
adapter's own phase one, which sends the same command as its pre-commit check. Two round trips to
a cluster for something VanillaBP had already written down.

Because it had: the delivery record of that task names the adapter which delivered it and says the
task was left open. It lives in the database of the workflow aggregate, so it is read inside the
transaction the caller has open anyway, and every node of a cluster sees it - unlike the election
cache of entry 5, which is why a shared cache is not the answer for this one operation. What the
record could not say was WHICH task it belonged to, so it carries the task id now, and the moment
the application's completion of that task reached the BPMS.

This is not the registry entry 25 rejected, and the difference is the fallback rather than the
wording. Nothing is written for the sake of routing: the record exists, is written by the delivery
itself in the delivery's transaction, and is deleted by a retention which was there before. It
answers only about a task, never about a workflow. What decides whether it may answer is therefore
not how an operation is declared, but whether the call at hand names a task. `aggregateChanged`
addresses a workflow and names a task whenever the application passes a task id, and that call is
elected from the record like any other, while the overload without one walks the adapters as it
always did. A closed record is narrower still: it turns into the warned no-op only for the
operations which end the task it names, because a push writes into a scope that outlives the task
and the workflow may well run on. And where it says nothing - no store,
`deduplicate-deliveries` switched off, the retention passed, an upgrade whose open tasks predate
it - the walk runs exactly as it did. Camunda 7 used to be on this list, because it reported no
delivery; since decision 107 a delivery without an id is written down too. A registry which is wrong routes wrongly; this one
is either right or silent.

The moment a task was closed is written after phase two succeeded, on the dispatching thread, and
not when the caller asked. Between the two the task is still open for the BPMS, and on Camunda 8 it
is this very record which answers the redeliveries that renew the job's lock - marking it earlier
would let that lock expire. A second call inside that window is refused by the outbox' idempotency
key already (entry 22), and that key is free again once the entry was dispatched: from there on the
record is what makes a repeated completion the warned no-op it always was.

The record sits under the BPMN process id of the process which DELIVERED the task, and for a task a
called process handed out that is the secondary id, while the application completes it on the
primary process service. Reading the own id alone found nothing for exactly those tasks: the
operation paid the probe this entry exists to save, and the row `markTaskClosed` should have closed
stayed open, ageing and counting as an open task for good. So the read asks for every BPMN process
id the workflow service serves, the reading instance's own id first, and the ids a rename left
behind belong to that list because they are declared. The write moves nowhere. A record still says
which process handed the task out, which is what the delivery-log store keys on and what makes a
record written by an older version readable without migrating anything.

What this does not touch: phase two elects by probing, as it always did, so a workflow which
changed its BPMS between the call and the dispatch is still found. And the adapters keep their
phase one, so a task which disappeared between the delivery and the call still fails synchronously
where it always failed.

### 31. A cache VanillaBP ships lives in a repository of its own

The election cache is an interface an application implements for a reason which has nothing to do
with its domain: a second node starts with an empty cache, and everything that follows is
infrastructure work. That work is the same in every project, so VanillaBP ships one implementation
of it, for Hazelcast, in
[hazelcast-shared-election-cache](https://github.com/vanillabp/hazelcast-shared-election-cache)
with a release line of its own. The platform integrations stay free of any Hazelcast dependency.

Three things follow, and each of them is the reason for the split rather than a consequence of it.
The SPI stays the contract: an application whose infrastructure is Redis, a table of its own or
anything else is not a second-class case, and nothing about the shipped implementation may make it
one. A cache which needs a new Hazelcast version must not drag the platform's release with it,
which it would as soon as a platform module depended on it. And a member of a Hazelcast cluster is
a component with its own operational story, its own compatibility table and its own failure modes,
which belong next to the code that has them and not in the documentation of the platform.

What stays as it was is entry 25: a shared cache is the answer where the election becomes a
bottleneck, on Redis, Hazelcast or whatever the application already runs. That reasoning does not
change because one such cache now exists ready-made. No entry of this log ever promised that
VanillaBP ships none; the sentences which said so lived in the javadoc of `WorkflowAdapterCache`
and in the texts which followed it, and they now point at the implementation while still saying
that an application with different infrastructure writes its own.

### 32. The workflow service is the class which serves, not the class which declares

`@WorkflowService` is `@Inherited`, and what that means for VanillaBP is decided here, because four
places rely on one answer: the Spring Boot discovery, the Quarkus build step, the scanners of the
core and the reflective registration a native image is given.

The workflow service is the class which SERVES the BPMN process. A class inheriting the annotation
from a superclass is one of those, and the superclass is a declaration rather than a workflow
service of its own. Everything read per workflow service is read on the serving class: the archive
it sits in decides the workflow module, its simple name is the BPMN process id where the annotation
names none, and its handler methods are the ones it offers, the inherited ones among them.

Spring Boot arrived at that reading by itself, because it registers the class of the bean, and
version 1 did the same. Quarkus did not: Jandex reports the class an annotation SITS on and resolves
no `@Inherited`, so the declaration used to become the workflow service, and one source file served
two different BPMN processes depending on the platform it ran on. Quarkus is the platform which
moved, because VanillaBP on Quarkus exists from version 2 onwards, which was unreleased when this
was decided, so no application had been promised the other reading.

A class which is no bean serves nothing, so the workflow services are the beans. Spring Boot reads
that set from the bean definitions (entry 21). Quarkus cannot ask ArC while it is still generating
beans, so the build approximates the set by the classes of the family which are not abstract, and
each of them has to be a CDI bean of its own. A bean of a subclass does not stand in for a class
inheriting the declaration, because that subclass is a different workflow service, serving a process
of its own wherever the id follows the class name. This is the one point where Quarkus asks more
than Spring Boot does, and it is what keeps the set registered and the set required to be beans from
drifting apart. It is loud rather than silent: the build names the class, the declaration it
inherited and the ways out of it.

Where several classes of one workflow aggregate come out of that walk, nothing new happens. Classes
declaring the same process split the handlers of that process, and classes declaring different
primary processes end the build with the message which always ended it. Two subclasses of a base
naming no `bpmnProcess` are that second case, since each of them names its process after itself.

A declaration nobody can serve is reported instead of registered. An abstract class carrying the
annotation with no concrete subclass in the index has no class to hand a task to, and saying so is
better than the bean question the developer used to get about a class they made abstract on purpose.

### 33. The BPMS double is published, and the platform's own tests use the published one

An application which needs a BPMS to boot cannot be tested by anything which has no BPMS, so every
repository next to VanillaBP either starts a real engine or writes a double of its own. VanillaBP
ships that double instead, as `bpms-double` plus one module per platform, because the alternative
was for each such repository to depend on a real adapter and hope its engine starts quietly.

The point is not the artifact, it is which code goes into it. The platform's own tests run against
the published modules rather than against a copy kept in the test tree, and that is the whole
argument for publishing: a double nobody uses is a double nobody notices is broken. A second,
separately maintained one would compile under this repository's CI and would never run a workflow,
so a wrong default or an uncalled path would surface in somebody else's build after a release. The
two doubles this repository used to keep drifted apart while both were under its own CI and both
were run every day, which is what that failure looks like while it is still cheap.

It lives here and not in a repository of its own, which is where entry 31 sent the shared election
cache, because the reasons differ. The cache has a release cycle of its own and must not drag the
platform's; the double has none, since it implements the adapter SPI and follows every change to it
in the same commit. A repository of its own would put a release round trip between an SPI change
and the tests which prove it.

What that costs is a contract. The log lines the double writes, the names of the beans it
registers and the way it turns a filename into a BPMN process id are things a consumer can rely on,
so they are written down in `bpms-double/README.md` and changed deliberately. Everything else about
the double, the class layout included, stays free to move.

### 34. The core names the ids nobody deployed, the adapter decides what to do with them

A workflow module may declare a BPMN process id it deploys nothing under, which is how a renamed
process keeps being served. Only the core knows those ids, because they come from the annotations
of the application and the models of this deployment name themselves rather than the id somebody
renamed away from, and it already hands them over once per boot to ask what the BPMS holds for
them (decision 15). What the BPMS holds under such an id is then readable by an adapter whose BPMS
answers for a process id, which is what the Camunda 7 adapter does with the models its engine
kept.

Serving the workflows running under them is a second duty, and the core does not decide it. What
it takes to reach such a workflow is BPMS knowledge: an identifier which carries no process id
reaches the old id by itself, a Camunda 8 job type under `use-prefix` carries one and needs a
subscription per name, and Camunda 7 needs the models its engine still holds, because the way a
task is wired lives in them and nowhere else. So the core answers two things and stops there.
`taskWiringOfProcessesNobodyDeployed` says WHICH ids a workflow module declares without a model
and WHAT its `@WorkflowTask` methods serve for each of them, and the adapter composes its BPMS'
own identifiers from that answer. Deciding it here would put one engine's notion of a
subscription into the core, which is the mistake version 1 made with the eventual-consistency
handling.

The method is named after the question rather than after today's answer, and that is deliberate.
What an application may name its wiring by belongs to the surface of `spi-for-java` and can
widen; what an adapter needs from the core does not change, being the wiring in the form it
composes its own identifiers from. A name saying "task definitions" would have to be changed
with the first such widening, and every citation of it with it. An adapter therefore reads an
entry as "what to compose from" and never assumes it is spelled the way its own BPMS spells an
identifier.

What an entry holds today is the `taskDefinition` of a method, so a method naming a BPMN element
id contributes nothing and an id can be named with an empty collection. That is not a gap in the
answer, it is the honest shape of it: an element is matched through the model, and the model of
that id is the one thing the application does not have. An adapter which composes from task
definitions can therefore say which workflows it will not reach, which is what the Camunda 8
adapter does while it starts.

### 35. What an extension needs from VanillaBP is a contract it describes, not a base class it inherits

The Business-Cockpit adapters of version 1 were not adapters at all: they were extensions built on
the platform integration's internals - `AbstractTaskWiring`, `TaskHandlerBase`,
`AdapterAwareProcessService` and a second `@ConfigurationProperties("vanillabp")` bean of their
own. Every one of those is a class of the platform which happened to be public, so every change to
it was a change to the extension, and an extension for Quarkus could not exist at all.

Version 2 gives an extension four things and no base class. It describes its own annotation
(`HandlerContract`: how a method is matched, what may stand in its parameter list, whether the
return value is delivered) and VanillaBP runs those methods with the mechanics of `@WorkflowTask`.
It contributes an `AggregateServiceFactory` and gets one bean of its service per workflow
aggregate, injectable with the aggregate as its type argument. It asks the election which BPMS
holds a workflow instead of assuming the first-priority one. And it configures itself below
`vanillabp.extensions.<extension>`, with a per-workflow-module override.

What ties them together is that each is parameterized BY the extension rather than shaped after
one: the annotation type, the service interface, the extension id. Nothing in the core knows what
a user-task detail is, and an application not using an extension sees none of it. The sample
extension of both platform integrations is the proof: it builds against the two SPI artifacts and
the platform-neutral core, and would stop proving anything the moment it needed a dependency on a
platform integration.

Two shapes were deliberately not chosen. A base class per platform is what version 1 had. And a
cockpit-shaped SPI in the core - `getUserTaskDetails` and friends - would have made the next
extension a change to VanillaBP.

### 36. An extension's handler contract may arrive after the workflow services were scanned

The workflow services are scanned while the process-service beans are created. Whether an
extension's own bean exists by then depends on what else the application does - on Spring Boot an
auto-configuration's position, on Quarkus which bean is resolved first - and an extension cannot
influence it and should not have to reason about it.

So the registry remembers every workflow service which was registered, and a contract arriving
later is applied to all of them; a workflow service arriving later is scanned for every contract
known by then. Both orders produce the same result, which is what lets an extension register its
contract wherever it is convenient.

The alternative was to demand that contracts be registered before the scan, enforced by a startup
check. It would have worked, and it would have made the first question of every extension author a
question about the boot order of two platforms.

### 37. The core answers a path against the declared types, and says nothing where they cannot decide

The values an aggregate shares are a nested structure, so a BPMN expression may navigate into them, and
everything the sync model decides about a top-level attribute it decides about every segment below one. A
model reading `order.internalCode` where that attribute carries `@NoSyncWithBPMS` therefore reads `null`,
and on an embedded engine that is often silent: a gateway with a default flow takes it and a Camunda 7
conditional event answers false and waits for good, with no incident and no log line. So the check an
adapter runs while its models are deployed has to be able to ask about a path and not only about a name,
which is what `WorkflowAggregateSync#whatAPathFinds` and `WorkflowTaskWiring#unsharedWorkflowAggregatePaths`
are for.

The walk resolves each segment against the DECLARED type of the one before it, using the same primitives
which produce the values: the readable attributes of a type, the mode that type derives from its
annotations, and the table deciding what a value becomes. It answers one of three gaps. The segment is a
readable attribute the sync model keeps back. The type before it has no readable attribute of that name, so
the shared values carry no such member. Or the value before it travels as ONE value, a number or a text,
and therefore carries nothing below it, which is what makes `order.dueDate.year` read
nothing where `dueDate` is a `LocalDate`.

Where the declared types cannot decide, the answer is that nothing was decided, and no caller may read that
as approval. A `Map` answers whatever key it happens to hold, a raw or wildcard collection hides its
elements, an interface or an abstract type is whichever implementation the application assigned, and past
the nesting limit the values are cut anyway. The walk judges no method call for the same reason: what
`order.total.doubleValue()` resolves to depends on the runtime class the BPMS' serialization produced, which
a declared type does not name.

One residual risk is accepted rather than designed away. A concrete class can still be subclassed, so a
segment which is no attribute of the declared type may be one of the object actually assigned. The answer
names the declared type it read against, which is what lets a developer see a false positive for what it is,
and the alternative would be to refuse every answer about any non-final type and thereby report nothing at
all. The FIRST segment is the one exception a caller has to handle itself, because a name which is no
attribute of the aggregate may simply be a variable the model provides.

`AggregateSyncSupportTest` holds the five answers and the refusals, `WorkflowTaskRegistryTest` the boundary
around the first segment.

### 38. A check judges a model without asking which application version deployed it

For every check VanillaBP makes against a BPMN model it must not matter whether the model comes
from the current deployment or was already in the BPMS. A check may say yes or no, and it may say
that its BPMS cannot tell. What it may not do is answer no because it read the wrong set of
models: after a rename the answer often lives only in a model an earlier application version
deployed, and a verdict formed over the current deployment alone judges the application by
what it happens to have redeployed.

Two rules follow, and the first outranks everything else. A check which cannot see every model
that could carry its answer stays SILENT, never refuses: a blind check costs a warning nobody
got, a wrong one ends a call the application made correctly. And where the BPMS can be asked,
the check reads what the BPMS holds for the ids the application declares - through one picture
per adapter rather than one query per check, so "cannot tell" is decided once and answered as a
value instead of being encoded as an absence. Reading models out of a BPMS is the adapter's
work; what the core contributes is which ids are declared, and where a core check needs a held
model it asks through the version catalog it already has.

The mirror image binds the deployment-time refusals: a refusal judges the model being DEPLOYED.
A model the BPMS already holds is only being read on behalf of a check, nobody can change it any
more, and what it carries and VanillaBP cannot read is a warning naming the version - never the
end of a boot.

The adapters carry the consequences in their own decision logs and cite this entry as the rule.
The startup diagnostics' `null` defaults (`processVersionCatalogOf` and its siblings) stay as
they are: `null` means "this BPMS cannot be asked", and every check reading the picture then
stays silent, which is the first rule applied one level up.

### 39. Which adapter ids a type serves is answered in one place

An application configures adapters, and both platform integrations plus every extension bridging
to a BPMS have to turn that configuration into the same list: the ids of ONE adapter type, one set
of beans per id, because several ids of one type is what a migration looks like. Three rules
decide the list, and every consumer which reimplemented it dropped one of them.

The first is the plain one: a section under `vanillabp.adapters.<id>` whose `type` is this type, an
id without a `type` being its own type. The second is convention over configuration: an id named in
`prioritized-adapters` which IS an adapter type needs no section at all, and this one cannot be read
off the bound sections, because a section may consist entirely of keys the core model does not know
(an adapter's own `rest-address`, `webapps`, `database-schema-update`) and then nothing binds for it.
The third is the application which configures nothing: the single adapter dependency IS the
configuration, and the id the core derives is the type.

Leaving one out is invisible until it is expensive. The Spring Boot registrar applied the second rule
only where NOTHING bound onto the core model, so a migration setup with one core key in the new
BPMS' section registered no beans for the old adapter while the core derived its section and the
election looked for something serving that id. The Quarkus producers of the BPMS double filtered
the sections and nothing else, so the same two rules were missing there. Outside this repository an
extension bridging to a BPMS registers one bridge per configured engine and needs the identical
list, and a copy of it which drops a rule leaves the adapter registered and the bridge missing.

So `MigrationAdapterProperties#adapterIdsOfType` answers it, `AdapterBeanRegistrarSupport` binds
the tree off the Spring environment and asks it, and a Quarkus consumer asks it of the mapped
properties. What stays platform-specific is where the properties come from, which is the only part
that differs. `MigrationAdapterPropertiesTest.AdapterIdsOfType` holds the rules,
`AdapterBeanRegistrarSupportTest` the Spring binding and `QuarkusAdapterIdsOfTypeTest` the Quarkus
one.

### 40. A name clash is asked about where the BPMS already holds the name

`validateNoCollidingProcessIds` compares the scoped identifiers of one deployment against each
other, so it never sees the identifier another application deployed into the same BPMS years ago.
That deployment succeeds and the BPMS then decides on its own which side a start or a message
reaches. So the clash check asks as well: the adapter queries its BPMS about the identifiers this
application is about to deploy, and reports what is held already through
`reportIdentifiersTheBpmsAlreadyHolds`.

The adapter asks and the core words the warning. Only the adapter can query its own BPMS, and only
the core knows the mode, the property which set it and the scoped form a plain identifier ends up
as, which is decision 9. The adapter therefore hands over PLAIN identifiers plus a sentence naming
the holder, and the core writes our side, their side and the change which frees the name.

The question does not belong on `ProcessVersionCatalog`. That interface is keyed by a workflow
module and a BPMN process, while a message name and a decision id are scoped by the module alone.
Its answer carries a version and no holder. It is obtained for the ids the application DECLARES,
and this question is about an id somebody else holds. And its contract promises answers cheap
enough for a task dispatch, while this one is put while deploying. A question the core drives
itself fails for a simpler reason: nothing in the adapter SPI carries a message name, a signal
name, an error code, an escalation code or a decision id, so the core cannot even form the set of
identifiers to ask about.

A QUERY serves a BPMN process id and a DMN decision id, and only on a BPMS which keeps a repository
to search. Camunda 7 answers both from its repository service and Camunda 8 from its definition
searches; the Process-Engine-API answers nothing, because its API carries no read method at all.

No index answers for a message name, a signal name, a BPMN error code or an escalation code, and
that is not the same as nobody being able to answer. Those names live in a BPMN model, and a BPMS
keeps the models and hands them back: `RepositoryService#getBpmnModelInstance` on Camunda 7,
`newProcessDefinitionGetXmlRequest` on Camunda 8, and both adapters read models that way already. So
the names CAN be determined, by reading a model rather than by asking an index, and what rules the
complete answer out is the cost: one model read per definition version a BPMS holds is a number which
grows for as long as the application is in production, which is what decision 19 forbids a start to
do. Where the models are being read anyway the question is free, and that is where it is asked, see
below. Camunda 7 can also be asked about the message and signal names of START events, and that
half-answer is left out on the other rule of decision 19: a check which sometimes runs is worse than
none, because its silence stops meaning anything. A task definition is read off a held model like the rest, and what
differs is whether it is scoped at all. On Camunda 7 it is not: a task definition is process-local
there, the expression is evaluated inside the process by VanillaBP's EL resolver, nothing subscribes
to it engine-wide, so that adapter does not rewrite it and the question does not exist. A Camunda 8
job type is the opposite and is prefixed, because a job type is what a worker subscribes to,
cluster-wide. `ScopedIdentifierKind` names the kinds one by one, so a warning says "message name"
where a developer would say it.

A finding is a warning and never ends a boot, which is decision 38 applied: whoever holds the name
may be an application running correctly, and ending this boot would not help it.

Two more checks of the same subject cost nothing and were missing, and both are about the workflow
modules of THIS application rather than about somebody else's deployment. An adapter rewrites every
message name, signal name, error code and escalation code of the models it deploys through
`scopedIdentifier`, so it holds all of them while it deploys and the core learned none of them:
`reportIdentifiersTheModelsDeclare` is where they arrive now, and two workflow modules whose names
end up as one scoped form are named with both sides. Task definitions are part of that, and on a BPMS
which subscribes to them cluster-wide they are the most expensive kind of the set: two modules using
the same task definition under `none` end up with one job type, and the worker of one module fetches
the jobs of the other. A held version's job type is live for as long as workflows run on that version,
so the second check reaches it too. An adapter whose BPMS keeps task definitions process-local reports
none of them and the core needs no case for it. What is not a finding is two processes of one module
sharing a task definition: with `prefix-task-definitions-per-process` at its default their scoped forms
differ anyway, and where an application switched that off the sharing is its own explicit choice. Under `use-prefix` the forms differ and there is
nothing to report; the modes which let two modules share a name are `none`, and `by-adapter` where one
`tenant-id` for the whole adapter puts every module into one scope. Several processes of ONE module
sharing a name is the scope working as intended and is never reported.

Under `by-adapter` the line is a question rather than a verdict, and says so: what keeps the modules
apart there is the BPMS' own isolation, which is the adapter's knowledge and not the core's, so
VanillaBP cannot see whether one scope covers both modules or each has its own. Saying it plainly is
the same rule the finding about a foreign holder follows - a reader who cannot tell a certainty from a
guess ends up ignoring both.

That one warns instead of refusing, although decision 38's reasoning about a foreign deployment which
runs correctly does not apply to two modules of one application. Two reasons of its own do. Nothing is
overwritten: two BPMN processes under one id leave the BPMS holding one definition, while two modules
sharing a message name keep both models and only make the runtime meaning of the name ambiguous,
which an application may have arranged on purpose. And an application which ran like this yesterday
must not be stopped by an upgrade.

The second one reaches the names a workflow module deployed years ago.
`ProcessVersionCatalog#identifiersOfVersion` answers which of those names ONE held version declares,
read from the model the BPMS still hands back, and `reportIdentifiersOfHeldVersion` holds it against
what the current deployment of the other modules declares. It is asked in the loop
`DeployedProcessVersionsCheck` already runs over the held versions older than this boot's and not
faded out, which already reads each of those models and already knows how many workflows run on
them, so the question adds no fetch on an engine which caches parsed definitions and no query at all.
An adapter whose model read goes over the wire is expected to hold the model for the length of that
version's turn; the core caches no model. A held version of the module which still deploys the name is
continuity and stays silent, and the finding is a held version of one module carrying the name another
module deploys today. That one can only warn: nobody can change a held model any more, and the
workflows on it are running correctly, which is why the message says how many there are. Its limit is
which models get read at all - a workflow module the application dropped entirely has no catalog, so
nothing is asked about it, and its names stay invisible.

What is hard here is not the query, it is the discriminator. An identifier equal to one we deploy is
matched by our own previous version first, and no engine records which application deployed a
definition. Camunda 7 comes closest, because its adapter stamps every deployment with a name and a
source, so a definition whose deployment carries neither was not made by this adapter id; two
applications configured with the same adapter id and the same workflow module id write the same
stamp, so even that stays a hint. Camunda 8 has no owner attribute at all and goes by the resource
name, which is openly a heuristic. `certainlyForeign` carries the difference into the message,
because a reader who cannot see whether a line names another application or their own earlier
deployment learns to ignore the whole message. `IdentifiersTheBpmsAlreadyHoldsTest` holds the wording
of that one, `CollidingIdentifiersOfWorkflowModulesTest` the two checks about this application's own
modules, `OldProcessVersionsTest` that the held versions are asked in the loop which reads their
models, and `StartupQuestionCostTest` that a boot says each of the three once per workflow module
respectively per held version, however much the BPMS has collected and however many workflows run on
it.

### 41. Two workflow modules under one process id end the boot of the second one

`validateNoCollidingProcessIds` exists for one clash: two BPMN processes of this application which
reach the BPMS under the same identifier. The BPMS keeps one definition under that identifier and
loses the other, so one of the two workflow modules runs on a model nobody deployed. That makes it
the one name clash which is a refusal rather than a warning, unlike everything decision 38 and
decision 40 cover.

A deployment is per workflow module, so a caller can only ever hand over the processes of the module
it is deploying, and the clash lives between two modules. Every caller did hand over one module, so
the check was blind to the only case it was written for. What spans the modules is the map
`NameClashAvoidanceService` already keeps for the reports about identifiers two modules share: the
question is the same one, which workflow module already reaches the BPMS under this form, so the map
answers it for BPMN process ids as well and no adapter needs a memory of its own. The map is keyed by
adapter id, and that is not a detail: two adapter ids of one BPMS type are what a migration looks
like, each deploys into its own scope, and neither may see the other's processes.

Remembering across calls moves where the boot ends. It now fails while the SECOND of the two modules
deploys, with the first one already in the BPMS. A half-deployed application is the lesser evil: the
alternative is two workflow modules sharing a process id without anybody being told, an application
which starts and then serves one module out of the other's model. The refusal names both modules and
both plain process ids, so the developer does not have to work out which two deployments met.

A naive widening would have refused applications which are correct today, and working out why decided
the shape of this. Under `by-adapter`, the default mode, nothing is prefixed, so the scoped form IS
the plain form and two modules which a tenant keeps apart perfectly well arrive as two equal strings.
The core cannot judge that, because the isolation mechanism is the adapter's knowledge, so it asks:
`AdapterDeploymentService#ownIsolationSeparatesWorkflowModules` answers whether the BPMS itself would
put the two modules into different scopes, and only a "no" is a collision. The default answer is
"nothing", which is honest for a BPMS without isolation and is also the refusing side of a question
an adapter has not answered yet. The adapter answers about the scope it would REALLY deploy those two
modules to rather than about a property it reads, because `tenant-id` is resolvable per level and two
modules can land in one tenant without one line of the application saying so. Under `use-prefix` the
core composed both strings itself and asks nobody, under `none` nothing is scoped and nothing
separates the two by definition, and a mixed configuration is how one module's prefixed form meets
another module's plain id.

`CollidingProcessIdsAcrossWorkflowModulesTest` holds all of it, both deployment orders included, and
`StartupQuestionCostTest` holds that the adapter is asked once per pair of workflow modules rather
than once per process, which is decision 19 applied to a question the core puts to an adapter.

### 42. A poller sleeps until its store says it owes something

The outbox pollers ran on a fixed delay, ten seconds per store, and every turn was a select plus a
delete whether or not anything was waiting. A workflow application spends most of its life waiting
in a timer, so most of those 8640 turns a day asked a question whose answer was known. On a database
billed by active use that is a bill for doing nothing.

The interval was a guess, and it was wrong in both directions. Too often for an application with
nothing to do, and no help at all for an entry which became due a millisecond after the last turn:
that one waited the full interval whatever the number was.

So the rhythm is gone. Every store answers the same question after each poll - when is the earliest
moment I owe something - and the poller sleeps until exactly that moment. An entry due in four
minutes is dispatched in four minutes rather than on the next tick, and a store which owes nothing
is left alone. The question is the due-entry select with its time bound dropped, which is what keeps
the two in step: an entry the question does not see is an entry the select would not pick up either.
A BLOCKED entry is in neither, and that is the point of the predicate. It waits for a person rather
than for a clock, so a store holding nothing else has nothing to be woken for. The moment the oldest
dispatched entry may be deleted is part of the answer as well, so the wake-up which deletes is the
same wake-up which dispatches.

*The delete has left the poller since: the night window of decision 91 removes the dispatched
entries now, and a wake-up only dispatches.*

**The question has to be answered from an index, and the shape of the index decides the shape of the
question.** A repeated question whose cost grows with everything a table ever held is what entry 19
forbids, and an aggregate over an unindexed column is exactly that: measured on PostgreSQL 16 with
200000 rows, 11 to 18 ms as a sequential scan against 0.05 ms with an index, and only the first
number grows. So the two stores VanillaBP owns ship two indexes each, over the status and the
timestamp of each question - two and not one, because both questions filter the same status and order
by a different moment, and an index over both moments would serve neither. The same pair serves the
select which picks the due entries up and the delete which ends the retention, which is why there is
nothing to add beyond them. (The gruelbox store has its own repository since decision 102, so what
follows is that repository's to keep.) gruelbox owns its table and already indexes
`(processed, blocked, nextAttemptTime)` for its own flush, so nothing is added there and the QUESTION
is shaped to fit that index instead: two reads naming both flags, one per value of `processed`,
rather than one read naming only `blocked` which would have scanned the table.

What the indexes cost is on the write path, and an outbox is write-heavy by nature. Two more index
entries per inserted row, and a maintained entry wherever a status or a timestamp moves, which is the
claim and the marking as DONE. Against that stands one read per wake-up plus one per poll which would
otherwise be a scan, and the row is written two to three times during its life anyway. An application
which knows its own numbers better is free to drop them; nothing in VanillaBP reads an index by
name.

The notification after a commit stays what it was, the fast path, and it does more than before: it
pulls a sleep forward to the moment the entry it planned is due. An entry due now is dispatched now,
which is what every VanillaBP application always did, and an entry due in an hour shortens nothing.

**Nothing wakes the other nodes of a cluster, and that is deliberate.** Every node is equivalent,
and the node which writes an entry is by definition awake: its own post-commit hook dispatches it.
A sleeping node which never learns about that write loses nothing, because nobody was waiting for
that node in particular. So the cluster needs no notification between its nodes, and a database
notification such as Postgres `LISTEN/NOTIFY` buys nothing here while costing the portability this
platform promises - it exists on one of the supported databases and has no equivalent on MongoDB at
all. It is named here so nobody reopens it by accident.

What does need an answer is a node which GOES AWAY between writing work down and doing it: an entry
it inserted or a retry it rescheduled, while every other node sleeps until a time computed before
that entry existed. Graceful or not makes no difference, since a shutdown stops the poller and
leaves what it had not dispatched. `vanillabp.outbox.poll-interval` is the cap on the sleep which
covers that, and it covers nothing else. Ten seconds by default, the rhythm every application polled
at before, so one which sets nothing keeps the timing it had and the saving is bought by raising the
cap. **A reader who takes the cap for a cross-node notification sets it to seconds and gives the
whole saving away**, which is why the property's name is explained wherever it appears.

A shared election cache looked like the exact signal and is not used. The membership change it sees
lives in the repository of that cache rather than here, `WorkflowAdapterCache` has no method which
could carry it, and Hazelcast reports a node which died rather than left only after its own
heartbeat timeout, a minute by default. A signal which arrives after a minute is not better than a
cap an operator sets to a minute, and it would tie the platform to a dependency an application does
not have to bring. Where somebody builds it later, the cap is what it replaces.

`OutboxSleepsWhileNothingIsDueTest` of each store measures what a quiet application costs, which is
the claim that matters; that the poller sleeps is not the same claim and is worth less. It counts
connections on the two JDBC stores and commands on the two MongoDB ones, because a connection is the
claim from below - none taken is none used - and MongoDB's driver reports every command it sends.
Each of those tests first asserts that its counter sees traffic at all, since a counter which
silently counts nothing would turn the test green.

### 43. Housekeeping runs where there is something to house-keep

The retention cleanup of the task-delivery records ran hourly per store and deleted mostly nothing.
An application asleep for a day was woken twenty-four times for a delete which found nothing to
delete, which is the same defect as entry 42's interval in a cheaper place.

A run now happens only where a delivery was recorded since the last one. The records of that store
come into being when the application does work, so an application which does no work grows nothing
to delete and its database sees no statement from here.

What that leaves behind is the last batch before an application went quiet: records kept until it is
used again, or until it restarts, because the run at startup stays. That residual costs disk and
never correctness. Keeping a delivery record LONGER is the safe side of the window entry 24 splits
out - a record which is still there answers a redelivery from the record, and a record which is gone
runs the `@WorkflowTask` method a second time.

The alternative was to ask first whether anything is old enough and to skip the transaction where
nothing is. It was rejected because the question is itself a statement, one per hour per store, which
is the traffic the delete already was. A cheaper question buys nothing where the answer costs as much
as the act.

The outbox's own retention delete is NOT gated this way either. It runs in the night window of
decision 91.

*This paragraph used to say that the delete rides the poller of entry 42. That stopped when decision
91 moved it into the night window.*

### 44. A handler VanillaBP calls takes part in the transaction it finds

Entry 11 says that loading an aggregate, running a handler and saving it belong into one unit of
work, and that VanillaBP opens the right one. It says nothing about what happens when something is
already open, and for the handlers of an extension the answer was a transaction of VanillaBP's own,
suspending whatever ran. That turned one unit of work into two wherever an extension was called from
a transaction: the save of the handler committed while the work around it could still be rolled back,
and what the handler wrote then stayed behind with nobody to notice.

A handler of an extension now takes part in the transaction running on the thread and gets one of its
own only where none runs. The seam carries the form rather than a boolean, so all three ways of
working in a transaction can be ordered, and the form which says "take part, or open one" is what an
extension gets. The switch an extension could set to ask for the running transaction is gone with it:
one form serves both cases, and a switch without a second position explains a choice nobody has.

This is not a change against version 1. Version 1 never opened a transaction of its own around a
handler, neither in `spring-boot-support` nor in the Camunda 7 adapter, and from 1.4.0 the Camunda 8
adapter saved inside the application's transaction as well. So there is no `UPGRADE.md` entry: the
rule that defaults behave as version 1 did is kept by this change rather than broken by it.

A task is different and keeps its two forms, because there the adapter knows the answer. An embedded
engine calls inside its own transaction and owns the commit, and every other adapter calls from a
worker thread where nothing is open. An adapter stating a fact about its own threads is not the same
as a caller guessing, which is what the extension seam used to do.

What this does not do is remove the second writer. Two writers still commit one after the other
inside one transaction, and an application writing from a thread of its own is untouched by any of
this. It shrinks the window to the length of one transaction instead of the length of a delivery.

### 45. A handler allowed to save is warned about where nothing would notice the loss

Entry 14 warns about a BPMN model which can hold two tokens while its workflow aggregate has no
version attribute. That covers the second writer which stands in the drawing. It does not cover the
one a dependency brings: an extension may have VanillaBP save the aggregate after a handler of the
application ran, which is a writer nobody sees in the model and nobody configured.

So the same warning is given for that as well, once per BPMN process, naming the extension and its
annotation. Nothing else changes. There is no check while the application runs, no detection of the
conflict, no second report and no refusal to boot, now or after 1.0: an application whose handlers
write and whose aggregate cannot notice a second writer has made a decision, and what it is owed is
to be told which one.

A boot can only say that the handler MAY save. Whether a single call saves is the extension's choice
per call, and an extension asking for a reading handler says so at the call. The message is worded
accordingly. An extension whose handlers never write at all says that on its contract instead, and
then there is nothing to report; entry 50 says why that statement belongs there. Warning about what
is allowed says something where nothing happened; saying nothing until a write is lost says nothing
where it mattered, and of the two only the first can be read and dismissed.

The question goes to the persistence of the aggregate, not to an annotation of the class.
`AggregatePersistenceAware.detectsConcurrentModification` answers it, its default looks for the
attribute every supported persistence layer calls `Version`, and a store which notices a second
writer some other way overrides it and is then quiet. Writing the question per technology would be
the same question several times, and it would have no answer at all for a store VanillaBP does not
know.

Nothing is asked of a database for it. The answer comes from the aggregate's class, and the question
is put once per BPMN process, which is the shape entry 19 asks of everything a start does.

### 46. A test about housekeeping asserts what is left, not what it deleted

`MongoTaskDeliveryLogTest` counted the records its own call to the retention cleanup had deleted, and
failed once with one expected and none found. Since entry 43 that cleanup runs at startup and again
wherever a delivery was recorded, so the test and the application's own housekeeping point at the
same record, and whichever gets there second deletes nothing. A count then reports who was first.
What the test wants to know is whether anything past its retention is left.

So a test of this kind reads the state back instead: the record is gone, and the store holds nothing
older than its retention. That answer is true whoever deleted it, and it is still true when the
cleanup runs once more afterwards, which is why the test runs the cleanup twice. The tests of all
four stores are written that way now. The number a delete returns keeps its own test in
`OpenTaskRecordRetentionTest`, against a store nobody else writes to, so the promise of the return
value is still held somewhere.

The clock is out of it as well. A retention of zero made every record expired the moment it was
written, and a store whose timestamps have millisecond resolution cannot tell a record written in
that millisecond from one older than a bound which is strict. The stores of these tests keep an hour
now, and a record which has to expire is written two hours old.

Raising a timeout or repeating the test was rejected. Both hide that two deleters point at one
record, and the second run is green for the same reason the first one was red.

### 47. A store which cannot name its waiting adapter ids at a start names them at the first dispatch

A boot names the adapter ids which outbox entries are still waiting for, because an id the
configuration no longer has means a workflow was persisted and never started (entry 17). Three of the
four stores keep that id in a column of their own and answer with one `DISTINCT` over it. The
gruelbox store, which is what Spring Boot with JPA uses and therefore what most applications run,
keeps a call as one serialized invocation: the only way to answer there is to read every row and
deserialize it. Entry 19 rules that out. A start asks for numbers, and a question answered from
everything an application ever scheduled gets slower for every year it is in production.

*The premise changed twice since. Spring Boot with JPA runs VanillaBP's own JDBC store by default
(decision 75), which answers at the start, and the gruelbox store has its own repository (decision
102). The rule below still holds for a store which cannot answer at the start.*

So the question is put where the invocation is deserialized anyway, at the dispatch of the entry.
The id is at hand there, an id which is gone from the configuration is reported in the words the boot
of the other stores uses, and it costs nothing which was not already being paid. The report is said
once per adapter id and BPMN process, and the memory is shared with the boot, so a store which
answered the boot adds nothing at its dispatches. What a reader loses is time: the answer arrives
with the first flush after the start rather than during it, which is the poll interval of the outbox.
What a reader gains is that it arrives at all, in a setup where the check used to be silent.

The blind spot is the same one the three answering stores have. They ask about open entries, so an
entry which used up its attempts and was blocked is in nobody's answer, and gruelbox never reads a
blocked entry again either. A blocked entry has an ERROR of its own naming the workflow it lost.

Three other ways were weighed. A column next to gruelbox' invocation means adding one to a table
which belongs to gruelbox, and entry 16 says which tables are VanillaBP's and that nothing else is
touched. A table of VanillaBP's own, holding the adapter id and a count, answers the boot in one row
per adapter id, but it costs a second write on every workflow start, and it would be a third table in
the one setup which was chosen because it brings no table of VanillaBP's at all. And declaring the
check absent for this store leaves the gap where it is, in the setup most applications run, which is
the state this entry replaces.

### 48. An extension is configured by the core's resolution, not by a parser of its own

An extension is told things about a workflow module, about one BPMN process, and sometimes about
one task. The core already resolves such a setting for an adapter, over four levels, most specific
first, merged key by key (entry 7). An extension which cannot reach those levels writes the
resolution a second time: it reads the flat keys of the configuration, matches the suffixes by hand
and decides on its own what "most specific wins" means. The Business Cockpit had that, once per
platform, and the two spellings were already drifting apart before anybody noticed.

So the `extensions` map sits at all four levels rather than at two, and
`MigrationAdapterProperties.resolveForExtension` is the same walk `resolveForAdapter` is. The core
owns the LOCATION and the resolution; what a key MEANS stays the extension's business, which is why
the values stay strings and the extension binds and validates its own, typed, the way an adapter
binds the keys below its adapter id.

The extension id is a parameter of the call rather than something bound to an extension-scoped view
of these properties. The properties are one bean of the platform, an extension is not a bean of the
platform at all, and an extension asking with its own id is the same shape an adapter asking with
its adapter id has.

The Quarkus half is not a duplication but a wall. SmallRye refuses a key it does not know below the
mapping root, so before the workflow and the task level were part of the mapping, an application
writing `vanillabp.workflow-modules.<m>.workflows.<p>.extensions.<ext>.*` did not merely go unread,
it did not start.

### 49. A dispatch which cannot run yet ends, and nothing waits inside a transaction

Phase two of an operation may be rejected rather than fail: on Camunda 8 the exporter of the cluster
has not written the workflow yet, the adapter says so and names how long that usually takes
(`PhaseTwoRetryLater`). This is the ordinary case there, not an exception.

Two answers are possible, and only one of them works. A store may end the attempt and plan the entry
again, which is what the three stores VanillaBP wrote itself do. Or the dispatch may ask again on the
spot, which is what the gruelbox store did, in slices of half a second, so that a message correlated
right after a start went through in the second the cluster needed instead of in the next
`attempt-frequency`.

Asking again on the spot cannot work, because the attempt is not alone in its transaction. Gruelbox
opens one around the dispatch and ticks the entry off in it, and the dispatch joins that transaction
so that everything a handler writes stands or falls with the entry (entry 11). A rejected attempt
rolls that transaction back, and a transaction somebody joined and rolled back is marked
rollback-only for good, in Spring as in JTA. The attempt behind it therefore reached the BPMS and then
lost its commit. What happened next depended on a race with the flushing thread: either the entry
stayed open and the consumer got the same call a second time, or the update which counts the attempt
wrote the processed flag of the rolled-back attempt and the operation was recorded as done although
its effects were gone. Both cost what a handler had written, and neither said so. Gruelbox said what
it could see: `Failed to update attempt count`.

So a rejected attempt ends. The entry goes back to the store, and the window the adapter named is
written onto its row afterwards, in the listener which also blocks a permanent failure, because that
is the one moment at which the failed attempt is committed and the row can be touched again. The
short due time is therefore kept, which is what the waiting was for, and no thread and no database
connection is held while a cluster catches up. The attempt is counted like any other, so
`block-after-attempts` still ends a workflow which never becomes visible.

The poller is told the new due time (decision 42), so the entry is dispatched when the window it
asked for ends. What it ends is a dispatch which
slept while it held the transaction of the store, and with it the question of how many such sleepers
a connection pool survives.

`ARejectedDispatchIsPlannedAgainTest` holds both ends: the call reaches the consumer once and the
entry is ticked off, and the workflow which is searchable is served first while the other one waits.
`GruelboxWritesTheDueTimeADispatchAskedForTest` holds the due time in the row, in the repository of
the gruelbox store since decision 102. VanillaBP's own JDBC store writes the window when the attempt
ends, without a listener.

*Superseded in part by decision 113: the sentence saying the attempt is counted like any other, since an answer `PhaseTwoRetryLater` uses no attempt now and `wait-for-visibility-at-most` ends a workflow which never becomes visible.*

### 50. An extension may say while it is wired that a handler never writes

Entry 45 warns about a handler an extension may have VanillaBP save. That warning is about what is
allowed rather than about what happened, so it also reaches an extension whose handlers only read.
The Business Cockpit is that case: its details providers read the aggregate to build what is shown,
and they never write. Every application using the cockpit would read the warning at every start about
something which cannot happen, and a warning nobody can act on is one people learn to skip.

A call can already say that the aggregate is not to be saved, but it says it too late. The check runs
while the methods are wired, and at that moment nobody has called anything. So the same statement is
made on the handler contract, once, and it is there when the methods are found.

It belongs to the contract and not to the extension, because one extension can have both kinds: a
provider which reads and a notification which writes. Two kinds are two contracts, which such an
extension writes anyway, since the two carry different annotations.

The contract outranks the call. A call of a reading contract does not save whatever it asks for, and
asking is not refused: saving is what a call does unless it says otherwise, so refusing the
contradiction would refuse a caller for a sentence they never wrote. What is switched off is the save
VanillaBP performs. A persistence layer which writes a managed object by itself still writes it when
the transaction commits, which is the boundary a reading call has as well.

### 51. The keys an invocation offers are ranked, and the element id comes first

An extension names the element its event is about in more than one way: the BPMN element id and the
task definition of the same element. It offers those keys and VanillaBP picks a method for one of
them. The pick used to walk the METHODS and ask each of them whether it serves any offered key, so
the winner was decided by the order the class scan had found the methods in, which is the order a
platform's reflection happens to return. Two methods of an application, one per key, therefore won
against each other by chance.

So the walk is turned around. The keys are walked in the order the caller offered them, and all
methods are asked about one key before the next key is tried. The first key somebody serves wins,
which makes the order of the offered list the rank, and the method serving every element of the
process stays the fallback for the case that no offered key is served at all.

The rank itself is a direction of the platform rather than a choice of each extension: the element id
first, the task definition after it. The element id is the identity everything moves to, the
`taskDefinition` attribute of the annotations goes away later, and a VanillaBP BPMN of our own names
element ids while the adapter adds what its BPMS needs. An extension built on that order keeps
working unchanged through the removal, because the list then simply loses its second entry.

`ExtensionHandlers.hasHandler` takes a `List` for it. A `Collection` has no order anybody promised,
and the same question answered from an unordered argument would be half the promise. It is the one
place this is not additive, and it is narrower on purpose.

The keys a METHOD declares carry no rank of their own. A method says what it answers to, and that is
a set; the rank belongs to the question, not to the answer. Nor does the platform check that an
extension really offers the element id first, because a string does not say what it is, and a check
which guesses would be one more thing to be wrong.

Next to the pick there is now a line per extension, workflow module and BPMN process which says what
was wired: the methods, the keys each of them serves, and which of them serves every element.
Everything it names was in the registry already, so no hook was added to the handler contract for it.
The report is written once the workflow module is deployed, which is the moment the `@WorkflowTask`
side of a module is judged at, and a contract registered after that writes its own line when it
arrives. A BPMN process an extension has no method for is not named: most pairs of extension and
process have nothing to say, and the method nobody can see has its own report.

### 52. The adapter runs before every extension, and two extensions are not ordered against each other

An adapter and an extension work on the same BPMN model, so one of them is first. VanillaBP promises
which: the adapter has wired a BPMN process before any extension sees that process, and it is
processing workflows before any extension is started. Going down, extensions stop first and the
adapters last, so nothing is stopped while something else still feeds it.

What an extension builds on is therefore a relative position, not a number. It adds its listener
behind the last one of a kind the adapter put there, which keeps working when the adapter changes
what it writes. On Camunda 8 the Business Cockpit adds its `creating` listener behind the last
`creating` one and the rest behind everything; on Camunda 7 the adapter offers
`parseListenersAfter` and `parseListenersBefore` for the rare case of an element which has to be
seen untouched.

The order of two extensions among themselves is deliberately not promised. They are sorted by the
order each asks for, and two extensions which do not know each other cannot agree on a number, so
two of the same order run in the order the platform collected their beans in. Giving out a first and
a last position instead would only move the problem: the second extension wanting to be last is
where such a scheme ends, while a relative hook needs no agreement at all.

The promise used to live as javadoc in three repositories and nowhere as a statement about
VanillaBP, so an extension author found it by opening the right one by chance. It is now on the wiki
page `Extensions`, in `ADAPTER-AUTHORS.md` for the other side, and in the javadoc of
`ExtensionWiringService`. `DeploymentServiceTest#theAdapterIsFirstOnTheWayUp` holds the way up and
`#extensionWiringServicesAreStoppedBeforeAdapters` the way down, in the platform rather than in the
repository of one extension: a promise of VanillaBP which only an extension tests goes away with
that extension.

### 53. The section name belongs to the plug-in, and the walk over its levels to the core

A plug-in setting is written at four levels, and each level has two positions: what the level says,
and what it says for one adapter. That makes eight, from the least specific one to the most
specific:

```yaml
vanillabp:
  cockpit: …                                     # 1. the application
  adapters:
    saas:
      cockpit: …                                 # 2. the application, on this adapter
  workflow-modules:
    loan-approval:
      cockpit: …                                 # 3. the workflow module
      adapters:
        saas:
          cockpit: …                             # 4. the workflow module, on this adapter
      workflows:
        LoanApproval:
          cockpit: …                             # 5. the workflow
          adapters:
            saas:
              cockpit: …                         # 6. the workflow, on this adapter
          tasks:
            approve:
              cockpit: …                         # 7. the task
              adapters:
                saas:
                  cockpit: …                     # 8. the task, on this adapter
```

The most specific position which writes a setting wins, and what one adapter is told beats what the
same level says in general. The adapter positions exist because a plug-in hangs on every configured
adapter separately: an application migrating from `on-premise` to `saas` runs two adapters of one
BPMS type, and until now there was no place to tell them different things. Entry 48 put the
resolution into the core; this entry adds the adapter positions to it and says who owns the name of
the section.

The name is the plug-in's. `extensions` is a word of the contributors - a user knows adapters,
VanillaBP adapters and Business Cockpit adapters - and the Business Cockpit is configured below
`vanillabp.cockpit` because that is where version 1 configured it, so an application moving to
version 2 swaps a dependency and nothing else (entry 14 of the Business Cockpit's own log,
confirmed 2026-09-15). A plug-in without such a past is configured below
`vanillabp.extensions.<id>`, which VanillaBP binds itself as flat text. The mechanism does not know
the difference: it knows one section per level, and the plug-in binding its own typed tree hands in
one accessor per level, because lists and group hierarchies cannot be assembled out of flat strings.

So the walk lives once, in `SettingsResolution` of the extension SPI, and
`MigrationAdapterProperties.resolveForExtension` is one of its callers rather than a second
spelling of it. Before this the walk existed twice, in the platform for `extensions.<id>` and in
the Business Cockpit for its own tree, and two spellings of one rule drift.

The Business Cockpit needs positions 2 and 4 for nothing today, so the promise stands and falls
with the platform's own tests: `ExtensionSettingsPositionsTest` boots an application with two
adapters of one type on Spring Boot and on Quarkus, writes each of the eight positions once and
reads which one won. On Quarkus the boot is half the measurement, because SmallRye ends a startup
over a key no mapping knows - `UnknownExtensionSettingsKeyTest` writes such a key on purpose. A
position the resolution offers and the mapping does not declare would not be a position quietly
read by nobody, it would be an application which does not start.

### 54. The delivery log holds the work the application was handed, and nothing else

A record is written where a `@WorkflowTask` method ran. A user task the application has no method
for is therefore missing from the log, and `openTasksOfAggregate` does not name it either. That is
a gap somebody will run into, so it is written down here rather than left to be discovered.

The delivery does arrive, which is worth saying because it sounds as if it would not. An adapter
puts its lifecycle listeners on every user task of the model, not only on the served ones, so such
a task is handed out like any other. What the adapter does with it is the contract
`WorkflowTaskInvoker#workflowTaskHandlerExists` exists for: it asks before it delivers, and where
no method serves the task it finishes the notification itself and says so at TRACE
(`ADAPTER-AUTHORS.md`). VanillaBP stands next to that task and keeps no note of it. So this is a
decision, not a limit.

The gap it leaves is real. An extension showing what a case is waiting for reads the log, and a
workflow whose user tasks are all served by forms of the BPMS reads as a workflow waiting for
nothing. What speaks for closing it is exactly that: an answer with holes nobody sees is worse than
no answer.

It stays open, because a record needs an outcome and there is none. Every field of `TaskDelivery`
describes a delivery which was processed: the outcome reported back, the BPMN error it carried, the
moment the handler ran and the moment the completion reached the BPMS. A task no handler saw has
none of them. Such a record would also need a delivery key, and the core would have to invent one,
because the key it builds is the key a redelivery is recognised by. The first time that task really
is delivered to a method - the model gained one, or a second workflow of the same aggregate serves
it - the invented key answers the deduplication question wrongly. Writing down less than a delivery
is not a smaller record, it is a wrong one.

And a user task without a method is a design rather than an oversight. A task may be modelled
without touching the workflow aggregate at all: "set the machine to value x" can be ordered and not
checked, and what it leaves behind is that somebody did it. The process carries that, and the
Business Cockpit makes it visible. The wiring validation lets such a task pass on purpose, and the
log has nothing to say about it because the application was never asked anything.

So the log is what its name says, and the promise is written where a caller reads it: the javadoc
of `TaskDeliveryLog#openTasksOfAggregate` says that the answer is the open work of the application
and that the open work of the workflow is a question for the BPMS. An extension which needs the
second one asks the BPMS through its own adapter half, which is where the knowledge about querying
that BPMS lives anyway.

*Superseded in part by decision 119: the paragraph saying that a user task without a method is a design and that the wiring validation lets it pass. Such a task now needs a method or the line `implemented-externally=true`, as version 1 asked for the method. The rest stands: a user task the application marked that way still writes no record, for the reasons above.*

### 55. A number reaches a handler as the number the BPMS reported, or not at all

A `@TaskParam` declares a type and the BPMS reports a value, and the two of them do not have to
agree. Until now the core bridged the gap with `intValue()`, `doubleValue()` and their siblings.
Those read the bit pattern and cut a value down without saying anything, so a `Long` of
`3000000000` reached an `int` parameter as `-1294967296`, a `BigDecimal` of `120.50` reached one as
`120`, and a `BigInteger` of `9007199254740993` reached a `Double` with its last digit gone. A
measurement on 2026-09-16 found thirteen such cells in a matrix of forty-eight, the same thirteen on
Camunda 7, on Camunda 8 and on the Process-Engine-API, and not one of them wrote a log line.

The rule now is the one this repository already applies to an aggregate ID. A number is converted
through its decimal form, and the converted value is handed over only where it reads back as the
same number. Where it does not, the conversion fails, the invocation ends and the BPMS raises its
incident. The text of a number goes the same way, so a `String` of `"120.50"` bound to an `int` ends
in that message too, instead of the bare `For input string: "120.50"` an uncaught
`NumberFormatException` used to write.

Comparison is numeric, not textual. A `BigDecimal` of `120.50` bound to a `Double` is delivered as
`120.5`, because the two are the same number and the scale is not a value. A textual comparison
would have refused it. It would also have made the answer depend on the serialization format the
value was stored in: a nested decimal keeps its scale in one Camunda 7 world and loses it in the
other, so one and the same model would have converted here and failed there.

Why a refusal and not a warning: a warning is what an application already fails to read today, and
the wrong number keeps reaching handlers while nobody reads it. Version 1 bound the parameter by
raw reflection, so every narrowing pair threw `IllegalArgumentException: argument type mismatch`
there. The refusal is therefore a return to what version 1 did, with a message which names the
value, the declared type and the number which would have arrived. What version 1 accepted, a `Long`
and an `Integer` into a `long`, is accepted here as well.

Why in the platform and not in an adapter: no engine is involved in this. The check reads the value
in hand and the declared type, and it knows no BPMS, no version and no serialization format. Four
adapters converting on their own is how one handler starts answering differently per BPMS, and a
rule which had to know which engine wrote a value would go stale the moment that engine changed.
Which Java type a value comes back as stays the BPMS' answer, and `@TaskParam Object` is where an
application sees it.

The same method writes the attributes of a workflow aggregate the BPMS started
(`AggregatePropertyWriter`), so one rule serves both. A wrong number in an aggregate outlives the
handler which received it, which makes the refusal worth more there, not less.

*That class is gone since decision 92, because the platform builds no aggregate for a workflow the
BPMS starts. The paragraph above no longer applies.*

### 56. One version selection serves VanillaBP's own handlers and an extension's

`@WorkflowTask`, `@WorkflowStartedByBpms` and `@WorkflowEnded` let a method name the process
versions it serves, and the three registries behind them each carried their own copy of the same
code: a list of ranges, a match against the version a delivery reported, an overlap check against
the next method, a list of the version tags named. An extension bringing an annotation of its own
had nothing of this, so the Business Cockpit would have written a fourth copy in its own artifact
and every later extension a fifth.

The copies are gone. `ServedVersions` holds what one method serves and answers the three questions
about it, and all four kinds of handler hold one of them: the three of the core and the method an
extension's `HandlerContract` describes. An extension gets the ranges and the version tags
without writing any of it, tags resolved through the `ProcessVersionCatalog` of the adapters
included, and a change to the rules now reaches the core and the extensions at once. The
proof that this is one implementation and not a fourth copy is that the tests of the three
registries did not change.

An extension names the attribute its versions stand in, the way it already names the attribute its
lookup keys stand in, and the call names the version it is about. A method naming no version serves
every version, which is what every extension written before this gets, so nothing an extension does
today changes meaning.

Whether a version arrives at all is the contract's statement, made once
(`callsCarryTheProcessVersion`): the calls name the version wherever the BPMS reports one. It has to be the contract's, because the extension is the only one
who knows: a Camunda 8 job carries the version of its process, a Camunda 7 process definition knows
it, and an engine behind the Process-Engine-API fills a version tag only where it wants to. Where a
contract does not say it, a method naming a version can never run, and the start says so for every
such method rather than leaving the application to notice at the first event which does not arrive.
It is a warning and not the end of the boot, for the reason decision 20 gives about a method serving
no deployed version: what such a method does is an addition, and an application whose other methods
serve their events keeps running.

A method of an extension does NOT inherit the range of the `@BpmnProcess` of its class, although a
`@WorkflowTask` method does (decision 20). The class declaration binds the methods VanillaBP itself
calls to one generation of a model, and the events of an extension are not those methods: whether a
version even reaches them is the extension's answer, not the class', so a class range would switch
off methods which work today in applications which never asked for that. Naming nothing therefore
means every version here, and an extension method which is to be restricted says so itself.

### 57. The way back reads what the way out wrote, and refuses the forms which do not carry a value

A workflow aggregate shares an enum as the name of its constant and a value type like a `UUID`, a
`LocalDate` or a `Duration` as the string form that type writes itself (`AggregateSyncSupport`).
Reading such a value back was not possible: `ValueConversion` converted text into the eight
wrappers, `BigDecimal` and `BigInteger`, and refused everything else, so a `@TaskParam UUID` failed
against the very text the platform had written for it. An application could only declare a `String`
and parse it again in every handler.

The way back now reads exactly the forms the way out writes: an enum from the name of its constant,
and `UUID`, `Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`,
`ZonedDateTime`, `Year`, `YearMonth`, `MonthDay`, `Duration`, `Period`, `ZoneId` and `ZoneOffset`
from the text each of them writes. Nothing else was added. A form the platform never writes is a
form nothing can promise anything about, so a text in another shape is refused with an example of
the shape the type does write. `TextValueRoundTripTest` asserts both halves against each other, so
a change to either one is a failing test rather than a handler which stops being served.

An unknown enum constant is refused, naming the constants the enum has. This is the case decision 55
exists for, in its other half: a handler which took a name the model invented would act on a value
nobody declared.

`java.util.Date`, `java.util.Calendar` and `java.util.Locale` stay refused although the way out
writes them out as text, and this is the part which was measured first, on 2026-09-16. A `Date` is
written as `Wed Sep 16 21:55:30 CEST 2026`. That text carries no milliseconds, so the point in time
is already up to a second off before anything reads it back, and it names the zone by an
abbreviation several zones share. The same text written in `Asia/Kolkata` and
read in `Europe/Dublin` parses to an instant four and a half hours away and prints as the same text
again, so the round-trip check this repository uses everywhere else would pass a wrong value. A
`Calendar` is written as its debug form, and a `Locale` as `de_DE`, which no `Locale` method reads
back, while `Locale.ROOT` is written as an empty text. Each of the three is refused with the type to
declare instead, because the text in the BPMS looks readable and somebody has to say why it is not
read.

Why the way out was not changed to write something better for a `Date`: the text in the BPMS is what
an operator reads and what a BPMN expression works on, so changing it changes the model side for
every application already sharing such an attribute. That is worth its own decision rather than a
side effect of teaching the way back to read. Refusing costs an upgrading application nothing,
because the same declaration fails today.

One consequence worth naming, because a model reads these texts back. Camunda takes a date, a
duration or a cycle in a timer event. A `Duration` carries days down to seconds and a `Period` years
down to days, neither of them carries the combined `P3Y6M4DT12H30M5S`, and a cycle like `R5/PT10S`
is no `java.time` value at all and belongs in a `String`. `java.time` also writes a duration of a
day or more in hours, so `Duration.ofDays(14)` reaches the model as `PT336H`. The timer fires at the
same moment, because the engine reads both forms, and the difference is the wording an operator
sees, which the documentation says rather than the conversion repairing it.

`AggregatePropertyWriter` hangs on the same method, so an aggregate a BPMS-initiated start builds
gets its `UUID`, its dates and its enum attributes the same way, and refuses the same texts.

*Two parts of this entry changed later. Decision 58 lets a `Date` travel as the instant it holds and
adds `TimeZone`, and decision 59 stops the boot where a `Calendar` would be shared. Of the three
refusals above, only the one of `Locale` stays. And `AggregatePropertyWriter` is gone since decision
92, so the last paragraph no longer applies.*

### 58. A value type is recognised by what it is, and a Date travels as the instant it holds

The way out shared a value as text when the package of its class began with `java.` or
`javax.`, and that question was wrong at both ends. A `TimeZone` is a
`sun.util.calendar.ZoneInfo` at runtime, so it failed the check, the walk read its getters
and the sync point ended with an `InaccessibleObjectException` (measured on 2026-09-16).
A `Calendar` passed the check and reached the BPMS as its debug form, several hundred characters
naming every field of the implementation.

`TextValueTypes` now names the types a value travels as text for, and both ways read that
one list. The way out asks which of those types a VALUE is one of, so an implementation
class the runtime hands out is recognised by the type it extends. The way back asks the
DECLARED type exactly, because the text is promised for the types named and a subclass may
write a text of its own. Everything the JDK wrote and the list does not name still travels
as its own text, and the check for that now also asks which module a class comes from, so
no package the runtime exports to nobody is read by reflection again.

The list is a selection, and the documentation says so. It holds what a workflow aggregate
carries often enough to be worth carrying: an identifier, a point in time, a length of
time, a zone. Somebody who misses a type opens an issue for it. A list which promised the
JDK in full would promise a text for types whose text says nothing.

`java.util.Date` travels as the instant it holds, `2026-09-16T19:55:30.123Z`, and
decision 57 refused it for the text it used to travel as. The form of `Instant` was
chosen over the form of `OffsetDateTime` in the zone of the JVM because a `Date` is a
count of milliseconds and carries no zone of its own. The offset of the writing node is
not part of the value, so putting it into the text would let two nodes of one application
write two texts for the same value, and a value written in summer would read differently
from the same value written in winter. What an operator gives up for that is local time in
the model, which the documentation names. Every subclass travels the same way, through
`Instant.ofEpochMilli(getTime())`, so an attribute declared `Date` which a JPA provider
fills with a `java.sql.Timestamp` reaches the BPMS as the text a plain `Date` reaches it
as. A `Timestamp` loses what it holds below the millisecond, which is all a `Date` holds
anyway.

`java.util.TimeZone` travels as the id of `toZoneId()` and comes back through
`TimeZone.getTimeZone(ZoneId)`. `ZoneId.of` reads the text, because
`TimeZone.getTimeZone(String)` answers GMT for every text it does not know and a wrong
zone would travel unnoticed. Measured on 2026-09-17 against Java 21: a zone with a fixed
offset survives as it was written, so `GMT+05:30` is shared and read back as `GMT+05:30`.
A three-letter id does not survive, because it is no zone name. `IST` is shared as
`Asia/Kolkata` and `EST` as `-05:00`. The zone is the same one and its rules are the same,
the id is not, and an application comparing ids has to know that.

`java.util.Calendar` is carried neither way. Its text is the debug form, and reading a
point in time out of it means guessing which of its fields to trust. An attribute of that
type is named while the application boots, with `Instant` as the way. What a sync point
writes for it does not change: an attribute which quietly stopped being a process variable
would make a model read null and take a branch nobody chose, and the message at startup is
what makes the case stop being silent. `java.util.Locale` stays as decision 57 left it.

That message is a warning and not a failed boot. `validateSyncModel` is not told the
adapter's default, so it cannot know whether the attribute is shared at all, and an
aggregate whose values only ever travel outwards is an ordinary application. An attribute
marked `@NoSyncWithBPMS` is left out of it, because that one is certain never to travel.
`AggregateSyncSupportTest#whatCannotComeBackIsSaidAtStartup` holds the message, and
`TextValueRoundTripTest` holds both ways against each other for every type in the list.

What happens to a `Calendar` is decided by entry 59, which supersedes the two paragraphs
above in that one respect: such an attribute stops the boot instead of earning a warning,
and no sync point gets the chance to write it. Everything else here stands, the warning
for every other type included.

### 59. A Calendar the BPMS would be given stops the boot

Decision 58 named a `Calendar` attribute while the application boots and left the sync
point alone, so the BPMS kept being given the debug form of the implementation. That text
is 769 characters long, measured on 2026-09-17 against Java 21, and it is written again at
every sync point. It says nothing to a model and nothing to an operator. This is to be
prevented rather than reported (Stephan, 2026-09-17), so `validateSyncModel` collects such
an attribute as a defect and the application does not boot.

Leaving the attribute out of what is shared was the other way and was refused. A process
variable which quietly stopped being written would make a model read null and take a branch
nobody chose. So the application either declares a type which travels, an `Instant` for the
point in time and a `TimeZone` or a `ZoneId` next to it where the zone matters too, or it
says with `@NoSyncWithBPMS` that this attribute stays at home.

`Calendar` is the only type refused this way. Everything else decision 58 named keeps its
warning, a `java.util.Locale` and a `java.net.URI` among them: their text carries no value
back into a declared type, but it is text a person can read and work with, and a boot which
fails over it would cost more than it saves.

A boot which fails may not rest on a guess, which is why the refusal asks whether the
attribute really is shared, along the same chain a sync point walks. A class states its own
mode or inherits from the attribute holding it, and an attribute may state its own. Only
what is shared at the end of that chain is refused. What remains unknown is the adapter's
default, and the walk starts out sharing because `FULL` is the default of every adapter
there is (see `AggregateSyncMode`, whose `NONE` is meant for an adapter whose BPMS is fed
by something else entirely). The warning of decision 58 stays as loose as it was, because a
warning which is wrong costs a log line.

One consequence is worth naming: a type is now validated once per path leading to it rather
than once in total, because the same class may be shared below one attribute and hidden
below another. `AggregateSyncSupportTest#aSharedCalendarStopsTheBoot` holds the message,
and the tests next to it hold the aggregates which still boot.

### 60. A BPMS which keeps no version catalog says so, and the methods which never run are named

An adapter used to have one way of saying that its BPMS cannot place version tags: register no
`ProcessVersionCatalog`. That is the same thing the core sees before any adapter was asked, so the
core said nothing about the methods of such a process, and the two lines which did come out were
both wrong. One said at startup that a version tag is known to no BPMS, on the one BPMS where the
tag is the only thing a delivery carries. The other said at a delivery that no adapter can be asked
about the versions, which reads like a missing adapter and is a missing version notion. Both were
measured on 2026-09-17, on an application booted against the Process-Engine-API adapter with the
output suppression of the tests switched off.

An adapter can now answer instead of staying silent:
`WorkflowTaskWiring#reportNoProcessVersionCatalog(adapterId, module, process, reported)`, called
where `registerProcessVersions` would be called. `ReportedProcessVersion` is the second half of the
answer and says what a delivery of that BPMS carries, a version tag or nothing at all. With it the
core can name the methods whose version will never be met there, and both old lines are written in
the words of an engine which counts no versions.

The finding is a warning and the boot goes on. The same method can be the right one on another
BPMS the application is configured for, which is what the prioritized adapter list is for: a
workflow module served by the Process-Engine-API today and by Camunda 8 tomorrow keeps the methods
of both, and refusing to boot over the half which is idle would make the migration feature
unusable. It also fits what the other version findings do. A method serving no version a BPMS holds
is a warning too, for the neighbouring reason that the version may be deployed later.

The report is per BPMN process and it names the adapter, unlike the report about methods which
serve no version anywhere: what is stated here is true of ONE BPMS, so a method which runs on
another one is named without being called dead. Where a second adapter of the same process
registers a catalog, nothing is reported at all - the method runs on that BPMS, and the core has
nothing to warn about.

An adapter which registers no catalog and says nothing keeps the behaviour it had. Silence is not
an answer, and treating it as one would name methods of an adapter which was simply never asked.
`ProcessVersionMatchingTest.WithoutAVersionCatalog` holds both sides of that difference.

### 61. A profile file without its plain file is reported, not loaded

A workflow module may ship `loan-approval-prod.yaml` and no `loan-approval.yaml`. Measured on
2026-09-17: Spring Boot reads that file, Quarkus reads nothing of it. SmallRye pairs a
profile file with the file of the same name without the profile, in the same directory and
the same archive, so that the order the files are loaded in does not depend on which resource
a class loader answers with first. Where the second file is missing, the first one is never
looked for. The application starts either way and the first sign of the gap is a setting which
is not what the file says.

VanillaBP does not load the file itself. Registering it as a source of its own would make a
workflow module behave differently from every other piece of Quarkus configuration, and the
next reader of that code would have two rules to hold in their head instead of one. Instead
both platforms name the file at startup together with the file which would make it be read. An
empty file does the job, which is the smallest change an application can make. Quarkus finds the files while the application is built and writes the
line when it starts, so a native binary says it as the JVM does. Spring Boot reads the file, so
its line says that Quarkus would not: a workflow module is a library and the platform it ends
up on is not the one it was built on.

`WorkflowModuleProfileFileNeedsItsPlainFileTest` holds the rule this rests on, measured against
SmallRye 3.17.2. `ProfileFilesWithoutTheirPlainFileTest` holds which files are picked and what
the message offers, and one test per platform boots an application with such a module.

### 62. A payload lies beside the outbox entry and the entry names it

A phase-two call used to carry identifiers and nothing else. A sync to the Business Cockpit
wants to hand over the state the application saw at its sync point, and that is a payload.

The obvious place was the `ARGS` column, and it is the wrong one. Decision 22 and the story
behind it settled what that column is for: a correlation id names something, it does not
carry something. Widening it to a CLOB would put payloads into a column meant for keys, and
the same values would then travel through our own store and through MongoDB as well. The
limit is ours, not gruelbox's: gruelbox writes its invocation into a `TEXT` column and has
no such bound.

So a payload lies in a store of its own, one row per call which carries one, written in the
transaction which writes the entry and read back at the dispatch. The entry names the row by
`PhaseTwoCall.ARG_PAYLOAD_REFERENCE`, which is an identifier and therefore belongs exactly
where identifiers belong. The reference is added after the idempotency key was derived, so
no derivation rule ever sees it - a fresh reference per call would otherwise make every call
unique and deduplicate nothing.

A table of its own for the payloads, whichever store an application runs. Decision 47 weighed a
table of VanillaBP's own for a different question and refused it, because it would have added a
table to the one setup chosen for bringing none. That argument does not carry here: a call
without a payload writes no row, so an application which passes none keeps the setup it had, and
one which passes payloads has asked for the table.

*This entry used to go on with "one form for all four stores", and that sentence fell on
2026-09-24. It said that the way a payload is found and removed has to be the same everywhere,
which made the cheapest store pay what the most expensive one costs. Stephan decided the
opposite: every store may optimize how it sweeps, and where it is expensive it should be
expensive at that store alone. When that sweep runs, and how much of it fits, is decision 91.
What stays of this entry is the rest of it - the payload lies beside the entry and the entry
names it.*

*With the sentence went the refusal decision 76 recorded: on the same day Stephan decided that the
relational outbox gets a COLUMN for the reference, `PAYLOAD_REFERENCE`, with an index over it. The
reference still travels among the arguments, because that is where the dispatch reads it and
because it is an identifier; the column carries the same value in a shape an index reaches, which
is what the housekeeping asks. Measured on PostgreSQL 16.15 on 2026-09-25 with a hundred blocked
entries, one pass over the arguments took 2 ms against ten thousand dispatched entries beside them,
18 ms against a hundred thousand and 112 ms against a million, while the lookup over the column
took 1 ms at every one of those sizes; with ten thousand blocked entries the scan took 7.8 s and
the lookup 20 ms. The column is written after the idempotency key was derived, exactly as the
argument is, so no derivation ever sees it - that promise stands unchanged. An entry written before
the column existed carries it empty, and the startup fills it for the entries which still wait; a
dispatched entry is left alone, because its payload went with the dispatch and the history is the
part of the table which grows. gruelbox keeps the scan: its table is not ours.*

The price is named rather than hidden: one extra read per dispatch attempt of a call which
carries a payload, by primary key, and none at all for a call which carries none.

The bytes belong to whoever passes them. VanillaBP stores and returns them and reads nothing,
which keeps the format a matter between an extension and its own receiver. The size limit sits
in one place, `PhaseTwoCall.MAX_PAYLOAD_SIZE`, and is a mebibyte - wide enough for the state
of a workflow aggregate written as JSON, far below what MongoDB holds in one document and
below what a `BLOB` holds anywhere. It is enforced for every store, for the reason the
idempotency key is bounded at the smallest limit of the stores: an application must not
discover a narrower one by moving from one store to another.

A payload is removed where its entry is finished, which is the update marking the entry
dispatched. Removing it before that would leave an entry whose payload is gone, so the order
is fixed and errs towards keeping a payload too long. What a crash between the two writes
leaves behind is removed by an age sweep in the housekeeping of each store. Before it removes a
payload, the sweep asks whether an entry still names it (decision 76). The retention counts at
the entry, so a blocked entry keeps its payload for as long as its repair takes.

*This paragraph used to say that the sweep also removes "the payload of an entry blocked longer
than the retention". That stopped being true when the sweep began to ask the entries first, and
the half sentence was taken out on 2026-10-06.*

### 63. An entry which reports says which state it means, an entry which writes never does

*Withdrawn on 2026-09-17, see decision 64: the report is built at the event and travels
as the payload of decision 62, so nothing asked for an older state any more and the seam
went out again.*

An outbox entry is written in the transaction of an event and dispatched afterwards. The
aggregate it reads at the dispatch is the aggregate of that moment, not of the event.
Milliseconds while everything works, and days once a receiver is gone or a dispatch keeps
failing, which is when a report starts carrying values the event never had.

Which of the two states is right belongs to the single call, not to the application and not to a
setting. An entry which syncs the aggregate INTO the BPMS - a task completion or a push of changed
values - needs the state of now: the engine is where the case goes on, and writing a
day-old value back there is wrong in a way nobody notices for a while. An entry which reports to
somebody else - the Business Cockpit is the case this comes from - wants the state of its event,
because the sync points are set by the application and the report is about what it saw there.

So the choice sits on the call, `PhaseTwoCall#askingForTheStateOfTheEvent`, and VanillaBP's own
operations cannot make it: asking is refused for them where it is asked, with a message saying
why, instead of being ignored at a dispatch nobody watches. An entry which asks for nothing reads
what it always read and costs what it always cost - no history is read, which is what keeps this
cheap in an application which has an auditing and entries of both kinds.

The state is named by an id the application answers, through two defaults on
`AggregatePersistenceAware`: `getAuditingId` names the state an aggregate stands at, and
`loadByIdAndAuditingId` reads it back. Both defaults keep today's behaviour - no id, and a load
which ignores one - so an application without an auditing behaves as before, and one which has
an auditing writes the two answers its framework already has. What the id means is the
application's: an Envers revision, or a version an application keeps itself. VanillaBP carries it
and reads nothing in it, the way it carries a payload.

The id travels in the arguments of the entry, like the payload reference of decision 62 and for
the same two reasons: it IS an identifier, and every store persists the arguments already, so no
store learns anything new. It is added after the idempotency key was derived, because which state
a call wants to read says nothing about whether it is the same operation as another one.

Where the state is gone - the auditing cleaned it up while the entry waited - the current one is
read and a warning says so, naming the aggregate and the id. A report carrying newer values is
better than no report, and the line is what tells the two apart afterwards. Where the aggregate
itself is gone, nothing is said: there is nothing to fall back to.

This covers the business data and nothing else. What an adapter reads out of the BPMS while it
dispatches - the assignee of a user task, its candidates, its due date - is the state of the
dispatch, because it is the BPMS' data and the BPMS has no history of it that VanillaBP could
ask for.

It is the second answer to the question decision 62 answered first, and the two do not exclude
each other. A payload is exact and needs no auditing, and it costs space. Loading the aggregate
as it was costs no space and needs an auditing, and it reconstructs rather than remembers. An
extension which can pass a payload passes one; this is for the case where the state is large or
expensive to build.

### 64. A report is built at the event, so the platform asks the application for no older state

Decision 63 gave the platform a seam: an outbox entry could say that it means the state of its
event, the application answered an id for that state and read it back at the dispatch. It was
built for one reader, a report to the Business Cockpit which is written at an event and sent
later.

The premise fell on 2026-09-17. A report does not have to be built at the dispatch at all.
Everything it carries is there at the event which triggers it, so it is built there and travels
as the payload of decision 62. What the BPMS knows about a user task is a copy of what the
application decided. The truth stays in the application, and a copy taken at the event is the
copy the event had.

What was measured that day:

- The Process-Engine-API adapter already builds the whole prefill when the task is delivered,
  stores it and reads only from that store when it sends.
- The Camunda 7 cockpit listeners are built-in task listeners running inside the engine command,
  where every field of the prefill is at hand and no query is needed.
- `ActivatedJob.getUserTask()` of the Camunda 8 client 8.9.6 carries assignee, candidates,
  `dueDate` and `followUpDate`.
- A `UserTaskEvent` with every field filled is about 1078 bytes as JSON. The payload limit is a
  mebibyte.

That left the seam without a caller in any of the ten repositories of the workspace. An additive
SPI which nobody calls still costs documentation and tests, and everyone who reads the interface
has to be told what it is for. So it goes out rather than staying as a switch nobody flips.

Removed with this: `PhaseTwoCall#askingForTheStateOfTheEvent` and the auditing id it carried,
`PhaseTwoRequest#auditingId`, the two defaults `getAuditingId` and `loadByIdAndAuditingId` on
`AggregatePersistenceAware`, the two defaults on `AggregateServiceContext`, and the load by id
plus the fallback warning in `MigrationProcessService` and `ExtensionAggregateServiceContext`.

Decision 62 stays and now carries this case alone. Auditing itself stays a matter of the
application: the blueprint `persistence-audited-aggregate` shows how an application revisions its
aggregates and reads an old state back, and VanillaBP takes no part in it.

Nothing is written in `UPGRADE.md`. The seam never reached a release. It lived one day in a
snapshot, so there is no step from version 1 to describe.

### 65. Four places for a module file, and the same file may lie in one of them

Spring Boot read a workflow module's configuration at four places, Quarkus at two. Measured on
2026-09-17: a module which ships only `config/loan-approval.yaml`, or only
`loan-approval/config/loan-approval.yaml`, is read on Spring Boot and nowhere on Quarkus. A
workflow module is a library and the platform it ends up on is not always the one it was built
on, so the same jar carried settings which apply on one and are missing on the other.

Quarkus reads the same four now:

```
loan-approval.yaml
config/loan-approval.yaml
loan-approval/loan-approval.yaml
loan-approval/config/loan-approval.yaml
```

The four are styles, not a ranking. Somebody who keeps the module's configuration next to its
BPMN files should be able to, and so should somebody who collects configuration in a `config`
directory. VanillaBP prescribes neither. The alternative was to report the two places Quarkus
lacks and leave them unread there, and it would have made the choice of a style into a choice
of a platform.

There is no order among the four, and none is needed, because **the same file may lie in
exactly one of them**. A module which ships it twice ends the boot with a message naming both
places. A ranking would mean a setting whose source cannot be seen from the outside: two files,
one of them silently ignored, and nothing in the log to say which. The check runs on both
platforms. Spring Boot read the four places before this story and let one of two files win
without a word, which is the same defect, only older.

The list lives once, in `WorkflowModuleConfigFiles` of the core, so the two platform
integrations cannot drift apart. Quarkus finds the files while the application is built and
ends the boot when it starts, so a native binary refuses as the JVM does.

The profile rule of decision 61 applies per place: a file carrying a profile is read only where
the file without the profile lies next to it, and with four places there are four spots where
that bites. Nothing about that rule changed, it just has more places to apply to now.

`WorkflowModuleConfigFilesTest` of the core holds the four places and the message.
`WorkflowModuleConfigLocationsTest` holds what the Quarkus config sources read, one test per
place. `ModuleFileInEachOfTheFourPlacesTest` holds the same for Spring Boot, and
`TheSameFileInTwoPlacesTest` of each platform holds the refusal.

### 66. Sharing a whole workflow aggregate is allowed at the workflow and nowhere else

An aggregate which carries no `@NoSyncWithBPMS` anywhere hands every attribute it reaches to the
BPMS, at every sync point, and decision 10 says why those values are pushed at all. The price of
that starting point is the application which never thought about it. It writes an aggregate,
annotates nothing, and the card number travels to a cluster outside the application together with
the rest. Nobody chose that. It is where an application lands by doing nothing.

So the startup ends there. `FullSyncCheck` asks the sync model, once per registered workflow, what
the aggregate gives away if nothing at all is held back, and the message names the workflow, the
aggregate and those attributes. It offers two ways on. The first is to tell the aggregate what the
models really need, which is the recommendation anyway. The second is a permission at the
workflow:

```
vanillabp.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.allow-full-sync-with-bpms: true
```

The permission is read at the workflow and nowhere else. That is a deliberate exception from the
resolution over four levels of decision 7, and the reason is what an inherited permission would
do: it covers the workflow somebody adds to the module next week, and that is the workflow nobody
looked at. The key is bound at the application, at a workflow module and in an adapter section as
well, but only so that a line written there is answered with a message naming the place it
belongs. Ignoring it would leave somebody believing the permission was given.

What "everything" means is asked of the code which later decides what is written, so the check
cannot invent an answer of its own. The aggregate's ID attribute is left out of it: that value
reaches the BPMS whatever the sync model says, so an aggregate made of nothing but its ID gives
nothing away and starts without a permission. The persistence names that attribute, and where it
does not, the conventional name `id` stands in - an aggregate must not be refused because nobody
could name its ID. A secondary BPMN process is not asked either. It
runs on the workflow of the primary process, and the primary id is the one the configuration
knows.

The cost is one failed startup for every application which upgrades from version 1 without having
annotated anything, and that was weighed against a warning. A warning is read once and then
scrolls past, and what it is about is data leaving the application. `UPGRADE.md` names the
property so the upgrade does not have to begin with a startup error.

### 67. A delivery is routed by both wiring keys, because the boot accepts both

A `@WorkflowTask` method is wired by one of two keys and says which: `taskDefinition` names what
the BPMS subscribed to, `id` names the element of the model. The wiring validation accepts either
of them while the application boots. The routing of a delivery asked with one value only, so a
method wired by the element id was accepted at deployment and not found at the first delivery.

Measured on all three engines. On Camunda 7 a service task wired by `camunda:delegateExpression`
whose method names the element id deployed without a word and then ran into
`No @WorkflowTask method ... matches task definition ...`, retried by the job executor into an
incident. On Camunda 8, on cluster 8.9.19, a job type served by an id-wired method was activated
by the adapter and refused by the core, three attempts and then an incident. The
Process-Engine-API has the same shape, read rather than measured. So the defect was never one
adapter's.

Two ways were open. The deployment could refuse such a wiring with a message saying how to wire
it instead, or the routing could learn the second key. The routing learns it, for three reasons.
It is what a reader expects from `@WorkflowTask(id = ...)`, and the wiki of the Camunda 7 adapter
promises it. The deployment already treats the two keys as one pair, so refusing at boot what the
same check accepts would need the check to grow a rule instead of the routing losing one. And a
refusal would take away the wiring an application uses where the task definition is not stable,
a Camunda 7 expression above all.

So `TaskInvocationContext.getBpmnElementId()` is no longer only a field of the delivery record: the
routing reads it, and an adapter which leaves it `null` can serve a method wired by the element id
only where the one value it reports IS that id. The author guide says so, and the comparison of
the element id against the reported task definition stays for exactly that adapter.

The refusal message names both keys now. A reader who only sees the task definition looks for a
method under a name their model does not carry, which is what made the Camunda 8 measurement take
a cluster to understand.

`WorkflowTaskRoutingTest` holds both wirings, the adapter which names no element, and the message.

### 68. The youngest call replaces the one still waiting, and only where it says so

The outbox deduplicates a call against the entries still waiting for their dispatch, and the
older entry used to win: the younger call was dropped and `schedule` answered `false`
(decision 22). That was right as long as a call carried the intention and the dispatch read
the state fresh. The entry which waited would have read the same state a moment later, so
which of the two survived made no difference.

Decision 62 changed what a call is. A call may carry the state its caller saw, and the
Business Cockpit will do exactly that: the report is built at the event and travels with the
call. Now the older entry holds the older report, and dropping the younger one means a user
sees the first state of a step instead of the last. The worse the backlog, the more reports
wait and the further behind the one is which survives. So the direction turns: the youngest
call takes the waiting entry's place, payload included, in the transaction it was planned in.

The key stays what it is. Putting the process step into it was the other way to think about
this, and it produces the flood the collapsing was meant to prevent: every step would be an
entry of its own, and a cockpit which stood for ten minutes would hand the user ten messages
per workflow. While the outbox keeps up, a step's report is dispatched before the next one is
planned anyway, so the key never collapses anything a user wanted to see.

The call says the word, not a setting. `PhaseTwoCall.replacingWhatIsStillWaiting()` marks one
call, and nothing existing says it, so nothing existing changes. A setting would decide for
callers whose author never thought about the question, and it is not a matter of taste:
whether an older state may win follows from whether the call carries a state at all, which
only its author knows. The mark belongs to the planning and not to the entry, so no store
persists it, and it is added after the idempotency key was derived, where no derivation rule
can see it.

Only an extension may ask for it. What VanillaBP plans itself writes into the BPMS and reads
what it needs at dispatch time, so a younger call has nothing to bring along for it. Asking is
refused where it was asked, with a message naming the operation.

An entry a dispatch has taken is not replaced. It runs to its end and the younger call becomes
an entry of its own, which takes no part in the deduplication of that key, because the key
belongs to the entry on its way. Two reports then reach the handler where one was asked for.
That is the price of never taking work away from a dispatch which may have reached its
receiver already. A receiver which cannot take two reports of one state keeps the younger one
by its timestamp, which is what the Business Cockpit does.

**The payload of the replaced entry is removed in the same transaction, and the rule about a
claimed entry is what makes that safe.** An entry no dispatch has taken is an entry whose
payload no dispatch has read, so removing it takes nothing away from a reader, and a rollback
takes the removal with it. Leaving it to the age sweep was the alternative and it is the wrong
one here: the backlog this story is about would pile up an orphan of up to a mebibyte per
replaced report and keep every one of them for the retention, seven days by default.

Each store recognises a claimed entry by what its own dispatcher writes. The two stores
VanillaBP wrote itself write a lease when they claim an entry and count the attempt when it ends
(decision 79). So an entry with no attempt and no running lease is one nobody has read, and the
update which replaces carries both conditions. That makes it the same optimistic lock the claim
is. On the JDBC store the claim now reads its row once more,
because the row may have been replaced since the select of the due entries, and dispatching
the entry as it read then would hand the handler a payload reference which is gone. MongoDB
needs no such read: its claim is one atomic `findOneAndUpdate` and answers with the document
as of that moment.

Gruelbox has no API for replacing, so there the row goes: the waiting entry is deleted and the
younger call is scheduled under the same `uniqueRequestId`, in the caller's transaction. Two
things have to agree that no dispatch holds it. `version = 0` is gruelbox' own optimistic lock
and covers every entry a flush picked up, on any instance. A commit, though, submits its entry
straight away and writes nothing, so the row still reads as untouched while gruelbox holds it
with `SELECT ... FOR UPDATE`, and a delete meeting that lock would make the application's
transaction wait for a remote call - which is what an outbox exists to prevent. That case is
asked of a register the submitter keeps, which is where gruelbox already hands every entry
over before it invokes anything. The register answers for its own instance. What it leaves is
one instance dispatching an entry while another replaces it, which means two instances writing
one workflow at once, and VanillaBP names that the application's own business anyway.

A store which never learned any of this says so.
`PhaseTwoOutbox.scheduleReplacingWhatIsStillWaiting` has a default which discards the call the
way that store always did and writes a WARN naming its class. Without it a store written
outside VanillaBP would keep the older state quietly: the call went through, the handler was
called, and only the state was wrong. That is the one ending this must not have.

Nothing is written in `UPGRADE.md`. The outbox of version 2 has not reached a release, so
there is no behaviour a version-1 application could be upgrading from.

### 69. A business key counts only where it carries the workflow aggregate's id

VanillaBP names a workflow by its workflow aggregate and by nothing else. A BPMS which keeps a
business key of its own gets that id written into it wherever VanillaBP starts the workflow:
Camunda 7 has done so from the beginning, and Camunda 8 does it from cluster 8.9, the first line
with a `businessId` on an instance. A workflow started past VanillaBP can carry a key somebody
else chose and the variable with the aggregate's id as well, and then two values say different
things about one instance.

Nobody noticed that before, and the reason is worth writing down: **there was no channel for a
business key at all.** Every inbound contract named the workflow with a single string, and the
javadoc of `TaskInvocationContext.getWorkflowAggregateId()` equated "the Camunda 7 business key"
with "the Camunda 8 aggregate-ID process variable". An adapter whose BPMS keeps a second value had
nowhere to put it. So this is an addition to the adapter SPI, not the repair of a check which was
wrong.

The rule is one sentence: a business key counts only where it carries the workflow aggregate's id.
A key which says something else is not a second identity, it is a defect in whatever started that
workflow.

Two places hand a workflow over together with the aggregate's id, and the check sits at both. A
task delivery and the notification that a workflow ended both report that id, so the comparison is
free. `WorkflowTaskRegistry.invokeWorkflowTask` and `WorkflowTaskRegistry.workflowEnded` ask for it
before they do anything else with what the BPMS handed over.

*This entry named a third place until decision 98: a start the BPMS performed on its own, where the
key the instance already carried was held against the id VanillaBP gave the aggregate inside the
transaction the start opened. That comparison is gone, because the application names the workflow
at such a start and there is no second value left to compare. What the BPMS holds IS the name
VanillaBP looks the workflow up by, so a start is refused where no workflow aggregate carries that
name, which is decision 98's own rule rather than this check.*

The election is not a third place. The awareness probes send the aggregate's id out and get a
`WorkflowAwareness` back, so a workflow another adapter now holds is taken over at its next
delivery, which is the first of the two. The same holds for a workflow taken over from
version 1.

What a disagreement produces is a refusal, and VanillaBP raises no incident of its own. It ends
the invocation the way it ends one for a task nobody serves, and each BPMS then applies what
it applies to any failing handler: Camunda 7 retries the job as configured and raises an incident
afterwards, Camunda 8 counts the job's retries down and raises one afterwards, and behind the
Process-Engine-API it is that engine's business. The outcome is the same everywhere. The workflow
does not move on an identity VanillaBP cannot vouch for, and the message names both values, the
workflow module, the BPMN process, the adapter and the BPMS' own instance.

What says nothing does not contradict. A deviation needs two values which both say something and
disagree, so a missing business key, a missing aggregate id, or both, are silence.

An absent business key is the state of every Camunda 8 workflow up to cluster 8.8, of every
workflow on the Process-Engine-API, and of every workflow a timer started.

An absent aggregate id is the sentence which keeps the upgrade from version 1 working, and it is
the one somebody could "tighten" away later. Version 1 on Camunda 7 wrote NO process variables at
all, because the model read the workflow aggregate directly. A workflow which was running when the
application was upgraded therefore carries its identity in the business key and nowhere else, so
an adapter which reads the id from the variable finds none. If a missing variable counted as a
deviation, every migrated workflow would run into an incident at its first delivery, which is the
worst way an upgrade can break. The rule is written so that this is silence rather than conflict,
and `aWorkflowTakenOverFromVersionOneIsNotADeviation` holds it.

There is no way to switch the check off. Two values naming one instance differently are a defect
in the integration rather than a matter of taste, so a property would only let an application
keep running on an identity nobody can vouch for. There is nothing to trade off either: the check
compares two strings the delivery already carries, with no question to the BPMS.

`BusinessKeyIsTheAggregateIdTest` holds both places, the empty key, the key which carries the id,
and that a start refused for its name leaves no aggregate behind.

Nothing is written in `UPGRADE.md`, and the reason is sharper than "version 1 filled the business
key from the aggregate's id as well". A version-1 instance on Camunda 7 carries a business key and
no variable, and the rule reads that as silence rather than as conflict. Version 1 on Camunda 8
had no business key at all. So no workflow which ran under version 1 becomes a refusal here, on
either engine.

### 70. On Camunda 7 the business key is the only name, so nothing there can disagree

Decision 69 gave the adapter SPI a channel for a business key and a check which refuses a key
saying something other than the workflow aggregate's id. Reading the adapters afterwards showed
that the check cannot reach Camunda 7 at all, and that this is right rather than a gap.

Camunda 7 keeps one name and VanillaBP uses it. `Camunda7ProcessService` writes the aggregate's id
into the business key at the start, and `Camunda7WorkflowTaskBehavior`, `Camunda7UserTaskEventListener`
and `Camunda7TaskELResolver` read that same key back as `getWorkflowAggregateId()`. The adapter
writes no separate variable for the id either: `sharedValues` carries the attributes the sync model
shares and nothing else. So the key is not a second claim about the instance, it IS the id, and
`getBusinessKey()` stays at its default of `null` there. The check sees an empty key and returns,
which is what it should do.

What follows is where the case goes instead. A workflow somebody started past VanillaBP on
Camunda 7 carries the key that somebody chose, VanillaBP reads it as an aggregate's id, and there
is no aggregate of that name. That is the same shape as a start the engine performs on its own,
and it is decided by the rule Stephan set for it: an own start is recognised by the fact that the
id has no aggregate. Camunda 7 therefore needs no work for decision 69, and the foreign-start case
belongs to that rule and to the story which builds it.

*Decision 98 changed that rule. A name no workflow aggregate carries is refused now, and a start the
engine performs on its own is one which arrives without a name. A key somebody chose on Camunda 7 is
therefore refused at the start rather than read as an aggregate's id.*

One risk stays and is accepted with open eyes. A key somebody chose which happens to look like an
id an aggregate already has is attached to that workflow without a word. A key comparison would
have caught it, and on Camunda 7 nothing can, because catching it needs two values and there is
one. Giving Camunda 7 a second value, an aggregate-id variable next to the key, was weighed and
dropped: it is a new promise rather than a repair, it costs a variable at every sync point, it
departs from what version 1 did, and it would only report earlier what the missing aggregate
reports anyway.

Where the check does work is a BPMS which keeps both. Camunda 8 has a `businessId` on an instance
from cluster 8.9 on and carries the aggregate's id in a process variable, so both values exist
there and the comparison has something to do. The adapter reports neither today, on any line, so
the check is dormant until the lines which can carry the value, 8.9 and 8.10, report it. The wiki
says so on both sides rather than describing the end state as if it were here.

### 71. Parts which do not belong together stop the boot, and an unknown pair only warns

An application runs the platform integration, one or more BPMS adapters and its extensions, and
all of them are released at their own pace. A dependency update therefore produces pairs which
were never built and never tested together, and neither Maven nor Gradle says a word about it: a
version the application manages wins over the version a dependency asks for, even if that means a
downgrade. The failure arrives much later, as a `NoSuchMethodError` or a `NoClassDefFoundError`
somewhere which looks unrelated to the update. We have had this in our own house, where an old
adapter snapshot against a newer platform took the blueprint CI apart.

The rule is comparison against two numbers, not a list of known pairs. Every part says which
platform integration it was built against, and the platform integration says the oldest part it
still serves. A part built against a newer platform integration than the one it runs on stops the
boot, and so does a part older than the oldest one served. Everything in between starts. A list of
pairs was dropped because it would have to name versions which do not exist yet, and because every
release of any part would have to touch it.

The oldest part served is one maintained number, `vanillabp.oldest-part.version` in the root
`pom.xml`. Raising it is a decision of its own, taken when a release drops something adapters
older than that relied on, and it ends the boot of every application still using such a part.

What we cannot judge only warns, once per part, and says that this pair is unknown: a part which
ships no version descriptor, one whose descriptor is incomplete, and versions which cannot be
compared, which is what a build of one's own looks like. Refusing to start there would punish
setups we have no reason to distrust, and saying nothing would hide the one hint the developer
gets when the runtime failure finally arrives.

An extension is judged by the name it gives itself, and one which gives none is not judged. The
boot could only name the class behind it, and the developer reading the log cannot act on a class
name of somebody else's library. An adapter always has a name, its adapter type, so this gap is
the extension's alone and closes as soon as the extension answers.

The mechanism lives once, in `integration-spi`, which is the module both adapters and extensions
depend on. The core judges every adapter and every extension at the start of the deployment, and
on Quarkus the extension judges them again while it builds, which is earlier still. An adapter
also asks for itself in its constructor, and that is not a repetition: a platform integration
older than the check cannot contain the check, so the part is the only one able to report a
platform which is too old.

The numbers travel as a properties file per part, filled by resource filtering, and not as the
JAR manifest or `META-INF/maven/.../pom.properties`. A file of our own survives a Spring Boot fat
jar and a shaded jar, and its name carries the part, so several adapters on one classpath do not
overwrite each other. In a native image it survives because the Quarkus extension registers every
descriptor it finds while building.

### 72. Closing a task closes every record naming it, and the count is what claims it

`TaskDeliveryLog.markTaskClosed` said nothing about how many records it stamps, and the four
stores disagreed: the SQL statement had no row limit and closed all of them, while the two
MongoDB stores used `updateFirst` respectively `updateOne` and closed one.

A task can carry more than one record, so the difference is real. The record of a delivery is
keyed by the event as well, so the delivery which handed the task to the application and the
delivery which reported its cancellation are two records naming one task. A BPMS which hands the
same task out under a new job key produces two as well.

All four stores now close every record naming the task. A record left open is what keeps a task
alive for everything which reads the open work: the read of the open tasks of a workflow, the
election of a later operation and the retention. Closing one row and leaving the other would show
a task which is over as waiting, and the derived cancellation would then deliver it a second time.

The number a store returns is what it closed in that call, and that is a second promise rather
than a statistic. Two application instances deriving the same cancellation both call this, and
only the one which reads a number above zero delivers the event. The compare and set is the whole
claim: on SQL the second update waits on the row lock and reads zero afterwards, on MongoDB the
second write conflicts and the retry finds the filter no longer matching.

A record which was closed before keeps the moment it was closed at, which is why the filter
demands an absent closing moment. The age of an open task is measured against such a fixed moment
elsewhere in the same record.

### 73. The end of a workflow cancels what it was waiting for, whatever kind of end it was

An adapter which names the workflow in its end notification lets the core derive a cancellation
for every task it still believes is open in that workflow. The kind of the end does not decide
whether it derives.

The reason is measured. On Camunda 8 a terminate end event and an interrupting event subprocess
end the instance as COMPLETED rather than as a cancellation, and both of them can take an open
task away on the way out. Reading the kind first would skip exactly those two, which are the
cases the derivation exists for.

The price is a race. A task the application completed a few milliseconds ago is gone as well, so
an end which arrives in the window between the dispatch of the completion and the mark on the
record reads a completed task as canceled. The compare and set of `markTaskClosed` shrinks that
window and does not close it. We take the race rather than a second index, because the outbox
offers no read by task id and a task reported as canceled once too often is the smaller harm than
a task the application never hears about.

What decides is the workflow id, and that is the adapter's call. An adapter whose BPMS cancels
each element by itself names none and nothing is derived for it: Camunda 7 fires an END execution
listener per element, process termination included, so a derivation on top would report the same
task twice. Camunda 8 fills it.

A derived cancellation carries no job of the element, so a `@TaskParam` and a multi-instance value
reach the method as `null`. The boot names the methods which really declare one, and says nothing
about the rest. A value which is silently absent is what an application finds out in production;
a warning on every derived delivery would be noise for the models nobody cancels.

### 74. The probe of an open task has three answers, and only "gone" cancels

Whenever a BPMS hands the application a job, the core looks at the other tasks it still
believes are open in the same workflow and asks the adapter whether they still exist. That
question is answered with `GONE`, `STILL_THERE` or `CANNOT_SAY`, and a cancellation follows only
on the first one.

The third answer is the whole point. An adapter which cannot tell a refusal from an outage would
otherwise have to guess, and a guess in the "gone" direction cancels every open task of an
instance whenever the engine hiccups. The Process-Engine-API is the case we have: it probes with
a `PREFLIGHT_CHECK` completion, its API has no typed exceptions, and every failure there means
the same thing to the caller. Such an adapter answers `CANNOT_SAY` and nothing happens, which is
exactly what happens on `STILL_THERE` - the two differ in what they mean, not in what follows,
so being honest costs an adapter nothing.

An adapter which supplies no probe at all keeps behaving as it does today. That is what makes the
whole mechanism additive for every adapter written against the current SPI.

`WorkflowAwareness` is not this probe and must not be reused as one. It answers the election's
question - which of the configured BPMS holds this task - and folds "not mine" into
`UNKNOWN_TO_BPMS`, which read as "gone" would cancel the open work of a workflow whenever the
wrong adapter is asked.

The check is on by default, against the rule that defaults stay compatible with version 1. What
changes is that a method carrying `@TaskEvent(CANCELED)` starts being called where it never was,
and only such a method is affected: the wiki told people the event never arrives on a remote BPMS,
and that sentence is what was wrong. The property
`vanillabp.delivery.check-open-tasks-on-delivery` switches it off for an application which does
not want the new calls, and `vanillabp.delivery.max-open-tasks-checked` caps the round trips per
wake-up at ten until somebody measures a better number.

### 75. One outbox for every relational database, and the aggregate decides which thread dispatches

On a relational database every platform runs the same outbox: the store and the dispatcher in
`migration-adapter/runtime`, written against a JDBC connection. Spring Boot used gruelbox for it
and Quarkus a copy of its own, so one promise had two implementations, and a defect found on one
platform had to be looked for twice. What stays platform-specific is the transaction: Spring Boot
binds its connection with `DataSourceUtils` and Quarkus enlists an Agroal one in the running JTA
transaction, which is what `PhaseTwoOutboxTransaction` and `JdbcConnectionAccess` carry.

Working against a third-party outbox as long as possible was deliberate, and it did its job: it
kept us from building things which only work with an outbox of our own. That job is done, so the
swap happens in 2.0. Gruelbox stays available for an application which already runs it, and
nothing in the platform depends on it any more. It takes its own table with it, so an upgrade which
still has entries there is told at startup rather than losing them quietly.

*The key `vanillabp.outbox.gruelbox.enabled` named here first is gone. Since decision 102 the
gruelbox store has a repository of its own, and an application opts in by adding its artifact.*

The entries of one workflow aggregate are dispatched by one thread, and the aggregate decides
which of them (`DispatchLanes`, `vanillabp.outbox.dispatch-threads`, four by default). Until now
every store dispatched on a single thread per node, so an application which completed tasks from
twenty job executor threads under version 1 handed every completion to one dispatcher here. A pool
giving the next free thread the next entry would fix the throughput and break the order: two
operations of one workflow would reach the BPMS the wrong way round, and nothing downstream would
notice until a customer did. The number of threads is bounded because an unbounded one only moves
the limit into the connection pool, where it is harder to see.

What this does not order is a FAILED entry. It waits for its backoff, and the next entry of the
same aggregate passes it in the meantime - which is what a single thread did as well, because a
failed entry goes back into the table either way.

The MongoDB stores keep dispatching on one thread. They are a store of their own on both
platforms, the lanes are not tied to JDBC, and the work belongs to the story which measures
whether they need it.

*Decision 77 did that measuring and gave the MongoDB stores the same lanes.*

The entry above which this changes the premise of is 47: gruelbox is no longer what most
applications run, so the store which cannot name the adapter ids of its waiting entries at a
start is now the exception rather than the default. What decision 47 decided - that such a store
says it at the first dispatch instead - is unchanged and still holds for gruelbox.

### 76. The orphan question runs over an index where the store can index a field, and over a scan where it cannot

Before the housekeeping removes a payload by age it asks the entries whether one of them still
names it. Decision 62 put that reference among the arguments, and the retention counts at the
entry, so age alone does not say a payload may go. On a healthy store the question is never asked,
because a payload goes with the dispatch of its entry. An entry which is stuck keeps
its payload, so one stuck entry means the question is asked at every run of the housekeeping.
That used to be every poll, every ten seconds by default; since decision 91 it is the night window.

That was measured in September 2026, and the result is not the same on both kinds of store.

On MongoDB the reference is a field inside the entry document, so an index reaches it. A sparse
index over `args.payloadReference` is created with the other indexes of the collection, and the
question is answered from it. Measured against MongoDB 8.2 with a hundred stuck entries, the
question took 4 ms with ten thousand dispatched entries beside them, 35 ms with a hundred thousand
and 341 ms with a million, while the index answered in 0 to 2 ms at every size. Sparse, because
only an entry which carries a payload has the field: in a collection of twenty thousand entries of
which two hundred carried one, the index held 20 KB. The write pays for it, and the measurement
could not tell that cost apart from the run-to-run spread of the same write: four indexes took 149
to 183 microseconds per insert and five took 162 to 202, which says the index is well below what
the round trip of one write costs.

On a relational database the reference lies inside a column of text, and no index reaches into it.
The only index there would be an index over a column of its own, and that column is what decision
62 refused: the reference is an identifier and travels where identifiers travel, in one form for
all four stores, and gruelbox owns its table so a column there was never possible at all. So the
JDBC stores keep the scan, and what it costs is written down here rather than left to be
discovered: with a hundred stuck entries the pass took 121 ms against ten thousand dispatched
entries, 413 ms against a hundred thousand and 4.1 s against a million, on PostgreSQL 16.15. It
gets worse than linear when the stuck entries themselves pile up, because the payload store asks
in chunks of a hundred and every chunk is a scan of its own: a thousand stuck entries took 1.4 s
and ten thousand took 47 s.

Those last numbers are the price of keeping one form, and they are a price nobody pays while the
outbox is healthy. Whether they are worth a column in `VANILLABP_PHASE_TWO_OUTBOX`, for the three
stores which could carry one, is a question about decision 62 and belongs to whoever reopens that
one. Until then an application which finds its housekeeping slow has the same fix it always had:
repair or remove the entries which are stuck.

*Stephan reopened it on 2026-09-24 and the column was built: `VANILLABP_PHASE_TWO_OUTBOX` carries
`PAYLOAD_REFERENCE` with an index over it, and decision 62 records what that changes and what it
measured. Two things about the numbers above went with it. The question is no longer asked in
chunks of a hundred - it is one condition inside the delete which removes the orphans, so the row
of a thousand blocked entries is no longer 1.4 s but 100 ms, and ten thousand are 7.8 s rather than
47 s. And on the relational stores which own their table the question is now a lookup: 1 ms
wherever the scan was, 20 ms where it was 7.8 s. What stays of this entry is gruelbox, which owns
its table and therefore keeps the scan, and the shape of the argument - a question which grows with
the table is a question decision 19 forbids, and the column is what took it out.*

A payload the housekeeping did remove is said at DEBUG with its count, and nothing is said when
there was none. An orphan means a payload was written and its entry never was, so the count is
zero unless a process died between those two writes, and a line which is always zero teaches a
reader to stop reading it.

### 77. The MongoDB stores dispatch on lanes too, and their claim reads the oldest entry first

Decision 75 left one question open: whether the MongoDB stores need the dispatch lanes the
relational store got, or whether an application on MongoDB is held up by something else
anyway. A store nobody runs under load should not get a second dispatch stage, because such
code only ages. So the question was measured before it was answered.

The measurement repeats what decision 75 was taken on: 200 outbox entries of 40 workflow
aggregates, all due at once, with a handler which takes 20 milliseconds because that is what a
call to a BPMS costs. It ran on 2026-09-24 in the development container of this repository,
against MongoDB 8.2 in a container, with the outbox' own settings at their defaults.

| lanes | Spring Boot | Quarkus |
|-------|-------------|---------|
| 1     | 4885 ms     | 4793 ms |
| 2     | 2393 ms     |         |
| 4     | 1291 ms     |         |
| 8     | 703 ms      | 840 ms  |

The relational store, measured the same way on H2 in memory on 2026-09-21, needed 4498 ms on
one thread and 802 ms on eight. So the MongoDB stores have the same shape: what one thread
spends there is the wait for the BPMS, and nothing about MongoDB moves that wait somewhere
else. Both stores therefore dispatch the way the relational one does, on
`vanillabp.outbox.dispatch-threads` lanes keyed by the workflow aggregate.

What the lanes needed on top is a claim which reads the oldest entry first. The lanes keep the
order they are handed the entries in, and a collection answers in an order of its own - the
relational store has ordered its select by the moment an entry was written since it had lanes.
Without that sort two operations of one workflow would reach the BPMS the wrong way round as
soon as an attempt has moved a due time, and a load measurement would applaud it, because
reordering is faster. The index over the status and that moment already exists, so the sort
costs nothing.

The numbers above are a statement about a measured past, not a promise. They say what a
dispatch stage which waits for somebody else does with more threads, and they say nothing
about a handler which is busy rather than waiting, or about a database under load.

### 78. All three tables explain their name the same way, and the generated SQL keeps the default

`vanillabp.outbox.jdbc` named the outbox table and the payload table. The table of the
delivery log had no key at all, so an application with naming rules in its database could
rename two of its three tables and not the third, and nothing said why. On MongoDB all
three collections had their key since the delivery collection got one.

So the delivery table gets its key as well, `vanillabp.outbox.jdbc.delivery-table`. It does
NOT follow a renamed outbox the way the payload table does. The payload table belongs to
one outbox and is house-kept with it, while the delivery log is a store of its own which
answers a different question, and a name derived from the outbox would rename it behind the
application's back.

The schema artifact takes the same name: the changelog property `vanillabp.delivery.table`
was fixed before and is now overridden like the other two. The generated Flyway files are
the part which cannot follow. They are generated while `io.vanillabp:vanillabp-schema` is
built, and nobody knows an application's names then. An application which renames a table
therefore applies the changelog with Liquibase, or edits the generated statements before it
applies them. That is the answer the outbox table and the payload table already gave, so
the three are alike again rather than one being worse off.

Not chosen: leaving the name fixed, which keeps the imbalance and the question with it, and
letting the key count only where `vanillabp.outbox.create-schema` is `false`, which would
have made one property mean two things.

### 79. A node claims what its lanes are working on, and one thread renews those leases

A claimed entry renews its lease from the claim until its dispatch is over, one write per
entry and tick, and the tick is a third of `vanillabp.outbox.attempt-frequency`. Since the
lanes a node claimed much more than it was dispatching: every lane took sixteen entries into
its queue, so eight lanes meant 136 claims and 136 writes every tick. With an
attempt-frequency of half a second that is more than eight hundred writes a second, on the one
thread which renews. Where that thread falls behind, a lease runs out while its entry is still
waiting in a queue, another poll takes the entry over and the operation is carried out twice.

Two ways out were measured on both stores: more renewal threads, or a node which holds fewer
entries. The second one wins, so a lane now takes ONE entry beyond the one it dispatches
(`DispatchLanes.ENTRIES_WAITING_PER_LANE`). A node then holds two claims per lane plus the one
the poller is holding out, the renewals per tick are the number of lanes, and the rest of the
backlog stays in the table, where another node can take it and an operator can read it.

The measurement ran on 2026-09-24 in the development container of this repository, against
MongoDB 8.2 and PostgreSQL 16 in containers, with eight lanes and an attempt-frequency of half
a second. Three shapes, each run with the deep queue and the queue of one, one after the other
in the same JVM so both meet the same machine. The machine was busy with other work at the
time, which is why the durations of one setting vary as much as they do; what the measurement
is about are the two other columns.

MongoDB, and the writes counted are the renewals alone:

|                    shape                     | queue |       duration       | renewals  | delivered twice |
|----------------------------------------------|-------|----------------------|-----------|-----------------|
| 200 entries of 40 aggregates, 20 ms handler  | 16    | 853 / 684 / 670 ms   | 221-280   | 0 / 0 / 0       |
| 200 entries of 40 aggregates, 20 ms handler  | 1     | 694 / 671 / 685 ms   | 200       | 0 / 0 / 0       |
| 200 entries of 40 aggregates, 600 ms handler | 16    | 18.1 / 22.1 / 22.1 s | ~7500     | 0 / 3 / 8       |
| 200 entries of 40 aggregates, 600 ms handler | 1     | 18.1 / 18.2 / 18.2 s | ~1250     | 0 / 0 / 0       |
| 2000 entries of 400 aggregates, 20 ms        | 16    | 13.1 / 11.1 / 10.0 s | 2000-2779 | 0 / 0 / 52      |
| 2000 entries of 400 aggregates, 20 ms        | 1     | 19.1 / 7.4 / 6.7 s   | 2000-2003 | 1 / 0 / 0       |

PostgreSQL, where the number counts every updated row, so two of them per entry are the claim
and the mark:

|                    shape                     | queue |   duration    | rows updated | delivered twice |
|----------------------------------------------|-------|---------------|--------------|-----------------|
| 200 entries of 40 aggregates, 20 ms handler  | 16    | 2179 ms       | 403          | 0               |
| 200 entries of 40 aggregates, 20 ms handler  | 1     | 2225 ms       | 400          | 0               |
| 200 entries of 40 aggregates, 600 ms handler | 16    | 22.1 / 18.6 s | 3550 / 2855  | 6 / 4           |
| 200 entries of 40 aggregates, 600 ms handler | 1     | 18.2 / 22.4 s | 1449 / 1493  | 0 / 0           |
| 2000 entries of 400 aggregates, 20 ms        | 16    | 15.7 / 23.6 s | 4001 / 4000  | 0 / 0           |
| 2000 entries of 400 aggregates, 20 ms        | 1     | 21.7 / 12.1 s | 4000 / 4000  | 0 / 0           |

Eight renewal threads instead of one were measured on MongoDB in the same way. They removed
the repeated deliveries where the handler was slow, and they did not remove them where the
backlog was large: one of those runs still delivered an entry twice. They also leave the
writes where they are, because the entries are still claimed. So the threads treat what the
claims cost instead of not spending it, and there is nothing they buy on top of the queue of
one.

What the shorter queue does not cost is throughput. A lane which finishes takes the entry
waiting at it and the poller refills the place at once, so the lane idles for the length of
one claim rather than for a round trip to the BPMS. The durations above say the same thing:
the two settings cannot be told apart by them.

This entry does not change decision 75, which decided the lanes and the ordering key. What it
changes is how much a node claims ahead of them.

### 80. The BPMN location is read above the workflow, so below it the startup ends

`resources-location` names where an adapter's BPMN files lie. It is read for a workflow module
and for an adapter, and the global `vanillabp.resources-location` follows both. Nothing reads it
lower down, and nothing can: the deployment opens the files to learn which processes and which
tasks are in them, so at the moment the location is needed there is no workflow and no task to
ask for one.

The key nevertheless binds at all four levels of decision 7, because one class carries what an
adapter may be told and every level binds that class. So a line at a workflow or at a task can be
written, and until now it was read by nobody and reported by nobody. Somebody moves their BPMN
files and writes the new location one level too deep, and the application boots on the old files.

`MigrationAdapterProperties.refuseResourcesLocationsBelowTheWorkflowModule` ends the startup
there instead. The message names every place a location was written at and the two keys it may be
written at, which is the same answer decision 66 gives for the permission to share a whole
aggregate. Both are settings a level binds without reading, and a setting which can be written
and does nothing is worse than one which cannot be written at all.

### 81. No published artifact makes an application fetch Lombok or MapStruct

The rule: a published artifact never makes an application fetch Lombok or MapStruct. Two things are
out of a module we publish. A POM which names either tool at a scope a consumer resolves, and
bytecode which calls a method of either tool, reads a field of it or names one of its types in a
signature. What the source does is free, and the test code and the build tools stay free as well.

An annotation of either tool may stay, in the source and in the published class file, as long as its
retention is `CLASS`. An annotation with retention `RUNTIME` is not covered by the measurement below
and needs one of its own before it may stay.

**What the published artifacts carry.** Measured on 2026-10-02 from outside every repository: a
consumer project of its own, an empty local repository and `-Pvanillabp-snapshots -U`, so every file
came off the registry and Maven's workspace reader answered none of it. 37 published jars of the
platform, the three adapters, the SPI, the Gruelbox outbox and the Hazelcast cache, with 1038
classes in them, plus the 51 published POMs. No class in them names MapStruct, and no POM does
either. Lombok is a dependency of 23 POMs, 18 declared and five managed, every one of them at scope
`provided`, which Maven never passes on. Another 15 POMs name it inside
`annotationProcessorPaths`, where it is build configuration a consumer never reads. In the bytecode
Lombok appears as one single thing, `@lombok.Generated` on the members Lombok generated, in 134 of
the 1038 classes. It sits in `RuntimeInvisibleAnnotations`, so its retention is `CLASS` and the JVM
does not read it at all. No call, no field access and no type in a signature names either tool.

**The step which answers the question.** An application outside all repositories, with neither tool
anywhere near its POM, depends on `vanillabp-spring-boot-integration` and
`camunda8-adapter-spring-boot`. It resolves 79 artifacts at runtime scope, and neither
`org.projectlombok:lombok` nor `org.mapstruct:mapstruct` is among them. It compiles against
`WorkflowModule.builder()`, `getId()` and `getSourceUri()`, which Lombok generated, and it runs.
`Class.forName("lombok.Generated")` throws there, and reading the annotations of those members back
by reflection returns none, because the JVM skips what it cannot see.

**What the rule already took out.** `org.mapstruct:mapstruct` sat in
`quarkus-integration/runtime/pom.xml` with no scope, so it was on the compile classpath of every
application which pulls our Quarkus integration, for one generated properties mapper. That is the
first half of the rule, and the mapper is written by hand since then.

**Why the Lombok annotation is better off staying.** `lombok.addLombokGeneratedAnnotation = false`
in a `lombok.config` takes it out of every class file, and no source has to change for that.
Measured on 2026-10-02 in a probe project: with the setting the constant pool is clean, and it costs
coverage. JaCoCo 0.8.15 skips a member which carries an annotation named `Generated`, so a class
with a Lombok getter and setter whose test calls neither reports no missed method. The same class
compiled with the setting reports two missed methods and seven missed instructions, which is what
the same accessors written by hand report. The gate sits at 85, and the setting would move 134
classes across six repositories the wrong way for nothing an application can notice. So the
annotation stays and `lombok.addLombokGeneratedAnnotation` stays unset.

**Where the javadoc reason went.** The wording this entry replaces said that a class in the
`src/main` of a module we publish carries neither a Lombok nor a MapStruct annotation. It was
written for a real problem. Javadoc does not run Lombok, so the published page of a class whose
accessors Lombok generates shows none of them. Stephan weighed that on 2026-09-29 and takes it: a
javadoc comment on the field is enough while Lombok writes the getter and the setter, and a builder
without javadoc is a pity he accepts, because the extra code weighs more and a builder only rarely
reaches the end user. The help an IDE shows while somebody edits a YAML file is a second surface,
and it does not depend on Lombok either. It comes from
`META-INF/spring-configuration-metadata.json`. Every description in the published file of
`vanillabp-spring-boot-integration` is word for word an entry of
`spring-boot-integration/runtime/src/main/resources/META-INF/additional-spring-configuration-metadata.json`,
which is written by hand and guarded by `AKeyIsDescribedInOnePlaceTest` and
`EveryKeyOfASectionIsDescribedTest`. Not one of them comes from a javadoc comment, and the module
layout is the reason: `VanillaBpConfigurationProperties` only carries the `@ConfigurationProperties`
annotation, and every property comes from `MigrationAdapterProperties` in another module, whose
javadoc the processor cannot read. So a Lombok accessor on a properties class costs nothing there.

The thirteen configuration classes which lost their Lombok annotations before this wording stay as
they are. Putting the annotations back costs more than it returns.

### 89. A setting written below the level it is read at ends the startup, and all of them say it the same way

One class carries what an adapter may be told, and all four levels of decision 7 bind that class.
A key therefore binds at a workflow and at a task even where the code reading it never asks a
task. Such a line can be written, nothing complains, and nothing happens. The application boots
and the setting is not there.

Decision 66 ended the startup for the permission to share a whole aggregate, decision 80 for the
location of the BPMN files. Each wrote its own message in its own words, and the next key would
have written a third. `MisplacedSettings.refuse` is the shape all of them use now. It names which
levels read the setting, the keys the application wrote, the keys it may write instead, and why
the written level cannot be read. The keys are printed sorted, so the same configuration reads
the same way on every boot.

The rule this shape carries is the general one: a key which binds below the level it is read at
ends the startup. Dropping the binding is the other way out and it is not open to us, because one
class serves all four levels and a key dropped there is dropped everywhere.

### 91. The housekeeping gets a window at night, one node per store, and it measures how much fits

The outbox used to house-keep at the end of every poll: it deleted the entries whose retention had
passed and then the payloads no entry named any more. Every application paid for that all day long,
and nobody could say what one poll cost, because the cost depended on a table nobody measured.

Stephan's design of 2026-09-24: give the housekeeping an hour at night and let it take as much as
fits. The window is `vanillabp.outbox.housekeeping.start` to `.end`, four to five in the morning
where nothing is configured, and it governs both sweeps. The entries go first: they are the mass,
and the table they leave behind is the one the question about the orphaned payloads searches.

**How much fits is measured rather than configured.** Nobody can name that number in advance - it
depends on the database, on the machine, on how much history the store carries and on what else runs
at that hour. So the first batch of a night is a thousand rows, the time is measured, and the next
batch is twice as large while twice the time would still fit in what is left of the window. A batch
which runs past the end halves the size, because one wrong guess would otherwise eat the rest of the
window. The next night starts at HALF of the largest batch which fitted, not at that batch: the
database changed over night, and half is the distance kept from a measurement which is a day old.
Every batch is measured again rather than extrapolated, and the ramp is capped, because on the
gruelbox store the time grows with the table and not with the batch - every batch scans - and a rule
which doubled on the strength of one measurement would climb forever there. The rule lives in the
core (`HousekeepingBatchSize`) and not in the stores: four copies of the same awkward arithmetic
would make every claim about it depend on four copies staying equal.

Nothing of this is persisted, and it is not owed. The ramp is geometric, so a thousand reaches a
million in ten steps; a pod which restarts every day loses a few seconds at the beginning of its
window and nothing else.

**One node per store.** Two nodes house-keeping at once would each measure the other's work, and the
number they arrive at would be nonsense. A node therefore claims the store in the store's own
database, in `VANILLABP_HOUSEKEEPING` respectively the collection `vanillabp-housekeeping`, one row
per store. The shared Hazelcast cache was refused for this: it hangs on the election cache, it is
optional, and an application without it would then house-keep either not at all or uncoordinated,
while a database is something every application with an outbox has. The claim is per STORE and not
per application, because an application in a migration has a JPA outbox and a MongoDB outbox - two
databases, which two nodes may house-keep one each.

The claim is NOT renewed while the work runs, unlike the lease of a dispatch (decision 79). A
dispatch calls a BPMS and has no end anybody knows in advance; a window has one, so the claim is
taken until the window closes and given back when it does. A node which dies inside the window holds
the store until that end, which costs the rest of one night and is visible in the meters. What a
renewal would buy is a few minutes of one night, and what it would cost is a second thread and a
second reason for a lease to be lost.

A table of its own, and not a row in the outbox: a claim is neither an entry nor a payload, and
gruelbox owns its table, so a row could not go there at all. On gruelbox the window governs the
payloads alone - that library deletes its own dispatched entries in its flush, and its table is not
ours to bound.

**Three meters, read together.** `vanillabp.outbox.housekeeping.remaining` says what the window did
not get to, `.removed` how many rows it took and `.window.used` how much of the window it needed.
All three are set when the window closes and held until the next one closes, so reading a gauge
reads a field. The remainder alone would say nothing: a store with work left may have run out of
window or may never have house-kept at all. With all three the condition to alert on is a window
which was used up AND something left over, and the answer to that is a wider window. The remainder
costs one `COUNT`, which is the expensive question of decision 19 - once a night instead of once a
poll, and along the index the retention delete already reads. The orphaned payloads are not counted
with it, because on the stores which cannot index the reference that count is the very scan this
change took out of the poll.

**A JVM on UTC without a configured zone is warned, and starts.** A window without a zone is not an
instruction, so the zone is configurable and otherwise the zone of the JVM. A container runs on UTC
unless somebody sets its zone, and "four in the morning" then means four UTC, which in most places
is the middle of the working day - the housekeeping would run at that hour and nothing would say so.
So it says so: one warning at the startup, naming the hours the window really runs at, the zone the
JVM stands in, and both ways to say something else - set `TZ` on the container, or set the
environment variable of the property. Neither needs a new build. Every spelling which means UTC
counts, and an application which really wants UTC writes it down, after which the line goes away.

This was a refusal first, and Stephan turned it into a warning on 2026-09-25. The argument for the
refusal was that a wrong zone is invisible; the argument against it is the stronger one. UTC is what
a container ships with and what a Kubernetes deployment normally has, so refusing such a start does
not uncover a mistake, it invents a precondition - every application built against version 1 would
have had to be told a new thing before it could boot. And the two costs are not the same size: a
window in the wrong zone sweeps at an hour nobody expected, which is surprise and some load at the
wrong time, while a refused start costs the deployment. The warning is one of the notes VanillaBP
means to collect into one box at the end of a startup, so an operator reads them together.

The test JVMs of this repository are given a zone in the root POM, because the window is read in one
and a test which computes an hour of its own should not depend on the machine it runs on.

A node which is down for the whole window does not house-keep that night, and nothing catches it up.
A mechanism for that would be a guess; the meters show the night which was missed, and the
documentation says so instead.

### 92. A workflow the BPMS starts builds its own aggregate, and the platform builds none

A workflow started by a timer, a signal or a conditional start event has no aggregate, and
somebody has to build one. Until now VanillaBP did it: it instantiated the aggregate class,
derived an id from the trigger and copied the process variables of the model into equally named
attributes by reflection (`AggregatePropertyWriter`). An application could take over with
`@WorkflowStartedByBpms`, in two shapes, and that annotation was optional.

It is the same trap that was turned down for the `null` sub-objects of an aggregate: an
object which comes into existence without the application does not carry the application's
values. Lombok writes the defaults of `@Builder.Default` in the builder, so an instance built
with the no-argument constructor carries `null` where the application would have a value. A
private constructor makes the instantiation impossible at all, and a mandatory field breaks it at
the next change of the class. For a workflow the BPMS started this is not a detail somewhere in
the middle, it is the FIRST thing that ever happens to that workflow.

So the annotation is required, the enriching shape is gone, and the method returns the aggregate.
The platform saves what it is given and nothing else. The id is the application's choice as well:
the trigger carries a timer's time, and taking it as the id is what makes a repeated notification
harmless, while a signal and a condition carry no such value and an application which needs one
brings its own.

*Decision 98 replaced the part about the timer's time. The trigger carries no time any more, and a
repeated notification is recognised by the name the BPMS holds.*

The check runs while the models are deployed, not when the start fires. What the BPMS can start
on its own stands in the model, and which methods exist is what the scan knows, so both halves are
there while the application boots. Finding out at three in the morning, when the timer fires and
nothing happens, is the outcome this avoids. A process with such a start event and no method ends
the startup with a message naming the process, the start event and the method to write.

This also removes the third direction values could travel in. The rule about portable values
(`PortableValuesCheck`) covers the aggregate on its way to the BPMS and the `@TaskParam` on its
way back; BPMS into an aggregate was the third, and it existed only because the platform did
something which belongs to the application.

Two earlier entries close with a sentence about `AggregatePropertyWriter`: decision 55 says that
the same conversion writes the attributes of an aggregate a BPMS-initiated start builds, and
decision 57 says the same about value types and their texts. Both sentences are about a class
which no longer exists. What those decisions decide is untouched, because it is about
`@TaskParam`; only their closing consequence is gone.

Version 1 is not affected. It did not support a process the BPMS starts on its own.

### 93. An adapter's retry window means the same thing on every store

An adapter which rejects a phase-two call with `PhaseTwoRetryLater` says how long its BPMS needs
before asking again can help. The relational store of the core and both MongoDB stores wrote that
window onto the entry as it was named. The gruelbox store read it as an upper bound instead: it
wrote the window only where it was closer than `vanillabp.outbox.attempt-frequency`, and left its
own distance standing otherwise. So the same adapter on the same application answered differently
depending on which store the application had chosen, and the difference lived in the name of a
test rather than anywhere an adapter author reads.

The gruelbox store writes the window unconditionally now. An adapter naming a window knows
something about its BPMS which a store configured once for every workflow does not know, and
asking earlier than that costs a failed attempt out of the budget which blocks the entry. The
price is that an adapter can push an entry past a backoff a store keeps shorter on purpose, and
that is the adapter's call to make: the window is a statement about the BPMS, not a hint.

What a store still adds on its own is the poll it takes to pick a due entry up, at most
`vanillabp.outbox.poll-interval` (decision 49). That is a property of polling and not of the
window, and it is the same on all four stores.

The promise is written where an adapter author meets it: in the javadoc of `PhaseTwoRetryLater`
and on the outbox page of both platform wikis.
`GruelboxWritesTheDueTimeADispatchAskedForTest#aLongerWindowIsWrittenToo`, in the repository of the
gruelbox store since decision 102, holds the case which
used to go the other way.

*Superseded in part by decision 113: the part of a sentence saying that asking earlier costs a failed attempt, since asking earlier costs no attempt now and only asks in vain.*

### 94. A held version says which of its elements never name the item of a round

A multi-instance element which names no item - a Camunda 7 element without
`camunda:elementVariable`, a Camunda 8 one without `inputElement` - iterates without ever
saying what the value of a round is. A handler reading that value gets `null` and nothing says
why, so an adapter asks the core while it DEPLOYS a model and refuses the pairing.

A version a BPMS only still holds is never deployed again, so nobody asks - while the methods of
the application serve it all the same, because their version ranges say so. The first news is a
`null` in a handler running on a workflow which was started before the upgrade, and those are the
workflows which run longest.

So `BpmnTaskSpec` carries `multiInstanceElementsWithoutAnItem`, and the adapter fills it from the
model it read for `ProcessVersionCatalog.tasksOfVersion`. The component stands last, like `name`
before it, so every constructor an adapter and a test already write stays as it is.

`null` means that this adapter does not read the shape, and then the question is not asked at
all. An empty list means that every element of the chain names its item. That is decision 38: a
check which cannot answer for sure stays silent rather than refusing.

The finding is a WARNING and goes into the block a start writes at its end, under the versions a
BPMS still holds. Nobody can change a held model any more, and an application may have decided on
purpose to let the item be `null` there; what it must not be is silent. The way out is on the
side of the code: narrow the version range of the method and add one for the old version which
reads the index and the total only.

`DeployedProcessVersionsCheck.reportItemsThisVersionNeverNames` asks the question, and
`TheMultiInstanceShapeOfAHeldVersionTest` holds the cases.

### 95. A held version waiting for a name nobody sends any more is worth a look

A version a BPMS still holds can declare a message name or a signal name which no model of this
deployment declares any more. The workflows on that version wait at their event for something
nothing sends, and nothing in VanillaBP would say so: the name lives in a model the BPMS holds
and in no file of the application.

The core already reads those models for the name-clash check of a held version, so the question
costs nothing extra. It is answered there, next to the clash.

A warning, never a refusal. The platform can see the case and cannot know whether it is meant: a
rename whose old workflows were finished by hand looks exactly like a rename nobody finished. So
the line says "look at it" rather than "write this" - decision 38.

Messages and signals only. An error code and an escalation code are thrown by the model which
carries them, so a held version declaring one carries whatever throws it as well. A task
definition nobody serves is the subject of its own check, which says more about it than this
could. A BPMN process id is the process being asked about.

Two things silence it. A version nobody runs on: nothing waits there. And an adapter which never
reported what the current models declare: where nobody said what this deployment has, every old
name looks new, and the check would report a whole model.

It goes into the block a start writes at its end, under the versions a BPMS still holds.
`NameClashAvoidanceService.reportNamesNobodySendsAnyMore` writes it, the adapters answer through
`NameClashAvoidanceSupport.reportIdentifiersOfHeldVersion`, and
`ANameTheCurrentDeploymentNoLongerDeclaresTest` holds the case.

### 96. A start says once what it noticed

VanillaBP looks at a lot while an application starts. It used to say each of it where it found
it, so the findings stood between the lines of every other library and nobody read them unless
they were already searching. There are 260 such messages across the platform and the four
adapters, and of them at most 95 can appear on a start which survives.

So a start collects what it finds and says it once, at its end, as one block between two rulers:
`StartupFindings`. A check reports a finding instead of logging it, and the block is written in
ONE call of the logger, because a block written line by line is a block the next library writes
into.

The block is grouped by topic, and a topic is the artifact the fix lies in: parts and versions,
configuration, code, BPMN models, the versions a BPMS still holds, stored state, infrastructure.
That is the order a developer walks, and almost every message ends with an instruction naming one
such place, so the heading tells a reader which file to open. Each heading carries its count,
which is the line a reader skims. Grouping by severity was the obvious alternative and says
nothing: on a start which survives, nearly every finding is a warning anyway.

An application with nothing to notice gets nothing: no ruler, no heading, no empty message. A
healthy start reads the way it did before.

A finding is reported unformatted, with its scope beside its text, because the same finding
arrives once per workflow module, per BPMN process, per adapter id and per method. Two findings
of one topic carrying the same text become one entry naming both scopes.

**The two ends.** What ends a start is collected as well and thrown once, at the same moment,
with its reasons grouped and counted the way the block is grouped and counted, so the two read
alike. A developer who put two things wrong learns both in one start instead of one per restart;
the shape was already in the tree, in `DeploymentService.checkPartsBelongTogether`.

What was noticed before the refusal fell due is written before it is thrown. A warning does not
become less true because a later check ends the start.

The limit: a check which cannot let the start walk on, because the next check would ask a
question this one just proved unanswerable, throws where it stands. Its javadoc says so.

**Where the end of a start is.** In `DeploymentService.endOfStartup`, which the platform calls once
its start is complete. On Quarkus that is right after the workflow processing started. On Spring
Boot it is the end of the deployment, and what the start of the workflow processing notices after
that is a late finding. Apart from that, nothing a start can notice comes later: six validations run in a hook both platforms fire once
every bean exists, and the election capability of the adapters is judged after the deployment,
because a BPMS may only tell what it can do once something was deployed to it.

What Quarkus refuses while it BUILDS never reaches the block, and it is right that it does not:
an application which was never built never starts.

A start which was refused already ends earlier than that. The deployment and the start of the
workflow processing are the two steps which reach outside the application, and both look for a
collected refusal before they run: nothing is deployed to a BPMS for an application which is
about to end, and no adapter is told to hand out tasks for one. It also decides which message a
developer reads where both would speak, and the right one is the check which named a gap in
their application rather than whatever the deployment runs into afterwards.

`MigrationAdapterProperties.startupFindings()` is where a check finds the collection to report
into, and `TheBoxAtTheEndOfAStartTest` holds the block, the healthy start which gets nothing, and
the two ends.

### 97. An adapter reports through a bean, not through the adapter SPI

The block at the end of a start (decision 96) belongs to the core. An adapter has to reach it,
because 152 of the 260 startup messages of VanillaBP come from the four adapters, and a start
which writes a block for the platform and a line per adapter is worse than one which writes lines
for everything.

The way there is a bean. `io.vanillabp.integration.spi.startup.StartupReport` lives in the
integration SPI, `StartupFindings` implements it, and both platform integrations publish one
instance per application. An adapter asks for it the way it asks for a data source: a
constructor parameter on Spring Boot, an injection point or a producer parameter on Quarkus.

Not the adapter SPI, and not `AdapterCollaborators`. The collaborators are handed to the
deployment service and the process service of an adapter, and most of what an adapter finds
at a start is found before either of them exists: the Camunda 8 adapter alone has 75
messages and nearly all of them are about its configuration, which is read while its beans
are built. A collaborator would reach half the places which need it, and the other half
would need the bean anyway. Two ways to one object is one way too many.

The interface carries the reporting half and nothing else: `notice`, `warn`, `error`,
`refuse`. When a start is over, whether a reason not to start is collected or thrown, and
what the block looks like stay with the core, because those are answers a start needs once
and not once per adapter.

`StartupTopic` moved from the core into the integration SPI with it. An adapter names the
topic of its finding, so the list of topics is part of the contract rather than part of the
core's rendering.

**What an adapter reports.** A finding, which is something the developer of the application has
to change or know about. What the adapter itself set up, how many workers it started, which BPMS
it reached: those are reports about the adapter doing its work and they stay where they are. The
three framed reports of the adapters (`Camunda8Connectors`, `Camunda8Listeners`,
`Camunda7Listeners`) are of that kind and do not move.

**Nothing breaks while the adapters follow.** The platform publishes the bean, and an adapter
which does not ask for it behaves exactly as it did. The Camunda 8 adapter asks for it; the others
pick it up in a story of their own. That is why the way is a
bean and not a new mandatory collaborator: a mandatory one would turn every adapter red the
moment the snapshot lands, for a feature none of them uses yet.

`AnAdapterReportsIntoTheSameBoxTest` holds a finding which arrives through the bean, and
`TheStartupReportOfAnAdapterTest` of both platform integrations holds that each of them publishes
it.

### 98. The application names the workflow, and the BPMS holds that name

The id of a workflow is the id of its workflow aggregate. The application assigns it, in the
`@WorkflowStartedByBpms` method, and nobody else does. The BPMS is told about it afterwards and
keeps it: Camunda 7 as the business key, Camunda 8 and the Process-Engine-API as a process
variable named after the aggregate's id attribute.

**Why the id belongs to the application.** Decision 92 handed the workflow aggregate of a started
workflow to the application, because an object which comes into existence without the application
does not carry the application's values. The id was left half way. The core still read a name out
of the BPMS, still tried to turn it into an id of the aggregate, and then held it against the id
the application had chosen. Two parties named one workflow, and the core refused every start
where they disagreed. The Camunda 7 adapter carried the proof in its own suite: its Quarkus
lifecycle test starts a process past VanillaBP with a key of its own, and that start had been
failing since 504 landed.

An id is a value like any other. It belongs where the other values of the aggregate come from. So
the derivation is gone: `getNaturalIdentity`, the trigger time as an id, the generated fallback,
and the conversion which took a business key over where it happened to fit the id type. What is
left is one sentence a reader can hold in their head.

**What the listener reads.** The listener hangs on EVERY start event of a process, the plain one
included, and decides from the STATE of the workflow rather than from the kind of the event:

- the BPMS holds a name and a workflow aggregate carries it: the workflow is already ours and
  nothing is built. That is the application's own start, and it is the same answer for a second
  delivery of the same notification, which is how at-least-once delivery stays harmless.
- the BPMS holds no name: somebody started this workflow past VanillaBP. The
  `@WorkflowStartedByBpms` method builds the aggregate and names it, and the adapter writes that
  name into the BPMS.
- the BPMS holds a name no workflow aggregate carries: the start is refused. The message says
  that VanillaBP names a workflow, names the value the BPMS holds and says where it is kept.
- an unnamed start reaches a process without such a method: the start is refused, and the message
  carries the method to write. That is a runtime refusal, so the BPMS makes an incident of it.

The old rule read the kind of the start event instead. It could not work. Anybody with access to
the engine can start any process, and a timer start event says nothing about who started the
instance this time.

**Where the name is read from.** One question, two places to look.
`BpmsInitiatedStartContext.getBusinessKey()` is the answer of a BPMS which keeps a business key
of its own, and the core reads it first. Otherwise the core reads the process variable named
after the aggregate's id attribute out of `getVariables()`. Both are the same value under two
roofs, so one rule serves every BPMS and an adapter has nothing extra to report.

A name which does not even fit the type of the id attribute - a text against a numeric id - is a
name no workflow aggregate carries, and it takes the same refusal. There is no separate case for
it, because there is no separate outcome.

**What still runs at boot.** A process whose BPMS fires a start event by itself - a timer, a
signal, a condition - and which has no `@WorkflowStartedByBpms` method still ends the boot, which
is decision 92. Such a start can only ever arrive unnamed, so the application would find out at
three in the morning.

A plain or message start event is not judged that way. It is the shape the application's own
start has, so demanding a method for it would refuse every ordinary process. A foreign start
through one of them is refused when it happens.

**What it costs.** One load of the workflow aggregate per start of a workflow, where the
application's own start used to cost nothing. The workflow's first task loads the same aggregate
a moment later anyway. On Camunda 8 it also costs one job per start, which the adapter's own
decision measures.

**What this supersedes.** Decision 24 of the `camunda7-adapter`, which said a workflow started
past VanillaBP keeps the business key it was started with and gets its aggregate under that key.
It does not any more: a key VanillaBP did not write is refused.

The part of decision 92 which said the trigger carries a timer's time and that taking it as the
id makes a repeated notification harmless. There is no trigger time any more. No BPMS hands a
start listener the time it scheduled the start for, so an adapter would have to invent it. An
application which needs the time models a process variable, fills it by an expression and reads
it as a `@TaskParam`. Repeated notifications are handled by the name the BPMS holds instead,
which works for a signal and a condition as well.

`BpmsInitiatedStartExecution` carries the rule and `BpmsInitiatedStartTest` holds the four
answers of the listener.

### 99. A task for a workflow this application does not own is refused, loudly

A delivery whose workflow aggregate is not in this application's database is refused with
`DeliveryOfAnUnknownWorkflowException`, counted as `vanillabp.task.deliveries.unknown.workflow`,
and written into no delivery record. The BPMS then does with the refusal what it does with any
failing task, which on Camunda 8 is an incident at the first delivery of a listener job.

Stephan decided this on 2026-09-27, after story 605 measured the case and prompt 642 wrote down
the three ways out.

**Why it stays loud.** The retries are not raised for it, and the delivery is not handed back to
the BPMS as if nothing had happened. An application which quietly drops the work of another
application drops it for months, and the first anybody hears of it is a workflow which never
moved. The incident costs the owning application nothing, because the work is still in the BPMS,
and it costs the person who runs both applications one look at a message which tells them what to
do.

**What the message may not say.** The old message was `No workflow aggregate of class '%s' having
the ID '%s' was found processing a task ... it must not be deleted while the workflow is active`.
It named one cause and it named the wrong one: it tells a developer they deleted an aggregate, at
the moment when the likely truth is that another application on the same BPMS owns the workflow.

The new message says what happened, names everything the BPMS said about the delivery so the
reader can look the workflow up on the other side, and then names BOTH situations it can be, with
a next step for the one which has one. The property key it prints is spelled for the workflow
module and the adapter of this delivery, so it can be copied into the configuration as it stands.

**The two situations cannot be told apart.** A workflow aggregate this application never had and
one it had and deleted leave the same trace, which is none.

- the delivery records of a workflow are released when it ends and the retention removes the rest,
  and the very first task of a workflow has no record at all. An absent record proves nothing.

- the hints of the workflow-adapter cache live in memory for a while and are gone after a restart.
  An absent hint proves nothing either.

Guessing from either of them would name a cause the evidence does not carry, so the message names
both and accuses nobody. Should a durable record of "this application started this workflow" ever
exist, this decision is the place to revisit.

**Why no delivery record.** The record would be written by the application which wrongly received
the task. Whoever investigates reads the records of the application which OWNS the workflow, and
there the record would be missing, so the store would hold the one row nobody looks at and lack
the one row somebody looks for. `TaskDelivery` also carries a `workflowAggregateId`, and there is
no such aggregate here.

The metrics carry the fact instead. A counter answers "does this happen often", which is the
question asked the second time such an incident shows up, and it does so without a row per
delivery.

**What each adapter does with it.** The core words the refusal, the adapter carries it out with
the means of its BPMS. The Camunda 8 adapter puts the message into the incident and logs it
without a stack trace, because the sentence is the whole finding, which is decision 45 of that
repository. Camunda 7 cannot meet the case in normal operation, since its engine is embedded and
belongs to one application. The Process-Engine-API adapter can meet it, because its task
subscriptions match a task type globally and it has no tenant, and today it answers with
`failTask` and the engine repeats the delivery; that is its own decision to take.

`DeliveryOfAnUnknownWorkflowException` words the refusal, `MigrationProcessService#deliverWorkflowTask`
throws it, and `MigrationProcessServiceTest` holds the message.

### 100. The order of one workflow's operations is a promise of VanillaBP's own stores only

Two operations of one workflow aggregate leave the outbox in the order they were planned.
That holds for the two stores VanillaBP writes itself, the relational one and the MongoDB
one, because both dispatch on `vanillabp.outbox.dispatch-threads` lanes keyed by the
aggregate and both read the oldest entry first (decisions 75 and 77). It does NOT hold for
the gruelbox store, and it is not going to.

**Why gruelbox cannot be held to it.** The store is a thin layer over gruelbox' own table and
gruelbox' own dispatch, which is the whole point of it: an application which ran gruelbox before
keeps its table and its entries. Three things of that dispatch are out of reach.

- There are no lanes. gruelbox submits to the executor its submitter was built with, and
  the default is a pool which starts at one thread and grows to the parallelism of the
  common pool once its queue of 16384 is full. Nothing binds an aggregate to a thread.
- An entry reaches a dispatch two ways. gruelbox hands it to the submitter the moment the
  scheduling transaction commits, and a flush picks up whatever is due. The two race, so
  the second operation of a workflow can be submitted directly while the first one is
  still waiting for a flush.
- A failed attempt moves `nextAttemptTime`, which is the column a flush orders by. An
  operation which was rejected once therefore falls behind one which was planned after it.

Building lanes around gruelbox would mean holding its entries back and picking them in an
order of our own, which is a second dispatcher on top of the one the application asked to
keep.

**Why nothing has to be written for a user.** The wiki says the order between two operations is
not guaranteed and tells an application which needs one what to do instead: give the first
operation a transaction of its own and open the second transaction after that one committed.
gruelbox keeps that promise, because it is the weaker one. What the lanes buy is a store which
does better than the promise, not a promise which now reads differently per store.

**What is measured.** `EntriesOfOneAggregateKeepTheirOrderTest` and
`MongoEntriesOfOneAggregateKeepTheirOrderTest` hold the order for the two stores which keep it,
and `DispatchLanesTest` holds the rule itself. Nothing measures an order on gruelbox, which is
what this decision says is right.

### 101. Compensation is on the list of things which put a second token into a workflow

`ConcurrentTokenCheck` knew five ways a BPMN process can hold more than one token: the
non-interrupting boundary event, the forking parallel gateway, the forking inclusive gateway,
the parallel multi-instance activity and the non-interrupting event subprocess, plus the ad-hoc
subprocess. Compensation was not among them, although a throw event which compensates two
finished activities starts both handlers and leaves the workflow with a token per handler. Each
of those handlers is an ordinary workflow task and writes the same workflow aggregate, so an
aggregate without a version attribute loses one of the two writes.

Compensation now joins the list. The adapter reads its model and reports, the core decides what
it means and warns, which is the split every other form of this finding follows.

**Why it is reported as a shape and not as element ids.** The other forms are a flat list of
element ids, and the message names a few of them. That does not work here. The finding is that ONE
event in the model turns into several branches, and a list which holds the throw event next to the
handlers leaves the reader to work out which starts which. So the adapter reports
`CompensationSpec(throwEventId, handlerIds)` through `WorkflowTaskWiring#reportCompensation`, and
the warning names the throw event and the handlers it starts.

The text of the warning is the one every other form carries, so a process whose parallel gateway
was reported already folds into the same entry of the startup box. What has to change is the
aggregate, and that is one thing however many places in the model lead to it.

**A throw event which starts one handler is not reported.** Compensating a single activity gives
the workflow no second token. The handler runs where the rest of the model runs. Only a throw
event with at least two handlers is reported, and the adapters filter that out before they
report.

**Why Camunda 7 reports it although it runs the handlers one after the other.** Measured on
2026-09-27 against the embedded engine of Camunda 7.24 (`Camunda7CompensationTokensTest` in the
adapter): a throw event which compensates two finished service tasks creates both compensating
executions first and then signals them one at a time. The second handler starts after the first
one returned. This holds for the model as a modeller writes it and for the model as the adapter
deploys it: the adapter sets `asyncBefore` on every service task, and the flag is set on the
handlers as well, but the engine starts a compensation handler outside the normal flow and never
looks at it. Only one job exists at a time, and with the job executor running both handlers ran on
one thread.

Which handler goes first is not stable there, by the way. The engine sorts the subscriptions by a
creation time of millisecond resolution and sorts stably, so two activities compensated within one
millisecond are undone in the order they ran in. Both orders showed up in the same test within an
hour. That changes nothing about this decision and it is one more reason the wiki promises nothing
about the order.

So on Camunda 7 two compensation handlers made of service tasks never write the aggregate at the
same moment. The report is made anyway, for two reasons.

The check asks whether the process can hold more than one token, not whether two threads are
inside a handler. Both compensating executions exist from the moment the throw event runs, and
that is the same answer the parallel gateway gets on this engine, where the exclusive jobs of one
workflow are serialised by the job executor as well.

And a handler which WAITS keeps its token while the next handler is started. Two compensation
handlers drawn as user tasks are open at the same time on Camunda 7, and the application may
complete them in two transactions which overlap. The engine gives no guarantee here that would
make the warning wrong.

What follows for the wording is that the message must not claim the handlers run in parallel. It
says they can be open at the same time, and the wiki page `Compensation` carries what each engine
really does.

### 102. The gruelbox store leaves the platform, and what stays is the bridge and one refusal

Decision 75 said that gruelbox stays available inside this repository as an opt-in, behind
`vanillabp.outbox.gruelbox.enabled`, and that nothing in the platform depends on it any more. The
second half held. The first half is what this entry changes: the store is out, with its tests and
its library, and it lives in `vanillabp/gruelbox-phase-two-outbox`.

The opt-in was not enough, because a switch in the platform is a second decision. An application
had to have the library AND set the key, and the platform then had to say something sensible about
every combination of the two. It also kept paying for the library everybody carries:
`com.gruelbox:transactionoutbox-core` and `-spring` were `optional`, so no application inherited
them, but this repository still compiled against them, tested against them and answered for them.
What the move costs an application is one dependency, and its configuration stays as it is. The
section is still `vanillabp.outbox.gruelbox.*` and the table is still `TXNO_OUTBOX`.

**The bridge stays.** `JdbcPhaseTwoOutboxAutoConfiguration` still counts what `TXNO_OUTBOX` holds
undispatched and warns about it. An application coming from that store needs the message exactly
now, when the code which read that table is gone. It names the artifact to add instead of a key,
and it carries the table name as a literal, because the class which declared that constant is in the
other repository. The name belongs to the library anyway: gruelbox defaults to it and its migration
creates no other.

**Nothing about the key stays.** Every artifact holds its own properties, and that is the
developer's side of the bargain (Stephan, 2026-09-28). So `vanillabp.outbox.gruelbox.*` leaves this
repository completely: no class binds it, no condition reads it, and
`META-INF/additional-spring-configuration-metadata.json` does not describe it either. A development
environment therefore proposes that key exactly where the artifact serving it is on the classpath,
which is the artifact's own generated metadata doing the work.

Two earlier drafts said something about the key. The first ended the boot of an application which
set it without having the artifact, the second wrote a WARN instead. Both are gone, because a
property of an artifact which is not there means nothing, and a platform which comments on every
key it does not own would have to keep the list of them. What an application coming from that store
still needs is not about the key at all. It is the message above about what `TXNO_OUTBOX` holds, and
that one stays.

The Quarkus refusal stays as it was, with one sentence added: the store is not just absent on
Quarkus, it is not part of VanillaBP at all.

The two stores take turns through one bean name, `vanillaBpJdbcPhaseTwoOutbox`, which is the outbox
of a relational aggregate. The store of the other repository registers its outbox under that name
and declares its auto-configuration `before` this one, and this one carries
`@ConditionalOnMissingBean` on that name. So the dependency is the whole wiring, which is how
`hazelcast-shared-election-cache` replaces the in-memory election cache, and
`SpringPhaseTwoOutboxResolver` knows one JPA default name instead of two.

The same name is what makes the way back work. An application which switches the other store off
leaves the name free, so this configuration applies and serves the entries, which is one half of
the rule and the half nothing would have noticed breaking. `TheDependencyIsTheSwitchTest` of the
other repository holds both halves.

Beyond the move it cost two dependencies which nobody had asked for. They came in through gruelbox
and had to be asked for explicitly once it was gone: `jackson-databind` in test scope, which the
tests reading the configuration metadata parse with, and `jackson-annotations` as `provided`, which
javadoc needs to read the annotations of Spring Boot's health classes. Both were invisible while a
third party dragged them in, which is one more argument for the move.

The wiki keeps a paragraph about the store on the page `Spring-Boot-integration`, in the section
"The store which used to be here", and the page `Blocked-outbox-entries` points to the other
repository for a blocked entry there. Nothing in the platform cites either.

### 103. An expression in the model is named, and the rule which names it lives in the platform

An expression like `${order.shipping.express}` binds a BPMN model twice over: to the shape of the
objects behind the name, and to the expression language of the BPMS it is deployed to. Rename the
attribute in Java and a model nobody touched stops working. Move the workflow to another BPMS and
the expression stops working. The wiki has recommended the way round it for years, a getter on the
workflow aggregate which answers the question the model asks, and nothing noticed when a model did
it differently.

VanillaBP now notices. Four things were open before it could, and this is how they are answered.

**Which expressions are looked at, and who finds them.** Every place a modeller puts a data read:
the conditions of sequence flows and of conditional events, timers, the cardinality, the collection
and the completion condition of a multi-instance element, a loop condition, the correlation key of
a message, the inputs of a decision, an input or output mapping. `ExpressionPlace` names them and
carries the words a message uses for each. A place nothing there describes is `SOMEWHERE_ELSE`
rather than the closest match, because a wrong place sends the reader to the wrong part of the model
while the element id beside it is precise on its own.

Finding them is each adapter's work, reported through
`WorkflowTaskWiring#reportModelExpressions(module, process, expressions)` during `wireBpmn`: only
the adapter can read its BPMN dialect, knows where its BPMS evaluates something and knows what
delimits an expression. An adapter which cannot read its models reports nothing, and nothing is
read into that silence.

What is NOT reported is what the BPMS resolves for itself, an expression naming a wired task, a
delegate class or a form key. Those are not data reads and the developer cannot replace them with a
getter.

**Which of them are harmless.** The name of one variable is harmless, and more than that: it is what
VanillaBP recommends. Nothing is said about it. The rule which decides that is `ExpressionForm`,
and it lives in the platform, not in the adapters. Every adapter has its own expression language,
and what an expression costs must not depend on which of them a model is deployed to, otherwise
each adapter says something different about the same model.

The rule works on shape, not on a grammar. Both languages VanillaBP meets write a variable read as
a bare name, a member access with a dot and a call with a parenthesis, and that is all it needs:

|        Form        |                            Example                            |     Verdict     |
|--------------------|---------------------------------------------------------------|-----------------|
| `NAMES_A_VARIABLE` | `shippedAsNormalItem`                                         | nothing is said |
| `WALKS_A_PATH`     | `order.shipping.express`, `orderItems[1].shippedAsNormalItem` | WARN            |
| `CALLS_SOMETHING`  | `count(items) > 3`, `order.getShipping().isExpress()`         | WARN            |
| `COMPUTES`         | `not bigItem`, `amount > 1000`, `'PT1H'`                      | NOTICE          |

Two consequences of drawing the line there are deliberate.

A name with a space is legal in FEEL and is still not read as a name. The variables VanillaBP hands
a model are named after the accessors of a workflow aggregate, so a name which needs a space is not
one VanillaBP put there.

Anything the rule cannot place is `COMPUTES`, the mildest of the findings. A form nobody foresaw
must not turn into the loudest message.

How gentle: a WARN for what reaches into the data, a NOTICE for what only computes, and never a
refusal.

The two levels are the two halves of the binding. A path or a call binds the shape of the data, and
a rename in Java breaks the model silently, which is worth a warning. A computation leaves the data
model alone: what it binds is the language, whose word for "not" differs from the next one's. The
way out is the same getter in both cases, so the texts are close and the level is what differs.

Not a refusal, for two reasons. An existing model would stop deploying over a style we recommend,
which is the opposite of gentle. And a check which reads expressions can misread one. A wrong
warning about a model which works is bad, a wrong refusal is unusable.

A third form was on the table, a report counting the expressions of a model, and it is not a
message of its own. It is a sentence in the two messages: "2 of the 5 expressions of this process
name a variable and nothing else." That is why an adapter reports the harmless expressions as well.
A developer reads how far their model already is, and a process doing everything right stays
silent.

The exception the wiki names is named in the message too. A path into one item of a multi-instance
subprocess is what some BPMS need, the page `Workflow-aggregates` shows it, and a check which
warned about it without saying so would look wrong.

The acceptance is written as `accept-expressions-in-the-model`, read at the workflow, the workflow
module and the application, the most specific level winning. The message hands out the key at the
workflow, the most careful of the three.

Read at three levels, unlike `allow-full-sync-with-bpms` which is read at the workflow and nowhere
else (decision 66). That permission lets values leave the application, so an inherited one would
cover the workflow somebody adds next week. This one lets nothing out and changes no behaviour: it
says that somebody looked at the expressions of a model and meant them, and a team decides that for
a whole application as easily as for one process. Every level being read also means no line is
silently ignored, so there is nothing for `MisplacedSettings` to refuse.

The check leaves one thing alone, a BPMN process no `@WorkflowService` class claims. Its model
travels to the BPMS because it shares a file with the process which IS served, and asking for its
expressions to be rewritten asks for a file nobody in this application can change. The deployment
reports such a process on its own.

This is not the rule about portable values. That rule (`PortableValuesCheck`) asks whether the
TYPES of the values travelling survive the way there and back, and it ends a start where a declared
path does not resolve. Both can speak about one expression and they say different things: the other
one ends the start, this one says what the path costs you next year. Nor is this a verdict on DMN.
A decision gets its inputs as variables, and that is the intended way.

The Camunda 7 and the Camunda 8 adapter report what they read. The Process-Engine-API adapter does
not yet. Until an adapter reports, nothing is said, which is the same silence as for an adapter which
cannot read its models.

### 104. A citation of a decision names its file while the decision waits for its number

A decision is written before it has a number. It lives in `DECISIONS.pending/<story>.md` until the
main session numbers it, and the code which needs it is written in the same branch. So a citation
of a decision which has no number yet is a normal thing, and it has to survive the numbering.

Two spellings of it grew side by side. One names the file, `DECISIONS.pending/<story>.md`. The
other names the number in angle brackets, `<pending: n>`. A search for the path misses the second
one, a search for the word misses the first, and the branch which numbered five decisions searched
for one of them. Five citations stayed behind, two of them for weeks, in `NameClashAvoidanceSupport`
and in `HousekeepingWindowConfigurationTest`.

What holds now: a citation of a decision which is waiting names the file and nothing else. In
javadoc the path stands in a `{@code}` tag, because a path is plain text there and needs no
escaping. A citation of a numbered entry stays what it always was, `decision 7 in the repository's
DECISIONS.md`, and that whole phrase is what a citation says. A bare number in brackets is no
citation either, because nothing can tell it from any other number.

The angle-bracket form is out, and two spellings being one too many is only half the reason.
Javadoc wants the brackets escaped. The escaped form is three times as long as the number, so the
formatter breaks it over two lines, and that is what defeated the search both times.

**The check.** `bin/check-decision-citations.sh` reads the joined text of a file, not its lines. It
takes the leading `*`, `//` or `#` off a continued line first, so a citation wrapped anywhere is
one string again. It reports three things: a spelling which is not the file, a file which is gone
because the decision got its number, and a number no entry of `DECISIONS.md` carries. It reads the
whole repository in about a second, and it runs on every pull request.

Its own promise is the joining, so `--self-test` builds a small repository with one citation of
each kind, wraps two of them over two lines and checks that all of them are found. A line-by-line
search misses the wrapped ones, which is the whole reason the script exists.

One thing follows from the check for whoever writes such a document: a page which explains the
convention writes its examples with a placeholder where the number goes. An example with digits in
it is a citation as far as the check can tell, and it would report a file which was never meant to
exist.

What is open: the other five repositories keep decisions the same way and none of them has the
script. Rolling it out is a piece of work of its own, because each of them needs the CI job too.

### 105. A message an adapter asserts on is published as a phrase, and the adapter reads it from there

Two findings of `DeployedProcessVersionsCheck` were reworded when the platform moved its startup
findings into one block at the end of a start. What the sentence used to name, the subject line of
the finding names now. The platform pulled its own tests along in the same commit. Four assertions
in the Camunda 7 adapter stayed on the old words, and that came out two days later, in a pull
request which had nothing to do with it.

So: can the platform carry its startup messages as something an adapter imports, so that a
rewording is a build error rather than a search? Yes for the phrase a test looks for, no for the
whole message.

**Why not the whole message.** A message is built where it is reported. `DeployedProcessVersionsCheck`
writes text blocks with `%s` in them and picks between several of those, depending on what it
found. `reportUnservedTasks` chooses one of two texts and glues a remedy onto it. None of that is a
constant, and a constant holding the whole text would have to carry the same branches.

It is also more than a test needs. An adapter test asserts a SHORT fragment with the values put in,
such as `version '1' of process 'OldProcessVersionsProcess'` and `still run on this version`. One
constant per phrase is enough, and the test formats it with its own values.

The road is already there. An adapter compiles against `vanillabp-adapter-spi`, and that module
depends on `vanillabp-integration-spi`, where `StartupReport` lives. A public constant in the
integration SPI reaches the tests of every adapter at compile time. Reflection is not needed for
it.

The check itself lies in `migration-adapter/runtime`, and that module is on an adapter's test
classpath too. The Camunda 7 core holds it in test scope, and the integration tests get it through
the Spring Boot starter of the adapter. So the phrases may also stay beside the check which writes
them.

What cannot read them is `test-utils`. The modules which would declare them use test-utils in their
own tests, so Maven refuses the dependency back. That is why `ConstantOfAnotherModule` reads the
name of a VanillaBP table by reflection. A phrase needs that road only if test-utils has to read
it, and an adapter test does not go through test-utils.

**What it buys, and what it does not.** A constant which is renamed or removed is a compile error in
every adapter which reads it. A constant whose TEXT changes is silent, and that is the point: the
adapter test follows the new wording with no change at all. The wording is then tested in one
place, the platform, where the person rewording it stands.

It does not reach a message quoted outside a test, in a wiki page, a README or an `UPGRADE.md`
entry. Those stay a search, and the contributor skill `vanillabp-code-review` carries the two greps
for them.

What it costs: a constant which only the tests of other repositories read is published API from the
day it ships. It carries javadoc, and its name is one we keep. The precedent stands in the tree
already, because the names of the tables VanillaBP writes are public constants and nothing but
tests reads them.

It is built for the findings an adapter test asserted when this was decided, and the other startup
messages stay as they are until later work reworks them. The phrases stand beside the check which
writes them, so the name and the text are in one file and the SPI modules carry no message text:
`DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS`, `SERVED_BY_NO_METHOD`,
`STILL_RUN_ON_THIS_VERSION` and `STILL_RUN_ON_AN_OUTFADED_VERSION`, and
`DeliveryRecords.NO_DELIVERY_LOG`. The checks format their messages from them, so no phrase stands
in the tree twice. The tests of this repository keep writing the words out, because they are the
place where the wording is tested.

### 106. The delivery record says which kind of task an id is, and a record of the other kind elects nobody

`completeTask` with the id of a user task is a mistake applications really make. The BPMS reads
that key as a job key, no job carries it, and the answer is "not found" for a task which is
perfectly alive. What the caller used to read was a list of three causes, that the id is wrong,
that the task was completed long ago, that the workflow was canceled, and none of them was true.
Nobody knew the real one, so nobody could write it down.

VanillaBP can know it. A record is written per delivery, and the delivery knows whether it handed
out a task or a user task. So the record carries the kind, and two places read it: the election,
which must not route a command of the wrong kind, and the failure, which says what the id is.

**The kind is reported, not derived.** A user task of a Camunda 8 cluster runs under a job type of
VanillaBP's own, so the kind could be read off `TASK_DEFINITION`. That reading belongs to one
adapter and moves with every rename of that job type, while the table belongs to the platform.
`BpmnTaskSpec.optional` was the other candidate, and it is not the same question either: it says
that a handler for this element is optional, which is true of user tasks today and is a rule about
wiring, not about ids.

So `TaskInvocationContext.getTaskKind()` reports it, `default null`, with the two values the
platform tells apart anyway: `TaskKind.TASK` and `TaskKind.USER_TASK`, which is what
`Election.HOLDS_THE_TASK` and `HOLDS_THE_USER_TASK` ask a BPMS about. The column is `TASK_KIND`,
`VARCHAR(32)` and nullable, added by the changeset `vanillabp-task-delivery-kind-2.0.0`; the
MongoDB stores keep a `taskKind` field. No index: a record is found by its task, and the kind is
read from the row that lookup already returned.

A record which names no kind says nothing, and nothing contradicts nothing. That covers an adapter
which does not report the kind, a record written before the column existed, and a kind a newer
version of VanillaBP wrote which this one does not know. All three keep today's behaviour.

**The election is where the mix-up has to stop.** `DeliveryRecords.locate` answers the election from
the record, and it used to answer it for the wrong kind too: the record of the user task names the
adapter which holds it, so `completeTask` with that id was routed to that adapter and planned in
the outbox. The caller saw no error at all. The failure came at dispatch time, in a retry loop,
with nobody left to tell, and it was the BPMS saying "no such job", which is the message this
decision set out to improve.

So a record whose kind contradicts the operation does not answer the election. The adapters are
asked instead, each of them says it knows no such id, and
`MigrationProcessService.unknownToEveryBpms` reads the record again and answers. The walk costs one
round trip per adapter, and it is paid only when an application made this mistake.

What an adapter reporting the WRONG kind costs is the walk and nothing else. The probe of the other
kind then answers that it holds the task, the operation runs, and the application notices none of
it. That is the direction a mistake here has to fall in, and it is the reason the kind may be
reported at all without a check behind it.

What the message may say: which kind the id is and which method asks about that kind. It does not
say that the other method would succeed, because whether the task is still open is a question only
that call answers, and a message which promises otherwise is wrong half the time.

Where no record holds the id the caller gets the list of causes it always got, word for word, plus
one sentence naming `vanillabp.delivery.retention`. A record does not outlive it, so a task
completed longer ago than that is a task nobody remembers. An application without a store hears
nothing about a record, because there is none to miss.

It is not an idempotency check. It reads the table the deduplication of a delivery reads, which is
why it is tempting to call it one. The deduplication decides whether a delivery ran before. This
looks up what a caller's id is known as. Nothing about idempotency hangs on it, and a missing
record costs nothing but the sharper sentence.

What is left for later: a workflow id used as a task id, and the other way round, has the same
shape. The record carries `WORKFLOW_ID` next to `TASK_ID`, so `WorkflowNotFoundException` could
answer the same way. It is not done here, and it is a small piece of its own once this side stands.

### 107. A delivery nobody deduplicates is written down too, under a key which deduplicates nothing

The record of a task delivery answers three questions and only one of them is the deduplication:
which BPMS holds the task, which kind of id its id is, and whether this delivery was answered
before. Until now the third question decided whether the first two were written down at all. An
adapter which reported no delivery id got no row, so nothing could be read back about its tasks.

Camunda 7 is that adapter. `Camunda7UserTaskInvocationContext.getDeliveryId()` answers `null` on
purpose: a transaction of the engine creates every user task the token reaches, and the id comes
into being while it is created, so there is nothing a repetition could be recognised by.
`TaskDeliveryKey.of` answered `null` for a blank id, `DeliveryRecords.keyFor` passed it on, and
`MigrationProcessService` read that as "no delivery log for this delivery". Measured on 2026-10-01:
a user-task delivery reached its handler, the table held no row for it, and `completeTask` with
that user task's id answered with the list of three guesses. Which is exactly the mistake decision
106 wrote the kind of a task down for.

So the row is written. The absence of a delivery id takes the deduplication away and nothing else.

**The key says what the row is.** `TaskDeliveryKey.of` answers a `TaskDeliveryIdentity` now: the key
plus whether a repetition is recognised by it. Where the adapter reports no id the key keeps the
shape it always had and carries `(not-deduplicated)` with a random value where the id would stand:

```
c7|orders|Approval|CREATED|(not-deduplicated)7f3c…
```

The row is unique, so the primary key of the table and the `_id` of a MongoDB document hold without
a word of new code in any store. Nothing is ever looked up by such a key either, because the core
only reads a key it can build a second time. And whoever opens the table reads at the key itself
that this row answers no repetition, instead of having to know which adapter reports ids.

A key which simply left the last field empty was the other candidate. It is shorter and it is a
lie: two deliveries of two tasks would collide on it, and the second one would be read as a
repetition of the first.

What the switch still turns off: `deduplicate-deliveries` set to `false` remains the one case
which writes nothing at all. The property says the handlers of this application are idempotent
themselves, and the BPMS behind it may well repeat a delivery, so a row per repetition would show
one task as often as it was handed out to everything which reads the open work of a workflow. An
adapter which reports no id repeats nothing, so one delivery is one row there.

That is the whole reason the two cases are told apart rather than merged. Both look like "nothing is
deduplicated" from the configuration, and only one of them can afford a row per delivery.

The retention does with such a row the same thing it does with every other row, by the same
statement: `LAST_SEEN_AT` plus `vanillabp.delivery.retention`. What differs is where the clock
starts. A deduplicable row of an open task is kept alive by every redelivery it answers, so its
clock starts when the BPMS stops handing that task out. A row nobody deduplicates gets no
redelivery, so its clock runs from the moment the handler ran.

Nothing about correctness hangs on that number here, which is the point. For a deduplicable row the
retention IS the deduplication window and a row deleted too early runs business code twice. Here
the row answers no repetition in the first place, so a deleted one costs the saved BPMS round trip
of a task operation and the sharper sentence of a failure which names the kind of an id. An
installation whose user tasks stay open longer than seven days and which wants both raises the
retention; the startup says nothing about it, because there is nothing wrong to report.

Keeping such a row alive as long as its task is open was the alternative. It would mean the
retention asking which rows are open and never deleting those, and a task nobody ever completes
would then keep its row for good, an unbounded table in exchange for a sharper error message.

**What the startup check says.** `JdbcTaskDeliveryStore` used to explain the table by the
deduplication alone: "VanillaBP remembers every task delivery it processed in it, so a BPMS
repeating a delivery is answered from it instead of running the handler twice." That is now half
the truth, and the half which does not apply to Camunda 7 at all. The message names the second
purpose as well, so an operator asked to create the table learns why an embedded engine needs it
too.

Nothing new is reported. The warning about a missing store still fires only where an adapter may
repeat a delivery and deduplication is on, because its whole text is about running a handler twice.
An application whose only adapter reports no delivery id loses the two answers without a store, and
that is worth no boot-time warning: it has no way of knowing which of them it will ever ask for.

### 108. A row of the delivery log says what it is about, and the start of a workflow is one of them

The log of processed task deliveries holds a second kind of row: the start of a workflow. Such a
row carries the workflow aggregate and the BPMS' own id of its workflow and nothing about a task,
and `RECORD_KIND` is what tells it apart from a delivery.

The election of an operation about a workflow probed every configured adapter until one said it
held the workflow, and it remembered nothing but the hint of the cache. Decision 112 has it read
the start row first now. `PhaseOperation.AGGREGATE_CHANGED` is
`electedBy(Election.HOLDS_THE_WORKFLOW)`, and its javadoc says it in so many words: "No adapter ID
is persisted - the executing adapter is elected at dispatch time by probing". Nothing in VanillaBP
mapped an aggregate onto the workflow which runs it.

That mapping is also the one thing a BPMS answering from a read model cannot give shortly after a
start. On Camunda 8 the super-parent instance of a workflow is not stored anywhere: a job reports
its own instance and its immediate parent, and the chain to the root is walked with one search per
level against the secondary store, which is exactly the store that has not caught up yet. So the
row saves two things, the walk over the adapters and the walk up the parent chain, and it answers
in the window where no search answers.

Why it is a field of its own rather than a word in `OUTCOME`: that column is read back as
`WorkflowTaskOutcome.Kind.valueOf(...)` on the deduplication path, and its only legal values are
the names of that enum. A new constant of the enum would be the cheap way, and it would make a
START a RESULT of a delivery, so every `switch` over the enum gets a case which never occurs and
every question about open work keeps resting on a word in a field which means something else. A
store of its own next to the log would keep the log as it is and cost a second table, a second
retention and a second place the election has to look. A field of its own says the kind of the row
and leaves the outcome what it is. It is the only one of the three which separates the questions
about open work cleanly, and those questions are where a mistake here is silent: a row without a
task would be a phantom task in each of them, and the core reads them when a workflow wakes up. It
is a schema change, so it costs a migration after the release and had to happen before it. Stephan
chose it on 2026-10-03 and asked for it immediately, because the tests and the blueprints still to
be built carry the change better now than later.

`OUTCOME` loses its `NOT NULL` with it. A start reports no outcome, so it would have to borrow a
name, and a borrowed name is exactly what would make the row look like a delivery to the code
reading that column. A start row can never reach the deduplication either way: it is keyed by its
aggregate (`WorkflowStartKey`) and `recordedDelivery` is only ever called with a key built from a
delivery an adapter reported. Leaving the column empty is what keeps that true no matter what is
written later.

How the core learns the id: `PhaseOperationHandler.phaseTwo` returns `void`. The adapter creates
the instance, learns its key, and the core never sees it. Changing the signature would touch every
operation of every adapter for the sake of the one operation which has something to report, so
`PhaseTwoRequest` carries a sink instead, `reportStartedWorkflow(String)`, with a no-op where
nobody listens. An adapter which reports nothing keeps compiling and keeps behaving as before, and
`phaseTwo` stays a method which returns nothing. A workflow the BPMS started on its own needs no
new member at all: `BpmsInitiatedStartContext.getNativeInstanceId()` already carries the instance,
and the start runs in the transaction which persists the aggregate, so both values are known in one
place. What changed there is the javadoc, which documented the value for messages only.

The row is written at the earliest moment both values are known. For a start of the application
that is phase two, which runs after the caller's transaction committed, so a report of a changed
aggregate between the commit and the dispatch finds no id. That is right rather than a gap: the
instance does not exist yet either. And the row makes the id KNOWN. It does not make an operation
on an instance the BPMS has forgotten succeed, `WorkflowNotFoundException` comes as before, and it
says what was true when the workflow started rather than whether that workflow still runs.

There is one row per aggregate, and a second workflow writes over it. The key carries no workflow,
which is what makes the read a lookup by primary key. An aggregate may outlive its workflow and
carry a second one afterwards, and that case is not exotic: the release of an ended workflow is
bounded by a moment for exactly this reason. The second start therefore meets the row of the first
one and overwrites it. An insert which refused the duplicate would leave the id of the workflow
which ENDED standing, and that id is handed to an adapter as a hint and to an extension as an
answer, so it would send both looking for an instance the BPMS has forgotten. A row which already
names this workflow is left alone, which is what makes a start dispatched twice write nothing. The
overwrite needs an update, so it is a method of its own, `TaskDeliveryLog#recordWorkflowStart`,
whose default inserts and keeps what is there. A store of an application which does not implement
it answers the second workflow's id with the first one's until the period takes the row, and that
is said where the method is declared.

One gap stays open and is accepted. A start whose first dispatch created the instance and crashed
before the row was written is re-dispatched, and the re-dispatch mitigation skips it after the
adapter answers that it knows the workflow already. That answer is a `WorkflowAwareness` and
carries no id, so there is nothing to write down there. The row is then missing until the first
delivery of that workflow writes a delivery row carrying the id, which is what `workflowIdOf` reads
second. Closing it would mean letting a probe answer an id, which is a wider change than this
story.

Two periods, because two kinds of row are not alike. A delivery row may go as soon as nobody can
repeat that delivery. A start row is read for as long as somebody may ask which workflow an
aggregate belongs to, and that outlasts the workflow: changes to an aggregate keep arriving after
its workflow ended, from the application's own `@WorkflowEnded` method and from whoever maintains
the business data of a finished case. So `vanillabp.delivery.workflow-start-retention` is a period
of its own, thirty days by default and counted from the start, and `releaseRecordsOf` leaves the
row where it is when a workflow ends. Nothing about correctness hangs on the number. Once it
passed, the id is not known any more, so a caller which wanted to report a changed aggregate finds
no id and the report is dropped without a word.

The aggregate is a second sieve next to the period, and it is off by default. A period is a guess.
The exact question is whether anybody can still ask about that aggregate, and
`AggregatePersistenceAware#loadById` answers it: the aggregate, or `null` where there is none. So
`vanillabp.delivery.keep-workflow-start-while-aggregate-exists` deletes a row past its period only
where the aggregate is gone. The path from a row to that persistence is
`PhaseTwoRouter#processServiceOf`, where both platforms register every process service, and then
`MigrationProcessService#loadWorkflowAggregate`, which converts the serialized id, the path a
dispatched outbox entry already walks. Every answer but a plain "gone" keeps the row. A persistence
which does not implement `loadById` answers with an `UnsupportedOperationException`, and reading
that as "gone" would delete exactly the rows nobody can write again; it is said once per store.

Off costs nothing and leaves the period as the rule an installation can rely on. On costs one read
of the application's own database per expired row, which is why it is switched on rather than found
switched on: an installation with many short-lived workflows pays per workflow for a question
nobody asked. The number which would decide it the other way is how long an aggregate typically
outlives its workflow, and that belongs to an application rather than to us. What is measured: the
store side of one sieved pass took 15 ms at 100 expired rows, 22 ms at 1000 and 23 ms at 10000 on
H2 in memory, against 11, 14 and 67 ms for the plain delete; the 10000 case is the cap, which takes
a thousand rows per run and leaves the rest to the next hour. The read of the aggregate itself is
not in those numbers and belongs to the application's database, where it is a read by primary key,
and the cap is what bounds how many of them one run makes.

### 109. A message starts only the process of its own process service

`ProcessService#startWorkflowByMessage` starts the process of the process service it is called on,
and no other. A message which does not start that process is refused before phase one, with an
`IllegalArgumentException` which names the process and the messages which do start it.

Version 1 handed the message to the BPMS. Camunda 7 correlated it as a start message and Camunda 8
published it, so any process with a start event for that name started. Since the delivery log
remembers which workflow an aggregate belongs to, that became wrong in a quiet way. After phase two
the core writes the started workflow down under the process of the CALLER. When the message starts
another process, that row points at a workflow of a different process, and `workflowIdOf` under the
caller's process answers an id which does not belong to it. On Camunda 7 a second row for the same
instance also appears, written by the start listener under the right process.

Three other ways were weighed. Writing no row after a start by message keeps the row correct, but it
leaves the call itself free to start anything. Reporting the process id of the started instance
next to its id is an SPI change in the adapters' phase two and still lets the call start a foreign
process. Documenting the wrong answer leaves a trap. Refusing the call removes the case itself, and
it does so where the application made the mistake, with a message saying how to fix it.

How the check works:

- Adapters report the plain names of the message start events of each process while they wire it,
  through `BpmsInitiatedStartInvoker#reportStartMessages(adapterId, module, process, names)`. It is
  a report of its own and not a field of `BpmsInitiatedStartSpec`. The spec arrives without an
  adapter id, and the check has to ask the adapter which starts the workflow, because during a
  migration two adapters may deploy two versions of one process. A report of its own also makes
  "this adapter said nothing" plain to see: it never called. In a field, an old adapter which
  leaves it empty and a name the adapter could not read would look the same.
- The adapter asked is the first of the prioritized adapters, the one phase one elects for a start.
- The names come from the model the adapter deploys while the application starts. A message always
  starts the newest version of a process, and the newest version this application knows is the one
  it deploys.
- Names are compared plain, as the application passes them. An adapter strips its name-clash prefix
  before it reports and scopes the passed name before it correlates. The mode for message names is
  resolved at the workflow module, in both directions, so both sides are the same name.
- An adapter which does not report a process is not checked for it, and the start says so once per
  process, at INFO. That keeps an adapter running which does not make the call yet, and covers an
  adapter which cannot read models at all, such as the Process-Engine-API. An adapter which cannot
  name every message start event of a process (a name given as an expression) does not report that
  process either.
- The check runs at the call and not at the start of the application, because the name is only
  known at the call.

A Camunda 7 adapter also narrows the correlation itself to the process definition it deployed. Then
the rule still holds where the core cannot check, for example for a start event whose name is an
expression.

### 110. Every row of the delivery log carries the version of its process, and the core knows whether a missing one may still come

The log of processed task deliveries carries the version of the process definition a workflow runs
on, in a column of its own, `PROCESS_VERSION`. It is written for a delivery row and for the row about
the start of a workflow alike, wherever the adapter names it. An extension reads it together with
the BPMS' own id of the workflow, through `WorkflowElection#workflowStartOf`. The same answer names
the adapter of that row, the adapter which started the workflow. A workflow does not change its BPMS,
so that adapter holds the workflow until its end, and an extension learns it without an election.
`adapterIdOfWorkflow` and `locationOfWorkflow` stay as they are and still elect.

*Decision 111 changed that: both read the start row first now and elect only where it does not
answer.*

An extension may show details per version of a process: an implementation for version 3 of a model
and another one for version 4. To pick one it needs the version of the workflow, and it needs it from
the moment the workflow starts. On a BPMS which answers such a question from a read model, that is
the moment the read model knows nothing yet. The row written at the start is already the place where
the id of the workflow is known in that window, so the version goes into the same row. Without the
version an extension has two bad choices. It can report with an empty set of details, which replaces
the details already shown. Or it can report nothing and warn. Neither helps a user.

The version is written wherever the BPMS gives it, which includes every delivery row, and not only
in the start row. `TaskInvocationContext#getProcessVersion` delivers it with every task already, so
writing it costs nothing, and a delivery is often the first moment anybody hears of a workflow which
the application did not start itself. A start row without a version is answered with the version of
an open delivery of the same workflow, where one has it. Only rows of the BPMN process asked about
count for that, because a task of a called process carries the version of the called process.

An empty version means one of two things, and a reader has to tell them apart: "not yet" or "never".
The core asks the adapter instead of reading null, and it adds no new statement for that. The answer
exists already, per adapter and process. An adapter which counts versions registers a
`ProcessVersionCatalog` while it wires its BPMN, and an adapter which does not says so with
`reportNoProcessVersionCatalog`, naming whether its deliveries carry a version tag.
`ProcessVersions#reportsVersions` reads both, and the answer travels to the reader as
`WorkflowStart#versionsAreReported`. An adapter which said nothing either way counts as one which
reports none, because a reader waiting on such an adapter would wait forever.

An adapter which keeps a catalog and still writes a row without a version has a defect. The core says
so in a WARN, once per adapter and BPMN process, and writes the row all the same. Dropping a delivery
or a start because a piece of information is missing would cost far more than the information is
worth.

`PhaseTwoRequest#reportStartedWorkflow(String, String)` stands beside
`reportStartedWorkflow(String)`. The sink behind it, `WorkflowStartReport`, gets a default method with
two arguments which drops the version and passes the id on, so the interface stays a functional one.
An adapter which calls the old method compiles and runs as before, and its rows carry no version. A
test of an adapter which hands a lambda into a request keeps compiling too.

The same start may be reported twice. On Camunda 8 the adapter opens a worker at every start event,
so a start of the application is reported once by phase two and once by the BPMS. Only one of the two
may know the version. So a second report of the same workflow ADDS the version to a row which has
none, and it never changes a version which is there: that one is the version the workflow started
on. A second workflow of the same aggregate replaces the version together with the id, also with an
empty one, because the version of the workflow which ended says nothing about the one which runs now.

What it costs: a nullable `VARCHAR(255)` column in the relational table, added by a changeset of its
own, and a field in the MongoDB documents. No index, because the version is always read with the row
it stands in, and the key or an index which is there already finds that row. A store written by an
application gets the value through the new last component of `TaskDelivery`; the constructor without
it stays, so such a store keeps compiling and writes no version.

Stephan decided it on 2026-10-04: into the gate, and always carried where the BPMS gives it.

### 111. The election of an extension reads the start row before it asks a BPMS

`WorkflowElection#adapterIdOfWorkflow` and `#locationOfWorkflow` read the row written when the
workflow started before they elect. Where that row names an adapter which is still one of the
workflow's adapters, its adapter id and its workflow id are the answer. No BPMS is asked, and nothing
is waited for. Otherwise the election runs as it did before.

The row may answer because the adapter of the start row is the adapter which started the workflow. A
workflow does not change its BPMS: a new workflow starts in the first adapter of the list, and a
running one stays where it is and is looked for there. So the adapter which started a workflow holds
it until its end, and the row says the same thing the election would find out, only without the
round trip.

What it saves: an extension asks this while the application's transaction is open, for example when
it reports a changed aggregate. On Camunda 8 the election waits for the read model when the exporter
is behind, up to ten seconds, and the transaction, its database connection and its locks wait with
it. With the row that wait is gone.

No signature changes for a caller. What changes is that the two methods do not throw after a
workflow ended, for as long as its start row lives (`vanillabp.delivery.workflow-start-retention`).
Before, a BPMS which had forgotten an ended workflow made them throw, so an extension could not name
the adapter of a workflow whose aggregate changed after its end, although the start row lives longer
than the workflow exactly for that case. The answer says who started the workflow, not whether it
still runs, which is what the javadoc of both methods says.

The election runs as before where there is no row: a workflow started before the row existed, a row
past its period, a start whose adapter reports no workflow id, or the window after a first dispatch
which crashed before the row was written. It also runs where the row names an adapter which is not
configured for the workflow any more, because sending an extension to an adapter which is not there
helps nobody. Only the start row counts here; an open task row is not read for this, so the rule
stays the one stated above: who started the workflow.

Stephan decided it on 2026-10-04: into the gate, built together with the version in the rows.

### 112. An operation on a workflow reads the start row before it searches

The election of an operation about a workflow (`Election.HOLDS_THE_WORKFLOW`: correlating a
message, pushing a changed aggregate) reads the row written when the workflow started, in phase one
and in phase two. Where the election cache has no hint and that row names an adapter which is still
one of the workflow's adapters, the row is the hint. Its adapter is probed first, and an unknown
answer means "not visible yet": phase one plans the operation, and phase two gives the entry back
instead of consuming it. The BPMS' own id of the workflow goes to the probe as well, through the
four-argument `awarenessOfWorkflow`, so an adapter can ask its engine by key instead of searching.

Phase two of such an operation hands the workflow id of the row to the adapter, as
`PhaseTwoRequest#workflowId()`, where the row names the adapter which runs that phase two. An
adapter which addresses a workflow by its key can then skip the search for it.

The re-dispatch of a START reads the same row before it probes. Where the row names the adapter the
entry was written for, and was written at or after the moment the entry was planned, the earlier
attempt created the workflow, and the entry is consumed without asking anybody. A row older than the
entry belongs to an earlier workflow of the same aggregate (decision 116).

Before this, only the hint in the election cache told "not visible yet" apart from "nobody knows
this workflow". That hint lives in the memory of one node and expires after an hour. Measured
against Camunda 8.10.0 with a stopped exporter on 2026-10-04: after a restart, all 480 waiting
`aggregateChanged` entries were consumed as stale, and the values never arrived. The application got
`WorkflowNotFoundException` for workflows which were running. The same happens on a second node
which takes over an entry, and after the hint expired. The row is in the application's database,
it names the adapter which started the workflow, and a workflow does not change its BPMS (decision
111 states the same for an extension). So the row says what the cache said, and it says it after a
restart too.

For the re-dispatch it closes a quieter gap. The probe there is a search, and while the read model
is behind it answers "unknown" for a workflow the first attempt created. The start then runs again.
A second instance has jobs of its own, so the delivery log sees no repetition, and the window was as
long as the outage instead of the usual second.

The cache comes first, because it costs nothing to read and an election wrote it more recently than
the start. The row is read when the cache has nothing. Where the row proved right, its adapter and
workflow id go into the cache, so the next operation does not read the row again.

An operation about a task asks by the task's id, which is an exact question, and reads no row. A row
naming an adapter which is not configured for the workflow any more does not count, and its
workflow id is not handed to other adapters either. Without a row everything is as before.

What it costs, and what is accepted:

- A hint now lives as long as the row (`vanillabp.delivery.workflow-start-retention`, thirty days)
  instead of an hour. A workflow which ended and which its BPMS already forgot inside that period
  is not refused at the call any more. Its operation is planned, repeated and finally blocked, which
  is where it becomes visible. That is the residual a cached hint always had, only for longer.
- An earlier, ended workflow of the same aggregate is told apart from the one a re-dispatched START
  is about by the moment the entry was planned, for the row and for the probe
  (decision 116). An entry planned before that moment was recorded has none, and for
  it a second start whose first attempt failed before it created anything is still skipped.
- One read by primary key per operation on a workflow, and only where the cache has no hint.

### 113. Waiting for a read model uses time, not attempts

An entry whose dispatch an adapter answers with `PhaseTwoRetryLater` uses no attempt. The store
writes the window the adapter named as the next due time and leaves `ATTEMPTS` as it was. The
entry is blocked once `vanillabp.outbox.wait-for-visibility-at-most` passed since it was written.
The key is not set by default, and then it is the time the attempts of
`vanillabp.outbox.block-after-attempts` take with the growing backoff: forty-nine distances
between fifty attempts, 3 hours and 52.5 minutes with the defaults.

Until now every answer "not yet" counted an attempt, like a failure. On 2026-10-04 this was measured
against `camunda/camunda:8.10.0` with the exporter paused for twelve minutes and the application
running on. The adapter answered once every ten seconds, and the first entry was blocked after eight
minutes and eleven seconds. 153 entries of `aggregateChanged` ended blocked, and none of them came
back after the exporter caught up. Only an update of the row by hand opened them again. A database
which was away for the same twelve minutes blocked nothing, because the growing backoff spreads
fifty attempts over four hours. So a stopped exporter did more harm after eight minutes than a dead
database after four hours.

A read model which is behind is no failure of the entry. The attempt budget is there to stop an
entry which keeps failing, and it is measured in failures. A wait is measured in time, so it gets a
budget of time. The default gives a read model as long as a BPMS which is away, which is the outage
the attempt budget was chosen for.

How it works:

- `PhaseTwoOutboxProperties#hasWaitedForVisibilityLongEnough` is the one rule. The relational store
  of the core and the MongoDB store of each platform ask it when a dispatch is answered with
  `PhaseTwoRetryLater`, so all three behave the same.
- The clock starts at `CREATED_AT` respectively `createdAt`. No column was added. A younger call
  which replaces a waiting entry writes that moment anew, so the replacement starts the clock again,
  which is right: it is a new call. Earlier failures of the entry count toward that time as well.
  An entry which failed for three hours and then waits for a read model is blocked after one more
  hour. That is accepted, because both waits are spent by the same entry.
- The answer is handled before the attempt budget is looked at. An entry which used up attempts
  earlier is not blocked by an answer "not yet".
- The block itself counts one attempt, the same write every other block uses.
- Because the answer is not counted and the lease is given back, the next dispatch of the entry
  looks like a first one. A START is then not checked against the BPMS before it runs again. So an
  adapter throws `PhaseTwoRetryLater` only before its operation reached the BPMS. The javadoc of
  `PhaseTwoRetryLater` says so. The core throws it only before it calls an adapter. The Camunda 8
  adapter throws it for a push into a task's scope which its read model does not report yet, which
  is before anything reaches the BPMS.
- A value of zero or less ends the startup with a message naming the key.

Two sentences of earlier entries stop being true, and this entry replaces them. Both entries stay
as they are and carry a note pointing here.

- Decision 49, the sentence "The attempt is counted like any other, so `block-after-attempts` still
  ends a workflow which never becomes visible." An answer `PhaseTwoRetryLater` is no attempt now,
  and what ends such a workflow is `wait-for-visibility-at-most`.
- Decision 93, the part of a sentence "asking earlier than that costs a failed attempt out of the
  budget which blocks the entry". Asking earlier costs no attempt now. It only asks in vain, and the
  window stays the adapter's statement about its BPMS.

The gruelbox store lives in its own repository since decision 102. It still counts the answer as an
attempt and has to follow in that repository.

*Superseded in part by the repository of the gruelbox store: the last paragraph, saying that store still counts the answer as an attempt, since it now spends time instead of attempts as well, by the same rule `PhaseTwoOutboxProperties#hasWaitedForVisibilityLongEnough`.*

### 114. Phase two of a task-scoped push gets the row of its task

Phase two of an operation on a workflow which names a task, today only `aggregateChanged(aggregate, taskId)`, gets
the row of that task from the delivery log, as `PhaseTwoRequest#taskRecord()`. The core reads it with
`TaskDeliveryLog#recordOfTask`, under every BPMN process id the workflow service serves, the way the election of a
task operation reads it (decision 30). It hands the row over only where the row names the adapter which runs that
phase two, for the reason decision 112 gives for the workflow id: ids another BPMS gave its task mean nothing to
this adapter.

Measured on 2026-10-04 against `camunda/camunda:8.10.0`: the Camunda 8 adapter looked for the scope of the task
through the job behind the task id. The id of a user task is no job key, so every push into a user task was skipped
as "completed", also into an open one. The adapter needed to know which kind of task the id is, the workflow it runs
in, and whether it may ask its engine about it. The row has all of that already: `taskKind`, `workflowId`,
`bpmnElementId`, `processVersion`, and whether the task rests. A row exists only for a task the adapter left open
(`COMPLETION_PENDING`), and `taskClosedAt` stays empty until the application closed it. Asking an engine about a
task which rests disturbs nobody. Asking about a task a handler is working on may, on Camunda 8 it cuts the lock of
the job short.

Why the row and not a narrower type of the adapter SPI: the row is what the adapter wrote through the core when it
left the task open, so an adapter reads its own words back. A type of its own would copy five of its fields and
would have to follow every field the row gains. `TaskDelivery` is a type of the integration SPI, which the adapter
SPI depends on already.

What it costs: one read by task id per dispatch of a task-scoped push, the same indexed read a task operation pays
in phase one. A store which cannot answer it hands over nothing, and a failing read is treated like a missing row,
logged at DEBUG. No schema changes. `PhaseTwoRequest` gets an eighth component, and the seven-argument constructor
stays, so an adapter or a test which builds a request keeps compiling.

Like the workflow id, the row is a hint. An adapter which does not read it behaves as before.

### 115. The ERROR of a blocked entry links to the way back, and the ERROR of an extension stays beside it

An entry the outbox gives up on waits for a person. Until now the ERROR only said that it "has to be
cleaned up manually", and nothing said how. The Business Cockpit throws `PhaseTwoPermanentFailure`
for a change whose workflow or user task is missing after ten minutes, so its operators meet such
an entry in normal operation, not only after a broken deployment.

Each of the three dispatchers (JDBC, MongoDB on Spring Boot, MongoDB on Quarkus) now ends its
ERROR about a blocked entry with the address of the wiki page `Blocked-outbox-entries`. The page
says, per store, how to find the entry, open it again or delete it, and what opening does to the
idempotency key. The address is one constant, `PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE`, so
the lines and the tests reading them cannot name two different pages.

A blocked entry of an extension leaves two ERROR lines: one of the extension, which says what to
check in its own terms, and one of the dispatcher, which names the row and carries the stack trace.
Merging them was weighed and not taken. The extension does not know the row, and the dispatcher
does not know what the extension's failure means. Both lines stay.

A command or a user interface for the repair was not built here. That is roadmap line 125 (admin
UI). Until then the statements on the wiki page are the supported way.

### 116. A retried start counts only the workflows started after it was planned

An aggregate may outlive its workflow and carry a second one afterwards. This stays allowed, and
the wiki says it is supported but not recommended: a business id processed twice is usually better
modelled as two aggregates. Forbidding it was weighed and not taken. It would be a new rule which
refuses a start, and a start row which expires after its retention period could only half enforce
it.

What had to be repaired is a loss. Say the first attempt to dispatch the SECOND start fails before
it creates anything. The dispatch which repeats the entry asks whether the workflow exists already
(decision 112). The start row names the first workflow, Camunda 7 answers from its history, and
Camunda 8 searches without a state filter. All three say "it is there", the entry is consumed, and
the second workflow never starts.

So every START now carries the moment it was planned, in its arguments under
`PhaseTwoCall.ARG_PLANNED_AT`. The arguments were chosen over a column of the outbox table because
every store already persists them and hands them back, the gruelbox store and a store of an
application included. No rule deriving an idempotency key reads the moment, so two starts of one
aggregate still share their key. The moment is kept in milliseconds, which is what the stores keep
of the moment a start row is written.

The repeated dispatch uses the moment twice:

- A start row counts only where it was written at or after the moment. A row which is older belongs
  to an earlier workflow.
- The adapter's probe gets the moment, through the four-argument
  `MigratableProcessService#awarenessOfWorkflowForRedispatch`. An adapter compares it with the
  start time its BPMS reports and counts only the workflows started at or after it. The default
  ignores the moment and asks the three-argument method, which is what an adapter written before
  answers. Such an adapter still skips the second start in the case above.

An entry planned before this change carries no moment. For it everything counts, as before.

The clocks were weighed. The start row and the moment are both read from clocks of the application's
nodes. The row of this entry's own workflow is written after the dispatch, so it is younger than the
moment by the time the dispatch took. Two nodes whose clocks differ by more than that make the row
look older, and then the probe answers. The workflow exists, so a probe which can find it does. A
BPMS whose clock is behind the node's by more than the time between planning and the first dispatch
makes the workflow look older than the entry, and the probe answers "unknown". That costs a
duplicate start, which is the at-least-once residual the contract permits anyway. The other way
round, an earlier workflow would have to start within that same difference before the second start
is planned, and the earlier workflow has to END before then. Moving the moment back to allow for
the skew would turn the first error into a lost workflow, so neither the core nor an adapter does
that.

### 117. A report from inside the start of a workflow about the aggregate it builds does nothing

A `@WorkflowStartedByBpms` method builds the aggregate of a workflow the BPMS started. Code which
reports every change of an aggregate calls `ProcessService#aggregateChanged` there too, and an
extension such as the Business Cockpit reports the same change through the election
(`WorkflowElection`).

Neither report can work at that moment. The method runs before the start row is written (decision
111 and 112 read that row first), and on Camunda 8 the aggregate id is not even a variable of the
instance yet: the start listener job writes it when it completes. So nothing finds the workflow. The
cache holds no hint either, since VanillaBP did not start this workflow. Measured against the core on
2026-10-06 with an adapter which does not show the workflow, as Camunda 8 does not:
`ProcessService#aggregateChanged` saved the aggregate, asked each prioritized adapter once and threw
`WorkflowNotFoundException`, which ends the start and fails the listener job. The election of an
extension waits only where a hint exists, so it did not wait either: one question per adapter, then
an `IllegalStateException`.

And neither report is needed. Completing the start hands the values the aggregate shares to the BPMS,
and an extension which observes starts sees the new workflow when the start is done.

So the core keeps the running start on the thread for as long as the method runs
(`RunningBpmsInitiatedStart`, the workflow module and the BPMN process). While it runs, a report about
an aggregate which is NEW does nothing and writes a DEBUG line. New means the aggregate has no id, or
an id its persistence does not know. The id is often unknown before the start saves the aggregate,
which is the normal case for a generated id, so the rule cannot ask for it. The method may also
return an aggregate which exists already. That one may have other workflows and open tasks, and a
report about it runs as always. Where the persistence cannot load by id, nothing tells the two
apart, and the report runs as well.

An extension asks the same rule through `WorkflowElection#isInsideTheStartOf(Object)`. Its default
answers `false`, which is what an implementation written before answers.

Writing the start row before the method runs was weighed and not taken: the id is often not known
then.

The rule does not ask which BPMS runs the workflow. On Camunda 7 the method runs inside the engine's
own transaction. That engine keeps the aggregate id in the business key, and the key is written from
the method's result. So a report from inside the method finds nothing there either, and the start
writes the values when it is done, as on Camunda 8. The Process-Engine-API reports no start the
application did not ask for, so the method never runs there.

### 118. A blocked outbox entry keeps the reason of its last failed attempt

An entry the outbox gave up on said why only in the ERROR of its dispatcher. Logs rotate, and a
person who finds the entry days later, in a database client or in a report of an extension, saw
what was blocked but not why.

So every store VanillaBP owns keeps the reason next to the entry: the column `LAST_FAILURE` of the
JDBC table and the field `lastFailure` of the MongoDB collections on Spring Boot and Quarkus.

**What goes in.** The class and the message of what the dispatch threw, then the class and the
message of each cause, joined by `; caused by `, in one line. A cause is left out where the text
before it already contains it, which is what `new RuntimeException(cause)` produces. Line breaks
become blanks. The stack trace is not stored: the ERROR or WARN line of the same attempt carries it,
and the row only has to tell a person where to look. An entry blocked because it waited too long for
its BPMS starts with how long it waited and names `vanillabp.outbox.wait-for-visibility-at-most`,
then the last answer of the adapter, because that answer alone reads like an entry which is still
waiting.

**How long.** At most 1000 bytes of UTF-8, and the column is `VARCHAR(1000)`. Bytes and not
characters, because Oracle and DB2 count a `VARCHAR` in bytes by default. A text cut by characters
would fail there on the first message with an umlaut, and the write which fails would be the one
which blocks the entry. A cut text ends with `...` and is never cut inside a character. MongoDB
keeps the same text, cut the same way, so both stores show a person the same reason. 1000 is enough
for a wrapped failure with two or three causes and stays far below the row-size limit of MySQL.

**When it is written.** By every write which ends an attempt, in the same statement: a failed
attempt writes its reason with its new due time, an answer "not yet" (`PhaseTwoRetryLater`) writes
the answer, a block writes the reason which blocked. An attempt which got through empties the field,
because the field answers "why does this entry hang", and a dispatched entry does not hang. An
operator who opens a blocked entry again leaves the field alone, so the reason stays readable until
the next attempt overwrites or empties it.

**Where it is built.** Once, in `LastFailure` of the core, for all three dispatchers. Two stores
building the text each would drift apart, and the point is that the stores read the same.

Not taken: a column for the class of its own (no question reads it apart from the message), a
`CLOB` of full length (a `CLOB` is spelled differently on every database and not needed for one
line), and keeping the reason on a dispatched entry (it would answer a question the entry no longer
raises, and the retention deletes the entry anyway).

The gruelbox store of `io.vanillabp:gruelbox-phase-two-outbox` keeps no reason. Its table belongs
to gruelbox, which has no column for one.

### 119. Every task of a claimed process has a method or is marked as served elsewhere

Every task of a BPMN process a `@WorkflowService` class claims needs a `@WorkflowTask` method, or the
property `implemented-externally=true` saying that something other than this application serves it.
Without either, the start ends. With a method AND a line at the task itself, it ends as well, because
the method and the other worker would both answer the same task. This holds for a service task, a user task, a listener and anything else an
adapter hands over in `validateTaskWiring`. Decided by the maintainer on 2026-10-07.

Version 1 asked for the method. Its Camunda 7 and Camunda 8 adapters wired every user task with
`allowNoMethodFound=false`, so a user task without a method ended the start there too. Version 2 had let
such a task pass and named it once at INFO, on the reasoning in decision 54 that a user task worked
through a task list alone is a design. That reasoning is right about the model and wrong about the start.
The start cannot tell a task meant for a task list from a forgotten method, and only the application
can. So the application says it, once per task, and the INFO line is gone from all three adapters.

The same gap had other shapes: a listener on a Camunda 8 element whose job type nothing here names, a
Camunda 7 external task, a Process-Engine-API user task without a form reference. Each adapter had its own
answer, or none. Now there is one rule in the core and one key for all of them.

**The key.** `vanillabp.workflow-modules.<module>.workflows.<process>.tasks.<task>.implemented-externally`.
A task is named by its element id or by its task definition, and both work. Where both are written, the
element id wins, the order decision 51 gives every key an invocation offers. The element id of an element
also covers every listener on it. This is the first key with both names: the element id is where the
configuration of a task is going (the BPMN of VanillaBP's own in 2.1), and the task definition is what
the `tasks` level has named so far.

A listener is served only by a method naming its task definition. A method naming the element id serves
the element, and the element may have a method of its own, so `BpmnTaskSpec.listener()` tells the core
which kind of spec it holds. A listener is still marked by the element id as well as by the task
definition, but the two lines mean different things for it. The line for its own task definition is
about the listener and stands at the task. The line for the element id is about the element, so for the
listener it counts like a line from above: it covers the listener where no method serves it, and a
served listener on a marked user task keeps its method. Otherwise a user task without a form key, which
only its element id can name, could never be marked while one of its listeners has a method.

**The positions.** The key may be written at all eight positions of decision 53, and the most specific
one wins: the task for one adapter, the task, the workflow for one adapter, the workflow, and so on up to
`vanillabp.implemented-externally`. The adapter positions are why the key is read in the core with the
adapter id: the two adapters of a migration may disagree about one task, since one BPMS may have a
connector for it and the other not. `validateTaskWiring` therefore takes the adapter id now. The
three-argument form stays and reads no adapter position.

Where the line stands decides what a method next to it means (the maintainer, 2026-10-07). A line at
the task itself, by element id or by task definition, says something about that task, so a method for
the same task contradicts it and the start ends. A line above the task, at the workflow, the workflow
module or the application, with or without an adapter, covers only the tasks below it which have no
method. A task with a method keeps its method, and nothing is said about it. So a workflow whose user
tasks a task list works off writes one line for the workflow and keeps the methods of its service tasks.
A line at the task saying `false` takes one task back out of such a line.

**A list which goes out of date.** A list of task names in a configuration file goes out of date without
anybody noticing, and that was the reason against a key like this. So once a workflow module is deployed,
the core holds every task-level line against the element ids and task definitions the deployed models
carry, across all adapters, and warns about a line no model needs. A line at a workflow or at a workflow
module is held against the tasks it covers: where every task of the claimed processes below it has a
method, the line changes nothing, and the core warns about it as well. A line at the application is not
judged, because it covers several workflow modules and one module alone cannot tell. Only the models the module deploys
count. A version the BPMS only still holds is not asked, otherwise a task removed from the model would
have to stay marked forever. For the same reason a user task of such a held version is still not asked
for a method.

**Where it is read.** `WorkflowTaskWiring.isImplementedExternally` answers an adapter which refuses a shape
on its own before the core sees the task, a Camunda 7 external task say. The task still goes to
`validateTaskWiring` afterwards, so the rule and the message stay in one place.
`ImplementedExternally.propertyLine` writes the key for a message. A task name with a dot or a colon,
a Camunda 8 job type like `io.camunda:http-json:1` for example, needs brackets on Spring Boot and quotes
on Quarkus, and a colon has to be escaped in a properties file on both, so such a name gets one line per
platform.

**The second meaning of the workflow position.** For a process nobody claims, a line at the workflow
says that the process belongs to somebody else. Decision 120 holds that rule. The
deployment reads that line through `MigrationAdapterProperties.implementedExternallyAtTheWorkflow`
before anything is deployed, so it is not read in `ImplementedExternallyCheck`.

The predicate `WorkflowTaskWiring.isClaimedByAWorkflowService(module, process)` came with this change,
because the Camunda 8 adapter asks it while it sorts the listeners. It answers by
`resolveWorkflowAggregateIdName` and nothing else: only a claimed process has a workflow aggregate.

`ImplementedExternallyTest` holds the rule, both names, the adapter positions, the order of the eight
positions, a line above the task next to a method, a line at the task next to a method, a listener, and
both warnings about a line nothing needs.

### 120. A process nobody claims is not supported: it travels with its file and is otherwise left alone

Proposed by story 937. Decided by the maintainer on 2026-10-07.

A BPMN file goes to the BPMS as a whole. So a workflow module can deploy three kinds of process:

- A process a `@WorkflowService` class claims. VanillaBP stands in for it.
- A process nobody claims which is in the same file as a claimed one. It reaches the BPMS only
  because of that file.
- A process somebody else deployed into the same BPMS. VanillaBP never sees its file.

Only the first kind is supported. The rules:

1. VanillaBP changes the model of a claimed process only. An adapter adds no listener, no
   transaction flag, no multi-instance mapping and no correlation key to a process nobody claims.
   What the whole file needs still reaches it: the prefix of `use-prefix`, and a message element
   the file shares with a claimed process.
2. No check of an adapter ends the start because of a process nobody claims, and none warns about
   it.
3. No worker, subscription or listener serves a process nobody claims. A workflow of it stops at
   its first task and does not run into retries and an incident.
4. A process somebody else deployed is not touched at all.
5. The core ends the start over a deployed process nobody claims, unless the application says that
   the process belongs to somebody else:
   `vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`. The line
   for one adapter, `...workflows.<process>.adapters.<id>.implemented-externally=true`, works as
   well and marks the process for that adapter only. A line at the workflow module or at the
   application does not mark a process: it is written for many processes at once.

**Why the start ends.** Decision 21 made a process nobody claims a WARN, because the start could
not tell a forgotten workflow service from a process somebody else owns. With the line it can,
because the application says which one it is. It is the rule of decision 119 one level up: what
nobody serves ends the start, unless the application marks it. Version 1 ended the start here too:
its Camunda 7 parse listener asked for a workflow service for every process the engine parsed. So
for an application coming from version 1 nothing changes, except that there is now a way out.

The refusal names every such process of the module with its file, and both ways out: a workflow
service for a process the application serves, or the line. The platform's hint about a class which
carries `@WorkflowService` without being a bean stays part of it. It is a refusal and not a thrown
exception, so every module is reported in one start. Nothing is deployed to a BPMS once such a
process was found, because the start is about to end.

**A process called by a call activity** counts as claimed when a workflow service declares it, for
example in the `secondaryBpmnProcesses` of the workflow service of the caller.

**The predicate.** `WorkflowTaskWiring.isClaimedByAWorkflowService(module, process)` came with
decision 119. Every adapter asks it, and an extension gets the same interface as a bean. The core
answers it from its registry. The SPI default still answers through
`resolveWorkflowAggregateIdName`, for test doubles. The registry does not, because that name is
asked of the application's persistence, and an application on a BPMS with a business key need not
know it.

**`WorkflowElection.workflowIdOf`** and `workflowStartOf` refuse a process no workflow service
declares, the way `adapterIdOfWorkflow` and `locationOfWorkflow` always did. An empty answer would
read as "VanillaBP does not know this workflow" and hide a wrong process id.

`DeploymentServiceTest` holds the refusal, the line for the process and for one adapter, the line
for another adapter, a line at the module and a line saying `false`. `UnclaimedBpmnProcessTest`
holds both on both platforms.

### 121. An extension asks the core which process variables its methods read

An extension method may take a process variable with `@TaskParam`, like a `@WorkflowTask` method.
Some BPMS hand out only the variables a subscriber named. A Camunda 8 job worker is one, and so is a
Process-Engine-API subscription. Such an extension has to know the names before the first event
arrives. For `@WorkflowTask` methods the core answers that with `WorkflowTaskWiring#taskParameterNames`.
`ExtensionHandlers` had nothing like it, so the Business Cockpit noted the names itself while its
methods were bound. It could only key them by the lookup key, so its answer was for the whole
application and not for one BPMN process.

`ExtensionHandlers#taskParameterNames(annotationType, workflowModuleId, bpmnProcessId, lookupKeys)`
answers it now, from the registry which binds the methods. It differs from the `@WorkflowTask`
method in two places. It takes the annotation, because one registry serves the contracts of every
extension. And it takes the keys as a ranked list, because a method of an extension is found the way
decision 51 describes, and the same keys have to give the same answer as `hasHandler` and `invoke`.

The keys are walked like an invocation walks them, but for every version at once. The methods
serving the first key count. If one of them serves every version, the walk stops, because no later
key and no method serving every element can run for this element. Otherwise the next key counts as
well, and in the end the method serving every element. The answer is the union of what all counted
methods read: the event has to satisfy whichever of them runs.

Where several methods of one key split the versions between them, the method serving every element
counts although it may never run. Working that out would mean comparing version ranges and tags,
some of which only the BPMS can place. A variable too many costs a few bytes per event. A variable
missing gives a handler `null`. So the answer may hold one name too many, but never misses one.

The answer holds exactly the `@TaskParam` names the core binds, as the `@WorkflowTask` method does.
A `@TaskParam` parameter one of the extension's own binders claims is the extension's business, so
it is left out. Multi-instance values are left out too. They are not plain process variables of the
element, and the `@WorkflowTask` method does not name them either.

The method is a default method which answers nothing. Only the core implements `ExtensionHandlers`,
and an extension's test double should keep compiling. A default which answers nothing is wrong for
an application, but no application ever sees it.

### 122. An adapter asks what the methods of every extension read

Some BPMS hand a task to one channel only. The Process-Engine-API is one: a task goes to exactly
one subscription. So an extension gets no channel of its own there. It reads what the adapter's
subscription delivered, and that subscription has to fetch the variables the extensions read as
well. Decision 121 lets an extension ask which variables its own methods read, but it asks by its
annotation. An adapter does not know the annotations of the extensions, so it cannot ask that way.

`WorkflowTaskWiring#extensionTaskParameterNames(workflowModuleId, bpmnProcessId, lookupKeys)`
answers it. The registry walks the keys for each registered contract on its own, the way decision
121 describes, and returns the union over all contracts. Each contract is walked on its own because
the keys of one extension never decide which method of another one runs. The answer is per workflow
module and BPMN process, like `taskParameterNames`.

It is a second method next to `taskParameterNames`, not a wider answer of that one. The adapter
asks both and fetches the union. So `taskParameterNames` keeps answering what the `@WorkflowTask`
methods read and nothing else. The Camunda 8 adapter uses it for its job workers, and a job worker
of the adapter must not fetch what an extension reads through its own worker.

The adapter passes the keys in the order the extension looks an element up. For the Business
Cockpit that is the element id first, then the task definition. The adapter cannot know that order,
so the javadoc and the ADAPTER-AUTHORS guide state it.

The method is a default method which answers nothing, as in decision 121, so a test double compiled
against an older core keeps compiling. Camunda 7 and Camunda 8 do not need it. There an extension
opens its own listener or job worker and asks for its own variables.
