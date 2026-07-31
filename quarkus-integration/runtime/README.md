![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus extension - runtime module

The VanillaBP Quarkus extension's runtime module. It is responsible for bridging to the
[VanillaBP migration adapter](../../migration-adapter) at runtime.

## Eventual consistency and transactions

Remote BPMSs (e.g. Camunda 8) are eventually consistent: after starting a workflow the
BPMS may not know the workflow instance yet. To deal with this, the class
`EventualConsistencyTransactionSupport` allows to register, per transaction,

1. **probes** which run right before the transaction is committed (a failing probe
   marks the transaction rollback-only) and
2. **after-commit actions** which run right after the transaction was committed
   successfully (e.g. to inform the remote BPMS). Each action receives the result of
   its probe.

### Design

The bean is `@ApplicationScoped` and keeps its per-transaction state in the JTA
`TransactionSynchronizationRegistry` (`getResource()`/`putResource()`), so no
transaction-scoped bean lifecycle is needed. On the first `addProbeAndAction(...)` of a
transaction an interposed `Synchronization` is registered:

- probes run and `setRollbackOnly()` is called in `beforeCompletion()` — both is
  allowed there;
- after-commit actions run in `afterCompletion(status)` if
  `status == Status.STATUS_COMMITTED`.

### Why not `@TransactionScoped` + `@PreDestroy`?

A previous implementation used a `@TransactionScoped` bean running the probes in a
`@PreDestroy` callback. That cannot work in Quarkus: the transaction-scoped context is
destroyed in an `afterCompletion()` synchronization (see
[Quarkus issue #36880](https://github.com/quarkusio/quarkus/issues/36880)), so
`@PreDestroy` runs *after* commit/rollback. At that point the transaction status is
never `STATUS_ACTIVE`, `setRollbackOnly()` would be too late and the JTA specification
forbids registering further synchronizations in `afterCompletion()`.

## Phase-two outbox

Adapters of remote BPMS report `needsTwoPhaseCommitForStartingWorkflows()` and
require a transaction outbox (`PhaseTwoOutbox` SPI of the
[migration adapter](../../migration-adapter)) which schedules phase two of starting a
workflow within the local transaction and dispatches it reliably after the commit
(also after a crash/restart, retrying with a backoff).

This module provides an own JDBC/JTA-based default implementation (gruelbox does not
support JTA): `JdbcPhaseTwoOutbox` writes entries into the table
`VANILLABP_PHASE_TWO_OUTBOX` using a connection of the Agroal datasource which is
enlisted in the running JTA transaction. The entry persists all fields of the
`PhaseTwoCall` (operation discriminator, elected adapter ID, serialized aggregate
ID) plus the idempotency key, enforced unique by a constraint of the table —
duplicate schedules are a no-op. `JdbcPhaseTwoOutboxDispatcher` claims due OPEN
entries atomically (optimistic update with attempts/backoff) and dispatches them
through the core-owned `PhaseTwoRouter` — right after the commit and by a
fixed-delay poller (crash recovery and retries). Successful dispatches mark the
entry DONE (deleted asynchronously once `vanillabp.outbox.retention` passed —
keeping the deduplication window open); repeatedly failing entries are marked
BLOCKED. The generated process-service beans register themselves with the router
(produced by `PhaseTwoRouterProducer`) at bean creation, including a converter
turning the serialized aggregate ID back into the aggregate's ID type (determined
by reflection over the aggregate class, see `AggregateIdConversion`; if
undeterminable, the String is passed through). The poller uses a plain scheduled
executor started on `StartupEvent`, so the `quarkus-scheduler` extension is not
required. The outbox beans are only registered if the Agroal capability is present
(see `VanillaBpBuildStepProcessor#buildPhaseTwoOutbox`); applications may define
their own `PhaseTwoOutbox` beans in addition.

Since story 26i BOTH defaults (JDBC + MongoDB) may coexist and the outbox is
selected **per workflow aggregate** (`QuarkusPhaseTwoOutboxResolver`): the most
specific `PhaseTwoOutboxAware` bean wins; without one, the single active outbox is
used (deactivated - `vanillabp.outbox.jdbc.enabled`/`vanillabp.outbox.mongo.enabled`
- or unusable defaults are not considered), and with several active outboxes the
startup fails guiding towards `PhaseTwoOutboxAware` (Quarkus has no platform-side
knowledge of which persistence manages an aggregate). Resolution happens AT STARTUP
via an inherited `StartupEvent` observer on `ProcessServiceBaseCdiBean` whenever
the first-priority adapter needs a two-phase commit - a missing outbox fails the
boot naming the remedies. Store names are configurable
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
orphan which ends up BLOCKED with a monitorable ERROR). The idempotency key is
enforced by a partial unique index, created automatically unless
`vanillabp.outbox.create-schema` is disabled. If both Agroal and the MongoDB client
are present, the JDBC outbox wins deterministically (consistent with Spring Boot
where the JPA outbox is ordered first).

|                  |           JDBC outbox (Agroal)           |       MongoDB outbox (`quarkus-mongodb-client`)       |
|------------------|------------------------------------------|-------------------------------------------------------|
| Enlisting        | JTA transaction (entry = part of TX)     | best-effort (write before commit, delete on rollback) |
| Store            | table `VANILLABP_PHASE_TWO_OUTBOX`       | collection `vanillabp-phase-two-outbox`               |
| Dedup            | unique constraint `IDEMPOTENCY_KEY`      | partial unique index `idempotencyKey`                 |
| Claim            | optimistic `UPDATE ... WHERE ATTEMPTS=?` | `findOneAndUpdate`                                    |
| DONE + retention | yes                                      | yes                                                   |
| Selected when    | Agroal capability present                | no Agroal, MongoDB client present                     |

Configuration (`QuarkusMigrationAdapterProperties`): `vanillabp.outbox.poll-interval`,
`vanillabp.outbox.attempt-frequency`, `vanillabp.outbox.block-after-attempts`,
`vanillabp.outbox.retention` and
`vanillabp.outbox.create-schema` (disable the `CREATE TABLE IF NOT EXISTS` DDL /
index creation to manage the schema manually, e.g. by Flyway or Liquibase — then
also create the unique constraint on `IDEMPOTENCY_KEY` / the partial unique index
on `idempotencyKey` yourself).

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
