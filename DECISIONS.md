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
operation can be identified, `PhaseTwoOperation` gives it an idempotency key, and where it cannot
(a broadcast signal has nothing to deduplicate on, and `AGGREGATE_CHANGED` reads its values at
dispatch time) the entry carries none and the residual duplicate is documented rather than hidden.
A test which called VanillaBP has to wait for the BPMS to catch up instead of reading its state in
the next line.

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
likely to hold the workflow, so the probe is retried briefly and then fails by name. There is no
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
it ten times. The same cut runs through the concurrent-token check and the transaction annotations:
the adapter or the platform integration reports facts, the core decides.

Repeating a failure which will never succeed costs an operator ten log lines and a blocked entry
either way, so the classification errs towards repeatable and each adapter's README lists what it
calls permanent and why.

### 13. A delivery whose version the BPMS did not report is served only by an unrestricted method

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

### 16. The two tables VanillaBP owns come from one schema artifact

The phase-two outbox and the task delivery log are ours, so `vanillabp-schema` ships one
database-neutral Liquibase changelog for them plus the SQL generated from it per database, and the
runtime DDL creates the same columns. Nothing else is shipped: gruelbox's table belongs to
gruelbox and the engine tables belong to the engine, both documented rather than copied.

Where the application creates the schema itself, both stores check at startup that their table AND
its columns exist, and name the table, the property and the artifact to apply. Checking only the
table is what let a later column slip through once.

### 17. An adapter id is an identity and is never renamed while anything is still open

Every persisted record which belongs to a workflow carries the adapter id it was written for: the
outbox entry so a pending call reaches the BPMS it was planned for, the delivery record so a
redelivery is recognised as the same delivery. Renaming an id therefore orphans them, and the
symptom shows up much later as a workflow which was persisted and never started, or as a handler
which runs a second time.

So the ids the stores still hold open work for are asked once at startup and compared with the
configured ones, and a mismatch warns with both readings and with the property which acknowledges
it. A warning, not a failed boot, because the entries are waiting rather than lost. A store which
cannot answer says so by returning nothing and the check stays quiet.

### 18. Reading a metric costs no more than reading a number

A gauge is read on every scrape, in every instance, so a gauge which counts rows in a table turns
monitoring into load. Every measurement which is not already in memory goes through
`CachedGaugeValue`, whose window is `vanillabp.metrics.gauge-cache`, and the wrapping happens once
in `MicrometerVanillaBpMetrics` so no store can forget it.

A store which cannot answer at all leaves a gap rather than reporting zero, because zero pending
calls is a statement somebody will act on. The same reasoning fixes the naming: the prefix is
`vanillabp.`, the place is a TAG and never part of the name, and nothing which is unique per
workflow ever becomes a tag, or every workflow would get its own time series.
