![Header](./readme/vanillabp-headline.png)

# VanillaBP platform integration

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository connects the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) to the
platforms an application runs on.

VanillaBP brings [hexagonal architecture](https://en.wikipedia.org/wiki/Hexagonal_architecture_(software))
to business process applications on the JVM. Its main goal: an application uses a business process
management system (BPMS), but stays decoupled from it and easy to maintain.
To learn more, visit [https://www.vanillabp.io](https://www.vanillabp.io).

The VanillaBP SPI (service provider interface) is an aspect-oriented API: the application marks its
code with annotations, and VanillaBP does the rest. To bring it to life, we integrate it into each
[supported platform](#documentation-and-supported-platforms). The aim is a good experience for the
developer, in the way each platform usually works.

## Documentation and supported platforms

These platforms are supported today:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/quarkus-report)

If you want to use VanillaBP, read the [wiki](https://github.com/vanillabp/adapter-platform-integration/wiki).
It explains the concepts, and it has the details for each platform.

This release is built and tested against Spring Boot **4.1.0**, Quarkus **3.37.1** and Java **21**.
A newer patch or minor version of either platform should work, but we do not test it here. A new
major version needs an upgrade of VanillaBP itself, and `UPGRADE.md` names the minimum an
application needs. There are no release lines per platform version. The
[Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter#release-lines)
is different: its artifacts carry the minor version of the cluster. Camunda 8 forces that, because
the client a build was compiled against is the lowest cluster version it accepts. Spring Boot and
Quarkus are dependencies of the application, and the application picks their versions itself.

## Contribution

[`CONTRIBUTING.md`](./CONTRIBUTING.md) says how to build this repository, how we write, and what a
pull request should contain.

If you want to contribute to VanillaBP, first read the `README.md` files of the modules. They
describe how things are built. Please create an [issue](./issues) first, so other contributors know
what you are working on.

[`diagrams/README.md`](./diagrams) lists the pictures which belong to those READMEs. It has the
architecture overview as a drawing, and a table which says which section of
`migration-adapter/README.md` holds each Mermaid diagram.

*Hint:* This repository does not hold adapters for a specific BPMS. It holds the infrastructure which
such adapters plug into. [https://www.vanillabp.io](https://www.vanillabp.io) lists the supported
BPMS and links to their adapter repositories.

Do you want to write an adapter for another BPMS, in a repository of your own? Read
[`migration-adapter/ADAPTER-AUTHORS.md`](./migration-adapter/ADAPTER-AUTHORS.md). It is written for
a team without access to this workspace. It describes the two interfaces to implement, the calls the
core expects back, what your answers promise and what a wrong answer costs.

Do you want to build something which plugs into the deployment but is not a BPMS adapter, the way
the Business Cockpit does? Read
[`migration-adapter/EXTENSION-AUTHORS.md`](./migration-adapter/EXTENSION-AUTHORS.md). It is written
for the same kind of team. It describes the six calls of the wiring service and the order they run
in, where the settings of such an extension live, and what it must not do.

### Where a snapshot comes from

Snapshots go to the snapshot repository of Maven Central. Only `main` publishes them, no other
branch. There is one snapshot version per module. So a branch which published would overwrite what
`main` published, and a consumer could get a set of modules which never existed together in one
tree. This really happened. On 2026-08-20, eighteen blueprint jobs failed with a `NoSuchMethodError`.
It named a method which existed only on one story branch: one module came from there, and another
came from `main`.

So a pull request builds and tests, but does not deploy. The publish job of `main` runs in a
concurrency group, so two pushes cannot mix. Please do not add a branch trigger to try something out
quickly. A snapshot which you install locally does the same job and reaches nobody else.

### Modules

Each `README.md` file describes its submodules and links to their README files.

These are the top-level modules, by directory name:

1. **bom:**<br>
   The BOM (bill of materials, artifact `io.vanillabp:vanillabp-bom`). An application imports it to
   get matching versions of all VanillaBP artifacts. The BPMS adapters are not part of it on purpose,
   because they are released on their own. Its parent is the release parent, *not* this aggregator.
   Importing a BOM imports its *effective* dependency management. So if the BOM inherited from here,
   it would pin Lombok, SLF4J, Testcontainers and more in every application.
   The BOM gives each tool of the build a scope, and that scope applies to the application which uses
   the tool. A module which is itself a library of test tools writes its own scope where it declares
   the tool. It uses the tool in `src/main/java`, and the managed scope `test` would stop it from
   compiling.
2. **migration-adapter:**<br>
   The core of VanillaBP, shared by all platforms. The goal is to build as much logic as possible in
   plain Java, without anything specific to a platform. Then a new feature reaches all platforms with
   little effort. [Details...](./migration-adapter)
3. **spring-boot-integration:**<br>
   Support for VanillaBP in Spring Boot applications. [Details...](./spring-boot-integration)
4. **quarkus-integration:**<br>
   Support for VanillaBP in Quarkus applications. [Details...](./quarkus-integration)
5. **test-coverage-report:**<br>
   This module generates the test coverage reports. Click the
   [badge of a platform](#documentation-and-supported-platforms) to open its report.
   Its module `coverage-gate` is built last. It breaks the build in two cases. The first is coverage
   below the line, which `coverage.threshold.spring-boot` and `coverage.threshold.quarkus` in the root
   POM set, in percent of covered instructions. That is the same number the badge shows. The second
   is a module which produces coverage data that no aggregated report reads.
   Both properties are 85, the number every VanillaBP repository uses as its gate. The rule is 90 per
   platform. So a report between the two passes the build, but still shows a gap which somebody owes
   a test for.
   The gate reports what it measured on every run, green ones included. It is the one test class in
   VanillaBP which prints while it passes (`@PrintsWhenPassing`).
   The Quarkus number includes what runs in a forked application. A prod-mode test hands the JaCoCo
   agent to the JVM it forks. The class ids of that run match the class files the report analyses,
   so nothing from that run is dropped. We measured this in `camunda7-adapter` on 2026-09-18. The
   javadoc of `TestCoverageUtils` has the numbers. It also names the one case where Quarkus does
   change a class id.
6. **test-utils:**<br>
   A small module with utilities which tests on all platforms use.
   It also holds `PublishedPoms`. Every VanillaBP repository calls this assertion to check what its
   published POMs hand an application. The repository names the file its modules publish and the
   tools of its own build. The javadoc of that class says why.<br>
   `PhaseTwoOutboxReader` is where a test asks what the phase-two outbox holds. It reads the
   entries, their state, their attempts and what each call belongs to. It reads the payloads next to
   them. It also writes the few things a test has to write: an entry which is due now, an entry which
   waits, and every entry opened again the way an operator repairs one. Every table name and every
   state it uses comes from the platform class which declares it. So a rename is followed in that one
   module, and not in every test which reads the outbox. This class exists so that no test spells a
   table or a column itself.<br>
   `MongoPhaseTwoOutboxReader` does the same for an application on MongoDB. It works on the database
   which a Spring Boot template or a Quarkus client hands it. It answers the same questions about
   entries and payloads, and it writes the entries a test has to plant. It takes the collection names
   and the three status values from the platform classes which declare them.
7. **bpms-double:**<br>
   The BPMS double, a fake BPMS for tests. It is published, so a repository outside this one can
   start a VanillaBP application in a test without a real BPMS. The platform's own tests run against
   this adapter, and that is what proves it for others. [Details...](./bpms-double)

### Implementation

This repository holds the following VanillaBP functions:

1. Platform integration:
   1. Loading the VanillaBP configuration in the way each platform usually loads configuration. This
      includes the configuration files per workflow module, and the conventions which make an
      explicit configuration optional.
   2. Finding [workflow modules](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules).
   3. Running the deployment pipeline of each workflow module (`readBpmn` → `prepareBpmn` →
      `wireBpmn` → `readDmn` → `deployResources` → `startWorkflowProcessing`) for every configured
      adapter and [extension](https://github.com/vanillabp/adapter-platform-integration/wiki/Extensions).
      The `.dmn` files of the module are read after its BPMN files and deployed together with them,
      in the same deployment
      ([decision tables](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules#decision-tables-travel-with-the-processes-calling-them)).
   4. Ending the start with `DeploymentService#endOfStartup()`. A platform integration owes the core
      this order: deploy, check, close. First `deployResources`. Then the checks which only work once
      an adapter has deployed (today the election capability of the prioritized adapters). Then
      `endOfStartup()`. If the last call is missing, the application starts as if the start had found
      nothing: no block in the log and no collected refusal. Each platform decides when that moment
      comes, and the two platforms say it in different ways (see the javadoc of `endOfStartup()`).
      `TheOrderOfAStartTest` of each platform pins the order.
   5. Finding services annotated with
      [@WorkflowService](https://github.com/vanillabp/spi-for-java#wire-up-a-process).
   6. Building a [ProcessService](https://github.com/vanillabp/spi-for-java#start-a-workflow) for each
      deployed process. Workflow services use it.
   7. Running [@WorkflowTask](https://github.com/vanillabp/spi-for-java#wire-up-a-task) methods for the
      adapters. This means: find the handler, load the workflow aggregate, bind the parameters, run
      the method in a transaction, save the aggregate, and map the outcome (completed / BPMN error /
      left open).
   8. Turning a workflow aggregate into the values a BPMS gets to see
      (`@SyncWithBPMS`/`@NoSyncWithBPMS`). There is one model, and every adapter uses it.
   9. A reliable second phase for a remote BPMS: the transaction outbox, its idempotency contract and
      the dispatch router.
   10. The read-only API for viewing workflows and their history across all BPMS. This includes a
       namespace per adapter for the ids of process definitions.
2. Support for migration between BPMS:
   1. Migrating within the same BPMS:
      1. From on-premise to SaaS.
      2. Between different versions.
   2. Migrating from one BPMS to another.
   3. Electing the BPMS which holds a particular workflow. VanillaBP asks the configured adapters in
      order of priority, and it remembers the answer.

### Rules and decisions worth knowing before contributing

The current implementation follows these conventions. If you keep to them, a new feature reaches
all platforms at once. The details and the reasons are in the `README.md` of each module.
[`UPGRADE.md`](./UPGRADE.md) is something else: it says what an application has to do when it moves
from VanillaBP 1.
These conventions are not what the code cites. A comment points at an entry of the
[decision log](./DECISIONS.md).

1. **Features are built in `migration-adapter`** (plain Java). A platform integration only does what
   only it can do: read configuration, scan and analyze business code, create beans, run
   transactions. If a feature needs behavior specific to a platform, express it as an interface which
   the core owns (for example `TransactionRunner`).
2. **Three SPIs, kept apart on purpose.** Applications implement the *integration SPI*
   (`vanillabp-integration-spi`): aggregate persistence, a custom outbox, the election cache. BPMS
   adapters implement the *adapter SPI* (`vanillabp-adapter-spi`). The *extension SPI*
   (`vanillabp-extension-spi`) has the one interface an extension needs to take part in deploying a
   workflow module. A type never lives in more than one of them.
3. **One adapter instance per configured adapter id**, not per adapter type. Migration is built on
   this. It is also why there are process and deployment services per id.
4. **Validate at startup, guide in the message.** A configuration error shows up when the
   application starts, and the message names the property keys to add. An application without
   configuration should still start, and its own log should lead the developer to a working setup.
5. **At least once, and never pretend exactly once.** Anything the outbox sends may be sent again.
   Operations carry a key for idempotency (a repeated call has no further effect). Remaining gaps are
   documented, not hidden.
6. **Never read state back from the BPMS into the aggregate.** The aggregate is the single source of
   truth. The only values read from a BPMS are those which a `@TaskParam` asks for explicitly.
7. **Tests: acceptance tests first**, per platform, against the published
   [BPMS double](./bpms-double). Coverage is measured separately per platform (>90% of instructions,
   enforced by `test-coverage-report/coverage-gate`). The acceptance test proves a story, not unit
   coverage.
8. **A sentence which promises behavior is part of the behavior.** A javadoc, `README.md` or wiki
   sentence which promises something either has a test which fails when it stops being true, or it
   says that it is an assumption and what would prove it wrong. A story which changes behavior reads
   the claims about that behavior again before it is done. (We went through the SPIs and the wikis
   once, to start from a clean state.)

### Building

This project publishes Java 21 class files, so an application on Java 21 can use it. The build
itself runs on a JDK 21 or newer, and the workflows use the one named in `.github/workflows`. There
are also `spotless` formatters for all supported file types, so the code style stays the same
everywhere.

This command builds the project, runs all tests and generates the code coverage report:

```shell
mvn install
```

*Hint:* Use `mvn install`, not `mvn package`. The Quarkus integration tests do not load modules from
the `target` directories of the submodules. They load them from the local Maven repository, which
`mvn install` fills. Do not write `mvn install verify` either. `install` already runs every phase of
`verify`. With both, Maven walks two lifecycles per module and reports every compiler warning twice.

## Decision log

[`DECISIONS.md`](./DECISIONS.md) holds the decisions which several places in this repository rely
on. It is the one thing the code may cite. A citation reads `see decision 7 in the repository's
DECISIONS.md`. Numbers are never reused. An entry which was overturned stays and names the entry
which replaced it. So a citation written today still leads to the right place in a year.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
