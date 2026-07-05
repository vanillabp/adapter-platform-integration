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

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
