![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus extension - runtime module

The VanillaBP Quarkus extension's runtime module. It is responsible for bridging to the
[VanillaBP migration adapter](../../migration-adapter) at runtime.

## The transaction VanillaBP writes in

`TransactionRunnerProducer` builds both halves of it, once for the application, and produces them
as CDI beans: the platform's own `QuarkusTransactionRunner` (JTA plus the CDI request context
Panache and Hibernate need on the threads an adapter delivers on) and the
`TransactionRunnerResolver` saying which runner a given workflow aggregate is written through. The
process services, `QuarkusPreCommitRegistrar`, the phase-two router and the workflow-task registry
use those beans rather than building runners of their own, so there is one answer instead of four
which drift.

What an extension injects is the **resolver**, never a runner. Something writing next to a
workflow aggregate asks `resolveFor(aggregateClass)` and writes through what comes back: the
application may have contributed a `TransactionRunnerAware` bean for that aggregate or a runner
serving every aggregate, and a runner of the extension's own would commit its entry separately
from the workflow it belongs to. Where the application contributed nothing, the answer is the
platform's runner, so the resolver is the entry point in every case. The contract is the same on
Spring Boot, where the resolver is `vanillaBpTransactionRunnerResolver`. An extension in miniature
asking it, and the guiding refusal of `inCurrent` outside a transaction, is
`ExtensionEnablementTest`; an application which brought a runner of its own next to that extension
is `ExtensionNextToApplicationTransactionTest`.

The platform's runner carries `@Typed(QuarkusTransactionRunner.class)`, and that is not
cosmetic. CDI derives the types of a bean from every supertype of what a producer returns, so
without it the bean would carry `TransactionRunner` among its types and an application with a
runner of its own would make every `@Inject TransactionRunner` ambiguous, inside the platform and
in an extension alike. Cut down to the concrete class, the platform injects it by that class, an
application's runner stays the only bean of the SPI type, and nobody is tempted to inject the bare
type. The resolver still takes the platform's runner out of its own second step by identity, so a
coverage verdict is not silenced by it should the restriction ever be dropped.

## Running a check right before the commit

A phase-one check of a remote BPMS must not advance the process, but it may ASK - whether the
task still exists, whether the model declares a message - and the answer can go stale between
the question and the phase-two dispatch. The later the check runs, the smaller that window, so
adapters hand their checks to the `PreCommitRegistrar` of the adapter SPI and
`QuarkusPreCommitRegistrar` implements it here.

It resolves the transaction runner of the workflow aggregate first
(`QuarkusTransactionRunnerResolver`), then calls `TransactionRunner#beforeCommit`.
That indirection is the point: since an application may bring its own unit of work, the check
has to be hooked into the unit of work VanillaBP actually uses, not into the platform's JTA
transaction. `QuarkusTransactionRunner` implements the hook with an interposed JTA
`Synchronization` whose `beforeCompletion()` runs the check - throwing there aborts the commit,
which is what a failing phase-one check has to do. A runner of an application which does not
implement the hook runs the check immediately, the behaviour of every adapter before the hook existed.

An earlier, wider mechanism (`EventualConsistencyTransactionSupport`: probes before the commit
plus actions after it, per transaction) was removed with the hook. It existed before the
phase-two outbox took over the after-commit half, had no caller left, and existed on this
platform only.

## Phase-two outbox

Everything an application sends to its BPMS leaves after the caller's transaction
committed, so every application needs a transaction outbox (`PhaseTwoOutbox` SPI of the
[migration adapter](../../migration-adapter)): it schedules phase two within the local
transaction and dispatches it reliably after the commit (also after a crash/restart,
retrying with a backoff).

