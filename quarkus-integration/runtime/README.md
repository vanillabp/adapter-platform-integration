![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus extension - runtime module

This is the runtime module of the VanillaBP Quarkus extension. At runtime, it connects to the
[VanillaBP migration adapter](../../migration-adapter).

## The transaction VanillaBP writes in

`TransactionRunnerProducer` builds both parts of it, once for the application, and produces them
as CDI beans. One is the platform's own `QuarkusTransactionRunner`. It brings JTA, and the CDI
request context which Panache and Hibernate need on the threads an adapter delivers on. The other
is the `TransactionRunnerResolver`. It says through which runner a given workflow aggregate is
written. The process services, `QuarkusPreCommitRegistrar`, the phase-two router and the
workflow-task registry use those beans and do not build runners of their own. So there is one
answer, not four answers which drift apart.

An extension injects the **resolver**, never a runner. Code which writes next to a workflow
aggregate asks `resolveFor(aggregateClass)` and writes through the runner it gets back. The
application may have added a `TransactionRunnerAware` bean for that aggregate, or a runner for
every aggregate. A runner of the extension's own would commit its entry separately from the
workflow it belongs to. If the application added nothing, the answer is the platform's runner. So
the resolver is the entry point in every case. The contract is the same on Spring Boot, where the
resolver is `vanillaBpTransactionRunnerResolver`. `ExtensionEnablementTest` is a small extension
which asks it, and it also tests the refusal of `inCurrent` outside a transaction, with its
message which guides the user. `ExtensionNextToApplicationTransactionTest` is an application which
brought a runner of its own next to that extension.

The platform's runner has `@Typed(QuarkusTransactionRunner.class)`, and this matters. CDI takes the
types of a bean from every supertype of what a producer returns. Without the annotation, the bean
would have `TransactionRunner` among its types. Then an application with a runner of its own would
make every `@Inject TransactionRunner` ambiguous, in the platform and in an extension. Limited to
the concrete class, the platform injects it by that class. The runner of the application stays the
only bean of the SPI type, and nobody is tempted to inject the bare type. The resolver still takes
the platform's runner out of its own second step by identity. So if the restriction is ever
dropped, it still does not silence a coverage verdict.

## Running a check right before the commit

In phase one, a check of a remote BPMS (business process management system) must not move the
process forward. But it may ASK, for example if the task still exists or if the model declares a
message. The answer can get old between the question and the phase-two dispatch. The later the
check runs, the smaller that window. So adapters hand their checks to the `PreCommitRegistrar` of
the adapter SPI (service provider interface), and `QuarkusPreCommitRegistrar` implements it here.

It first finds the transaction runner of the workflow aggregate
(`QuarkusTransactionRunnerResolver`). Then it calls `TransactionRunner#beforeCommit`. This detour
is the whole point. An application may bring its own unit of work. So the check has to hook into
the unit of work which VanillaBP really uses, not into the platform's JTA transaction.
`QuarkusTransactionRunner` implements the hook with an interposed JTA `Synchronization`. Its
`beforeCompletion()` runs the check. An exception there aborts the commit, and that is what a
failing phase-one check has to do. If the runner of an application does not implement the hook, it
runs the check at once. Every adapter behaved like that before the hook existed.

An earlier, wider mechanism was removed together with the introduction of the hook
(`EventualConsistencyTransactionSupport`: checks before the commit and actions after it, for each
transaction). It existed before the phase-two outbox took over the part after the commit. Nothing
called it any more, and it existed only on this platform.

## Phase-two outbox

Everything which an application sends to its BPMS leaves after the caller's transaction committed.
So every application needs a transaction outbox (`PhaseTwoOutbox` SPI of the
[migration adapter](../../migration-adapter)). The outbox schedules phase two inside the local
transaction. After the commit, it sends it reliably, also after a crash or restart, and retries
with a backoff.

The store does not belong to this module any more. `JdbcPhaseTwoOutboxStore` and
`JdbcPhaseTwoOutboxDispatcher` live in the core, and a Spring Boot application runs the same two
classes (decision 75 in `DECISIONS.md`). This module brings the bean `JdbcPhaseTwoOutbox`, and
the parts which really belong to Quarkus: a connection of the Agroal datasource, which takes part
in the running JTA transaction, and the JTA answers to "is a transaction running" and "tell me
when it committed" (`PhaseTwoOutboxTransaction`).

Entries go into the table `VANILLABP_PHASE_TWO_OUTBOX`. The entry stores all fields of the
`PhaseTwoCall` (operation discriminator, elected adapter ID, serialized aggregate ID) and the
idempotency key. A constraint of the table keeps the key unique, so scheduling the same thing twice
does nothing. The dispatcher claims due OPEN entries atomically (optimistic update with
attempts/backoff). It sends them through the `PhaseTwoRouter`, which the core owns. It does that
right after the commit, and also from a poller with a fixed delay (for crash recovery and
retries). It sends `vanillabp.outbox.dispatch-threads` entries at the same time, four by default.
The workflow aggregate decides which thread takes an entry. So two operations of one workflow keep
the order in which they were written. A successful dispatch marks the entry DONE. It is deleted in
the background once `vanillabp.outbox.retention` has passed. Until then, support can still read
the entry. Its `DEDUP_KEY` is replaced by its own ID, so the same operation can be planned again.
Entries which fail again and again are marked BLOCKED. The generated process-service beans
register themselves with the router (produced by `PhaseTwoRouterProducer`) when they are created.
They bring a converter which turns the serialized aggregate ID back into the aggregate's ID type.
The type is found by reflection over the aggregate class (see `AggregateIdConversion`). If it
cannot be found, the String is passed on. The poller uses a plain scheduled executor which starts
on `StartupEvent`. So the `quarkus-scheduler` extension is not needed. The outbox beans are only
registered if the Agroal capability is there (see
`VanillaBpBuildStepProcessor#buildPhaseTwoOutbox`). Applications may define their own
`PhaseTwoOutbox` beans in addition.

BOTH defaults (JDBC + MongoDB) may exist side by side. The outbox is chosen **for each workflow
aggregate** (`QuarkusPhaseTwoOutboxResolver`):

- The most specific `PhaseTwoOutboxAware` bean wins.
- Without one, the single active outbox is used. Defaults which are switched off
  (`vanillabp.outbox.jdbc.enabled`/`vanillabp.outbox.mongo.enabled`) or cannot be used do not
  count.
- With several active outboxes, the platform default for the technology which manages the
  aggregate is used. It is read from the persistence which VanillaBP resolved for the aggregate
  (`QuarkusPersistenceTechnology`).

Only if that cannot be found out, because the application brought the persistence itself, the
start ends and points to `PhaseTwoOutboxAware`. The resolution happens AT STARTUP, through an
inherited `StartupEvent` observer on `ProcessServiceBaseCdiBean`. If an outbox is missing, the
start fails and names the ways to fix it (`QuarkusStoreAttributionTest` for the resolution,
`OutboxStartupValidationTest` for the start which ends).

The resolver is a CDI bean of the neutral type
`io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver`
(`PhaseTwoOutboxResolverProducer`, `@Unremovable`). The process services use that bean and not
one of their own. An extension which writes its own phase-two entries into the transaction of a
workflow aggregate has to reach the same store, and it cannot work that out by itself. Which
default serves which technology, and whether it can be used, is answered by `PlatformDefaultStore`.
That is an interface of this module, not of an SPI. The Spring Boot integration offers
`SpringPhaseTwoOutboxResolver` as a bean for the same reason.
`MixedPersistenceStoreAttributionTest` is an extension which injects it and gets the store of each
aggregate of an application with two kinds of persistence.

Store names can be configured (`vanillabp.outbox.jdbc.table`, `vanillabp.outbox.mongo.collection`).
Every outbox instance needs its OWN store. Two dispatchers which poll the same store would compete
and send entries twice.

Some applications use MongoDB (`quarkus-mongodb-client`) instead of a JDBC datasource. For them,
there is a default based on MongoDB. `MongoPhaseTwoOutbox` writes into the collection
`vanillabp-phase-two-outbox` (same layout as the Spring Boot MongoDB outbox) of the database which
`quarkus.mongodb.database` configures. `MongoPhaseTwoOutboxDispatcher` claims, sends, blocks and
cleans up in the same way (atomic `findOneAndUpdate` claims, safe in a cluster without a lock).
MongoDB is not a JTA resource. So the outbox works on a **best-effort** basis. The entry is written
before the commit. On rollback, it is deleted on a best-effort basis. A crash in between leaves an
orphan entry. It ends up BLOCKED, with an ERROR which monitoring can see. A unique index over
`dedupKey` prevents duplicates. It is created automatically unless `vanillabp.outbox.create-schema`
is disabled. In that case, the startup names it, with the other indexes of `MongoSchema`, as
something the application has to create itself. If both Agroal and the MongoDB client are there,
the JDBC outbox always wins (as on Spring Boot, where the JPA outbox comes first).

### A call which carries a payload

A phase-two call carries identifiers. An extension may want to hand over the state it saw at its
sync point. Then it passes bytes as well:
`PhaseTwoCall.of(operation, module, process, aggregateId, adapterId, args, payload)`. The bytes are
not stored in the outbox entry. They are written into a store of their own, in the same
transaction. The entry names them by a reference in its arguments. Decision 62 in `DECISIONS.md`
gives the reasons.

For the outboxes which use JDBC, the store is a table `VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD`
(`vanillabp.outbox.jdbc.payload-table`). For the MongoDB ones, it is a collection
`vanillabp-phase-two-outbox-payloads` (`vanillabp.outbox.mongo.payload-collection`). It is created
together with the other tables unless `vanillabp.outbox.create-schema` is disabled.
`io.vanillabp:vanillabp-schema` describes it for Liquibase and Flyway.

Both names follow the outbox they belong to. If neither key is set, VanillaBP adds `_PAYLOAD` to
the name of the outbox table and `-payloads` to the name of the outbox collection. Some
applications rename their outbox to keep two deployments apart on one schema. Such an application
keeps the payloads apart too. A name written into one of the two keys is used as it is.

This costs one read by primary key for each attempt to send a call which carries a payload. A call
which carries none costs nothing, and it writes no row either. A payload is at most
`PhaseTwoCall.MAX_PAYLOAD_SIZE` bytes, one mebibyte. That limit holds for every store, so an
application meets the same limit whichever store it runs. On MongoDB, the bytes are a field of the
payload document. So MongoDB's own limit of 16 MB per document is never reached.

The payload is removed when its entry is marked dispatched. It is also removed together with that
entry once `vanillabp.outbox.retention` has passed. The retention counts from the entry. So an
entry which still waits, or which is blocked until somebody repairs it, keeps its payload as long
as it is in the store. The housekeeping removes by age only a payload which no entry names any
more. A crash between the two writes leaves such a payload behind. The housekeeping asks the
entries of its outbox first, and only for a payload which outlived the retention. In one case, a
payload is removed earlier: when a younger call replaced the entry which named it, see below.

### A younger call which takes the waiting entry's place

A call with a payload carries a state, and a state gets old. So such a call may ask to take the
place of the entry of its key which is still waiting, instead of being dropped because of it:
`outbox.scheduleReplacingWhatIsStillWaiting(call.replacingWhatIsStillWaiting())`. The entry keeps
its id and its key, and gets everything which the dispatch reads. The payload it named before is
removed in the same transaction. Decision 68 in `DECISIONS.md` says why the direction changed and
why the mark is on the call.

Neither store of this module replaces an entry which a dispatch has already taken. In that case,
the younger call becomes an entry of its own. Its `DEDUP_KEY` (or `dedupKey` on MongoDB) is set to
its own id, because the key belongs to the entry which is being sent. Two things together say
whether a dispatch has taken an entry: the attempts and the lease. If no attempt has ended and
nobody holds the entry right now, it is not taken. The update which replaces the entry checks both
conditions. So it is the same optimistic lock as the claim, and the two can never both win. Both
conditions are needed, because the attempts are written when an attempt ends. A dispatch which is
still running shows zero attempts, and only the lease shows it.

In every write which says how an attempt ended, both stores name the holder of the lease next to
the id: `LEASED_BY` on the JDBC store and `leasedBy` on the MongoDB one. So when another node took
over an entry while a node sent it, that node writes no result over the result of the node which
holds the entry now. `MongoADispatchWhichLostItsLeaseTest` tests the MongoDB half.

Both stores send on `vanillabp.outbox.dispatch-threads` lanes. The workflow aggregate of an entry
decides which lane takes it. So two operations of one workflow keep their order, and operations of
different workflows are sent at the same time. The MongoDB claim sorts by the time an entry was
written. The lanes keep the order in which they get the entries, but a collection answers in an
order of its own. `MongoEntriesOfOneAggregateKeepTheirOrderTest` tests both halves, and decision 77
in `DECISIONS.md` has the measurement behind it.

On the JDBC store, the claim reads its row once more after it won it. The select of the due entries
happens before the claim. Between the two, the row may have been replaced. Then the entry read
before would send the dispatch to a payload reference which no longer exists. MongoDB needs no
such read. Its claim is one `findOneAndUpdate`, and it answers with the document as it is at that
moment.

|                  |                 JDBC outbox (Agroal)                  |       MongoDB outbox (`quarkus-mongodb-client`)       |
|------------------|-------------------------------------------------------|-------------------------------------------------------|
| Enlisting        | JTA transaction (entry = part of TX)                  | best-effort (write before commit, delete on rollback) |
| Store            | table `VANILLABP_PHASE_TWO_OUTBOX`                    | collection `vanillabp-phase-two-outbox`               |
| Dedup            | unique constraint `DEDUP_KEY`                         | unique index `dedupKey`                               |
| Claim            | optimistic `UPDATE ... WHERE LEASED_UNTIL`            | `findOneAndUpdate` on a free `leasedUntil`            |
| Lease            | `LEASED_BY`/`LEASED_UNTIL`, renewed while dispatching | `leasedBy`/`leasedUntil`, renewed the same way        |
| Dispatch threads | `dispatch-threads` lanes, keyed by the aggregate      | `dispatch-threads` lanes, keyed by the aggregate      |
| DONE + retention | yes                                                   | yes                                                   |
| Selected when    | Agroal capability present                             | no Agroal, MongoDB client present                     |

Configuration (`QuarkusMigrationAdapterProperties`): `vanillabp.outbox.poll-interval`,
`vanillabp.outbox.attempt-frequency`, `vanillabp.outbox.block-after-attempts`,
`vanillabp.outbox.wait-for-visibility-at-most`,
`vanillabp.outbox.dispatch-threads`,
`vanillabp.outbox.retention` and
`vanillabp.outbox.create-schema`. Disable the last one to switch off the
`CREATE TABLE IF NOT EXISTS` DDL and the index creation, if you manage the schema yourself, for
example with Flyway or Liquibase. Then also create the unique constraint on `DEDUP_KEY` or the
unique index on `dedupKey` yourself. That column or field holds the idempotency key while the entry
waits for its dispatch, and the entry's own ID after that. This way, deduplication only covers the
operations which are still planned. On MongoDB, there is nothing else to create. A collection
appears with its first document. So at startup, VanillaBP reads what each of the three collections
has, and warns with the `createIndex` statement of every index which is missing.

## Optional extensions and the native image

`quarkus-mongodb-client` and `quarkus-mongodb-panache` are optional dependencies of this module. So
in an application which brought neither, no class which names one of their types may be reachable.
On the JVM, this takes care of itself, because a class is loaded when it is first used. A native
image is stricter. GraalVM links the whole reachable graph while it builds, and stops at the first
method it cannot resolve. We found three such methods. Each of them was called from a resolver which
every application runs.

Two rules keep them out of that graph.

- Whatever names a MongoDB type is a bean which the extension registers only under
  `Capability.MONGODB_CLIENT`: `MongoPhaseTwoOutbox`, `MongoTaskDeliveryLog` and
  `MongoClientDeploymentProbe`.
- The resolvers ask an interface instead of naming those classes. `PlatformDefaultStore` answers
  which persistence technology a store of the platform serves, and whether it can be used at all.
  `MongoDeploymentProbe` answers whether the MongoDB deployment is a replica set. Without the
  extension, the injection point is simply unsatisfied, and that is the answer which the resolver
  needs anyway.

The persistence detection has followed the rule from the start. `QuarkusPersistenceTechnology`
matches VanillaBP's own persistence implementations by their class NAME.

The module [native-image-tests](../integration-tests/native-image-tests) makes sure that all of
this stays true. It is an application with H2 and no MongoDB anywhere, and the test is that its
native build works. Locally:

```shell
./mvnw install -DskipTests -am -pl quarkus-integration/deployment,\
  bpms-double/quarkus/deployment,\
  quarkus-integration/integration-tests/native-image-tests
./mvnw package -pl quarkus-integration/integration-tests/native-image-tests -Dnative
./quarkus-integration/integration-tests/native-image-tests/target/*-runner
```

The deployment modules of both extensions are named explicitly. The augmentation part of an
extension is not a dependency of the application. With `-am` alone, they would come from the local
repository. There, an old copy hides whatever the build step was just changed to do.

Docker pulls the Mandrel builder image, so you do not have to install GraalVM. The last line is
there because a binary which cannot start proves nothing. The application's main method starts it,
starts a workflow and reads the aggregate back. Its exit code says whether that worked. This found
the second half of the problem: the BPMN resources were missing from the image. In CI, the job
`native-build` runs the same three commands.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
