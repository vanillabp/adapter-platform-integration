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
   across all files of a module.
4. `deployResources` pushes the result to the BPMS.
5. Once the application is ready, `startWorkflowProcessing` is called for adapters and
   extensions — only then workflows are actually processed.

### Two-phase workflow start (transaction outbox)

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
  scheduled via a *transaction outbox* (`com.gruelbox:transactionoutbox`) **within the
  same local transaction**. The outbox guarantees phase two is executed after commit —
  even after a crash and restart.

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
   `MigratableProcessServicePhaseTwo`, `AggregatePersistenceAware` and
   `ExtensionWiringService`.
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