The store is not this module's own any more: `JdbcPhaseTwoOutboxStore` and
`JdbcPhaseTwoOutboxDispatcher` live in the core and a Spring Boot application runs the same
two classes (decision 75 in `DECISIONS.md`). What this module brings is the bean
`JdbcPhaseTwoOutbox` and the halves which really are Quarkus': a connection of the Agroal
datasource, enlisted in the running JTA transaction, and the JTA answers to "is a
transaction running" and "tell me when it committed" (`PhaseTwoOutboxTransaction`).

Entries go into the table
`VANILLABP_PHASE_TWO_OUTBOX`. The entry persists all fields of the
`PhaseTwoCall` (operation discriminator, elected adapter ID, serialized aggregate
ID) plus the idempotency key, enforced unique by a constraint of the table —
duplicate schedules are a no-op. The dispatcher claims due OPEN
entries atomically (optimistic update with attempts/backoff) and dispatches them
through the core-owned `PhaseTwoRouter` — right after the commit and by a
fixed-delay poller (crash recovery and retries). It dispatches
`vanillabp.outbox.dispatch-threads` entries at the same time, four by default, and the
workflow aggregate decides which of the threads takes an entry, so two operations of one
workflow keep the order they were written in. Successful dispatches mark the
entry DONE (deleted asynchronously once `vanillabp.outbox.retention` passed — the
entry stays readable for support, while its `DEDUP_KEY` is replaced by its own ID so
a repetition of the same operation can be planned again); repeatedly failing entries are marked
BLOCKED. The generated process-service beans register themselves with the router
(produced by `PhaseTwoRouterProducer`) at bean creation, including a converter
turning the serialized aggregate ID back into the aggregate's ID type (determined
by reflection over the aggregate class, see `AggregateIdConversion`; if
undeterminable, the String is passed through). The poller uses a plain scheduled
executor started on `StartupEvent`, so the `quarkus-scheduler` extension is not
required. The outbox beans are only registered if the Agroal capability is present
(see `VanillaBpBuildStepProcessor#buildPhaseTwoOutbox`); applications may define
their own `PhaseTwoOutbox` beans in addition.

BOTH defaults (JDBC + MongoDB) may coexist and the outbox is
selected **per workflow aggregate** (`QuarkusPhaseTwoOutboxResolver`): the most
specific `PhaseTwoOutboxAware` bean wins; without one, the single active outbox is
used (deactivated - `vanillabp.outbox.jdbc.enabled`/`vanillabp.outbox.mongo.enabled`
- or unusable defaults are not considered), and with several active outboxes the
platform default matching the technology which manages the aggregate is used, read
off the persistence VanillaBP resolved for it (`QuarkusPersistenceTechnology`). Only
where that cannot be told - the application brought the persistence itself - does the
startup end guiding towards `PhaseTwoOutboxAware`. Resolution happens AT STARTUP
via an inherited `StartupEvent` observer on `ProcessServiceBaseCdiBean` - a missing
outbox fails the boot naming the remedies (`QuarkusStoreAttributionTest` for the
resolution, `OutboxStartupValidationTest` for the boot which ends).

The resolver is a CDI bean of the neutral type
`io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver`
(`PhaseTwoOutboxResolverProducer`, `@Unremovable`), and the process services use that
bean rather than one of their own: an extension writing phase-two entries of its own
into the transaction of a workflow aggregate has to reach the same store, and it
cannot work that out itself - which default serves which technology, and whether it
is usable, is `PlatformDefaultStore`, an interface of this module and of no SPI. The
Spring Boot integration offers `SpringPhaseTwoOutboxResolver` as a bean for the same
reason. An extension injecting it and getting the store of each aggregate of a
two-persistence application is `MixedPersistenceStoreAttributionTest`.

Store names are configurable
(`vanillabp.outbox.jdbc.table`, `vanillabp.outbox.mongo.collection`); every outbox
instance needs its OWN store (two dispatchers polling the same store would compete
and double-dispatch).

