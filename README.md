![Header](./readme/vanillabp-headline.png)

# VanillaBP platform integration

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository contains platform integrations for the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java).

VanillaBP aims to bring [hexagonal architecture](https://en.wikipedia.org/wiki/Hexagonal_architecture_(software))
to JVM-based business processing applications. Its primary goal is to enable the use of a business process management
system (BPMS) in a decoupled and maintainable way.  
To learn more, visit [https://www.vanillabp.io](https://www.vanillabp.io).

The VanillaBP SPI is an aspect-oriented API. Bringing it to life means integrating it into each
[supported platform](#documentation-and-supported-platforms) in a way that ensures an optimal developer experience
while respecting platform-specific conventions.

## Documentation and supported platforms

Currently, these platforms are supported:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/quarkus-report)

Developers who want to use VanillaBP should refer to the [Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki).  
It contains conceptual documentation as well as platform-specific details.

## Contribution

Developers who want to contribute to VanillaBP should familiarize themselves with the implementation details by
reading the `README.md` files of the respective submodules. Please create an [issue](./issues) first to let other
contributors know what you are working on.

*Hint:* This repository is not about BPMS-specific adapters. It provides the infrastructure into which such adapters
plug in. To learn which BPMS are supported, visit [https://www.vanillabp.io](https://www.vanillabp.io), which also
links to the corresponding adapter repositories.

### Modules

Each `README.md` file lists information about its submodules and links to their respective README files.

Top-level modules (by directory name) are:

1. **migration-adapter:**<br>
   Core VanillaBP functionality shared across all platforms. The goal is to implement as much logic as possible in
   plain Java, without platform-specific dependencies. This helps deliver new features to all platforms with minimal
   effort. [Details...](./migration-adapter)
2. **spring-boot-integration:**<br>
   Support for using VanillaBP in Spring Boot applications. [Details...](./spring-boot-integration)
3. **quarkus-integration:**<br>
   Support for using VanillaBP in Quarkus applications. [Details...](./quarkus-integration)
4. **test-coverage-report:**<br>
   A module that generates test coverage reports. Click the [platform's badge](#documentation-and-supported-platforms)  to open the respective report.
5. **test-utils:**<br>
   A small module providing utilities used by tests across all platforms.

### Implementation

This repository contains the following VanillaBP functionality:

1. Platform integration:
   1. Loading VanillaBP configuration using the platform’s native configuration mechanisms.
   2. Detecting [workflow modules](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules).
   3. Deploying BPMS resources (e.g. BPMN files, DMN files, etc.).
   4. Detecting services annotated with [@WorkflowService](https://github.com/vanillabp/spi-for-java#wire-up-a-process).
   5. Building a [ProcessService](https://github.com/vanillabp/spi-for-java#start-a-workflow) for each deployed process, to be used by workflow services.
   6. Creating event listeners for tasks to be [implemented by individual workflow services](https://github.com/vanillabp/spi-for-java#wire-up-a-task).
2. Support for migration across BPMS:
   1. Migrating within the same BPMS:
      1. From on-premise to SaaS.
      2. Between different versions.
   2. Migrating from one BPMS to another.

### Building

This project uses Java 21. In addition, `spotless` formatters are configured for all supported file
types to enforce a consistent code style.

Use the following command to build the project, run all tests, and generate the code coverage report:

```shell
mvn install verify
```

*Hint:* Note that `mvn install` is used instead of `mvn package`. This is
necessary because the Quarkus integration tests load modules not from the
`target` directories of submodules, but from the local Maven repository
populated by `mvn install`.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
