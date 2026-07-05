![Header](../readme/vanillabp-headline.png)

# Migration adapter

The migration adapter is the implementation of VanillaBP's adapter mechanism
used under the hood. Platform integrations are sole responsible for loading configuration
and analyzing business code in a way specific to the respective platform. Connecting
to adapters of supported BPMSs is done by this module which is used by platform
integrations  as a dependency. This ensures the same behavior of VanillaBP on
different platforms.

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

*Note:* Workflow-level configuration (`...workflows.*`) is modeled by the core but not
yet filled by the platform integrations. Until implemented, configuring it fails hard
at startup ("not yet supported") instead of silently electing the wrong BPMS.

- **New workflows** are always started using the first (highest-priority) adapter.
- **Existing workflows** may still live in a previously used BPMS. For operations on
  existing workflows (message correlation, completing tasks) the adapters are asked in
  priority order whether they own the instance (`MigratableProcessService.isTaskActive`,
  returning `true`/`false`/`null` for "unknown"). This is why eventual consistency has
  to be handled *here* and not in individual adapters: a remote BPMS (like Camunda 8)
  may not know an instance *yet*, and only the migration adapter can decide to fall
  back to the next adapter in the list.

To migrate, one puts the new BPMS first in the priority list and keeps the old one in
the list: new instances start in the new BPMS while existing instances complete in the
old one.

### Deployment pipeline

`DeploymentService` orchestrates deployment per workflow module:

1. Resolve the prioritized adapters for the module and find the matching
   `AdapterDeploymentService` by adapter ID.
2. Load the BPMN resources (either from an adapter-specific `resources-location` or
   the generic VanillaBP BPMN files).
3. For each file run the pipeline `readBpmn` → `prepareBpmn` (per process) →
   `wireBpmn` (adapter) → `wireBpmn` of all matching `ExtensionWiringService`s.
   The adapter-specific *processing context* (generic parameter `PC`) is accumulated
   across all executable processes of a file and across all files of a module.
   Extensions receive the same processing context as the adapter's `wireBpmn`;
   extensions are matched by assignability, so extensions may declare interfaces
   as model or processing-context type.
4. `deployResources` pushes the result to the BPMS.
5. Once the application is ready, `startWorkflowProcessing` is called for adapters and
   extensions — only then workflows are actually processed. It is called for *every*
   adapter resources were deployed to (not only the highest-priority one), since
   during a BPMS migration all configured BPMSs have to keep processing workflows.

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
  `needsTwoPhaseCommitForStartingWorkflows()`, a call to
  `MigratableProcessServicePhaseTwo` (implemented by the platform integration) is
  scheduled via the *transaction outbox* SPI `PhaseTwoOutbox`. If such an adapter is
  used but no `PhaseTwoOutbox` is available, starting a workflow fails hard.

The core does not implement (or depend on) any outbox itself — it only defines the
`PhaseTwoOutbox` contract:

- **Scheduling:** `schedule(workflowModuleId, bpmnProcessId, adapterId,
  workflowAggregateId)` MUST be invoked within the still-running local transaction
  that persists the workflow aggregate and MUST enlist the outbox entry in exactly
  that transaction: the entry becomes visible if and only if the transaction commits.
- **Recovery:** every committed-but-unprocessed entry has to be dispatched to
  `MigratableProcessServicePhaseTwo` right after the commit *and* after an
  application restart (crash recovery), retrying failed dispatches with a backoff.
  Entries are removed (or marked processed) only after a successful dispatch.
- **Idempotency:** as a consequence of the at-least-once semantics, adapters MUST
  tolerate repeated `startWorkflowPhaseTwo` calls: the triple
  `workflowModuleId + bpmnProcessId + workflowAggregateId` is the idempotency key —
  a second call for an already-started workflow has to return without starting
  another workflow instance.

Default implementations are provided by the platform integrations (configured via
`vanillabp.outbox.*`; applications may define their own `PhaseTwoOutbox` bean
instead):

|  Platform   |       Persistence        |                                      Implementation                                      |
|-------------|--------------------------|------------------------------------------------------------------------------------------|
| Spring Boot | JPA                      | based on `com.gruelbox:transactionoutbox` (`spring-boot-integration`)                    |
| Spring Boot | MongoDB                  | own implementation using `MongoTemplate` (`spring-boot-integration`)                     |
| Quarkus     | JDBC datasource (Agroal) | own JDBC/JTA-based implementation (`quarkus-integration`; gruelbox does not support JTA) |

### Aggregate persistence

The core does not know any persistence technology. `AggregatePersistenceAware`
abstracts saving an aggregate and determining its ID. Implementations are provided by
the platform integration (e.g. based on Spring Data) or by the business application
itself; the implementation with the most specific generic type for the aggregate wins.

### Extensions

Extensions (e.g. the VanillaBP Business Cockpit) integrate into the deployment
pipeline via `ExtensionWiringService`: they get their own `wireBpmn` and
`startWorkflowProcessing` callbacks, ordered by `getOrder()` and filtered by matching
BPMN-model- and processing-context-types of the current adapter. An extension may
define its own SPI (e.g. annotations to be picked up from business code) — such an SPI
lives in the extension's `wireBpmn` implementation and is not part of the VanillaBP
core.

## Modules

1. **spi:** (Service Provider Interface)<br>
   This module provides the interfaces to be implemented by platform integration
   implementations as well as interfaces to be implemented by adapter
   implementations: `AdapterDeploymentService`, `MigratableProcessService`,
   `MigratableProcessServicePhaseTwo`, `PhaseTwoOutbox`, `AggregatePersistenceAware`
   and `ExtensionWiringService`. Adapters report BPMN parsing errors using
   `BpmnParseException`.
2. **runtime:**<br>
   This module implements the runtime behavior according to the
   features [listed above](#features), mainly `DeploymentService`
   (deployment pipeline), `MigrationProcessService` (per-process runtime used by the
   platform integrations' `ProcessService` beans) and `MigrationAdapterProperties`
   (configuration model incl. validation).

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