For applications using MongoDB (`quarkus-mongodb-client`) instead of a JDBC
datasource, a MongoDB-based default is provided: `MongoPhaseTwoOutbox` writes into
the collection `vanillabp-phase-two-outbox` (same layout as the Spring Boot MongoDB
outbox) of the database configured by `quarkus.mongodb.database`, and
`MongoPhaseTwoOutboxDispatcher` claims/dispatches/blocks and cleans up analogously
(atomic `findOneAndUpdate` claims, cluster-safe without a lock). Since MongoDB is
no JTA resource, the outbox operates **best-effort**: the entry is written before
the commit; on rollback it is deleted best-effort (a crash in between leaves an
orphan which ends up BLOCKED with a monitorable ERROR). Deduplication is
enforced by a unique index over `dedupKey`, created automatically unless
`vanillabp.outbox.create-schema` is disabled. If both Agroal and the MongoDB client
are present, the JDBC outbox wins deterministically (consistent with Spring Boot
where the JPA outbox is ordered first).

### A call which carries a payload

A phase-two call carries identifiers, and an extension which wants to hand over the state
it saw at its sync point passes bytes as well:
`PhaseTwoCall.of(operation, module, process, aggregateId, adapterId, args, payload)`. The
bytes do not travel in the outbox entry. They are written into a store of their own, in the
same transaction, and the entry names them by a reference in its arguments - the reasoning
is decision 62 in `DECISIONS.md`.

The store is a table `VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD` for the JDBC-backed outboxes
(`vanillabp.outbox.jdbc.payload-table`) and a collection `vanillabp-phase-two-outbox-payloads`
for the MongoDB ones (`vanillabp.outbox.mongo.payload-collection`). It is created together with
the other tables unless `vanillabp.outbox.create-schema` is disabled, and
`io.vanillabp:vanillabp-schema` describes it for Liquibase and Flyway.

Both names follow the outbox they belong to. Where neither key is set, VanillaBP appends
`_PAYLOAD` to the name of the outbox table and `-payloads` to the name of the outbox collection. So
an application which renames its outbox to keep two deployments apart on one schema keeps the
payloads apart too. A name written into one of the two keys is used as it stands.

The form costs one read by primary key per dispatch attempt of a call which carries a
payload, and nothing at all for a call which carries none - such a call writes no row
either. A payload is at most `PhaseTwoCall.MAX_PAYLOAD_SIZE` bytes, one mebibyte, and that
limit holds for every store, so an application meets the same one whichever store it runs.
On MongoDB the bytes are a field of the payload document, which is why MongoDB's own limit
of 16 MB per document is never reached.

The payload is removed when its entry is marked dispatched. What a crash between the two
writes leaves behind, and the payload of an entry blocked longer than
`vanillabp.outbox.retention`, is removed by the age sweep which rides the housekeeping of
each store. A payload is removed earlier in one case: where a younger call replaced the
entry which named it, see below.

### A younger call which takes the waiting entry's place

A call carrying a payload carries a state, and a state goes stale. So such a call may ask
to take the place of the entry of its key which is still waiting, instead of being dropped
against it: `outbox.scheduleReplacingWhatIsStillWaiting(call.replacingWhatIsStillWaiting())`.
The entry keeps its id and its key, gets everything the dispatch reads, and the payload it
named before is removed in the same transaction. Decision 68 in `DECISIONS.md` says why the
direction turned and why the mark hangs on the call.

Both stores of this module refuse to replace an entry a dispatch has already taken; the
younger call becomes an entry of its own then, with its `DEDUP_KEY` respectively `dedupKey`
set to its own id, because the key belongs to the entry on its way. What says whether a
dispatch has taken an entry is the attempts counter both dispatchers write when they claim
one, and the update which replaces carries `ATTEMPTS = 0` respectively `attempts: 0` - the
same optimistic lock the claim is, so the two can never both win.

On the JDBC store the claim reads its row once more after it won it. The select of the due
entries happens before the claim, and between the two the row may have been replaced, so the
entry read then would send the dispatch to a payload reference which is gone. MongoDB needs
no such read: its claim is one `findOneAndUpdate` and answers with the document as of that
moment.

|                  |           JDBC outbox (Agroal)           |       MongoDB outbox (`quarkus-mongodb-client`)       |
|------------------|------------------------------------------|-------------------------------------------------------|
| Enlisting        | JTA transaction (entry = part of TX)     | best-effort (write before commit, delete on rollback) |
| Store            | table `VANILLABP_PHASE_TWO_OUTBOX`       | collection `vanillabp-phase-two-outbox`               |
| Dedup            | unique constraint `DEDUP_KEY`            | unique index `dedupKey`                               |
| Claim            | optimistic `UPDATE ... WHERE ATTEMPTS=?` | `findOneAndUpdate`                                    |
| DONE + retention | yes                                      | yes                                                   |
| Selected when    | Agroal capability present                | no Agroal, MongoDB client present                     |

Configuration (`QuarkusMigrationAdapterProperties`): `vanillabp.outbox.poll-interval`,
`vanillabp.outbox.attempt-frequency`, `vanillabp.outbox.block-after-attempts`,
`vanillabp.outbox.dispatch-threads`,
`vanillabp.outbox.retention` and
`vanillabp.outbox.create-schema` (disable the `CREATE TABLE IF NOT EXISTS` DDL /
index creation to manage the schema manually, e.g. by Flyway or Liquibase — then
also create the unique constraint on `DEDUP_KEY` / the unique index on `dedupKey`
yourself — that column respectively field carries the idempotency key while the entry
waits for its dispatch and the entry's own ID afterwards, which is what keeps the
deduplication window to the operations still planned).

## Optional extensions and the native image

`quarkus-mongodb-client` and `quarkus-mongodb-panache` are optional dependencies of this
module, so every class naming one of their types has to stay unreachable in an application
which brought neither. On the JVM that takes care of itself, because a class is loaded when
it is first used. A native image is stricter: GraalVM links the whole reachable graph while
it builds and stops at the first method it cannot resolve. Three such methods were found,
each of them called from a resolver every application runs.

Two rules keep them out of that graph.

- Whatever names a MongoDB type is a bean the extension registers only under
  `Capability.MONGODB_CLIENT`: `MongoPhaseTwoOutbox`, `MongoTaskDeliveryLog` and
  `MongoClientDeploymentProbe`.
- The resolvers ask an interface instead of naming those classes. `PlatformDefaultStore`
  answers which persistence technology a store of the platform serves and whether it is
  usable at all, `MongoDeploymentProbe` answers whether the MongoDB deployment is a replica
  set. Without the extension the injection point is simply unsatisfied, and that is the
  answer the resolver needs anyway.

The persistence detection has followed the rule from the start: `QuarkusPersistenceTechnology`
matches VanillaBP's own persistence implementations by their class NAME.

What keeps all of this honest is the module
[native-image-tests](../integration-tests/native-image-tests): an application with H2 and no
MongoDB anywhere, whose native build is the assertion. Locally:

```shell
./mvnw install -DskipTests -am -pl quarkus-integration/deployment,\
  quarkus-integration/integration-tests/dummy-adapter/deployment,\
  quarkus-integration/integration-tests/native-image-tests
./mvnw package -pl quarkus-integration/integration-tests/native-image-tests -Dnative
./quarkus-integration/integration-tests/native-image-tests/target/*-runner
```

The deployment modules of both extensions are named explicitly, because an extension's
augmentation part is no dependency of the application: `-am` alone would leave them to the
local repository, where a stale copy hides whatever the build step was just changed to do.

Docker pulls the Mandrel builder image, so no GraalVM has to be installed. The last line
is there because a binary which cannot start proves nothing: the application's main boots
it, starts a workflow and reads the aggregate back, and its exit code says whether that
worked. It found the second half of the problem, the BPMN resources missing from the image. In
CI the job `native-build` runs the same three commands.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
