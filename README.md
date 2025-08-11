![Draft](./readme/vanillabp-headline.png)

# VanillaBP platform integration

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository is about platform integrations of the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java).
VanillaBP is about bringing [hexagonal architecture](https://en.wikipedia.org/wiki/Hexagonal_architecture_(software))
into JVM based business processing applications.
Typically, the main goal is to use a business processing management system (BPMS) in a decoupled way.
To learn more about this, visit [https://www.vanillabp.io](https://www.vanillabp.io).

The VanillaBP SPI is an aspect orientated API. Bringing it to life means to integrate
it into each [supported platform](#documentation-and-supported-platforms) in a way that ensures optimal developer experience
supporting platform specific conventions.

## Documentation and supported platforms

Currently, these platforms are supported:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/quarkus-report)

Developers who want to use VanillaBP should read the [Wiki](./wiki) documentation.
It contains conceptional documentation as well as details specific to each platform.

## Contribution

Developers who want to support VanillaBP should learn about implementation
details by reading the README.md files of each respective submodule. Please
create an [issue](./issues) first to let other contributors know what you are working on.

*Hint:* This repository is not about adapters specific to a BPMS supported.
It is about the implementation in which adapters plug into. To learn about which BPMS
are supported visit [https://www.vanillabp.io](https://www.vanillabp.io).
It will also point you to the adapters repositories.

### Modules

Each README.md file will list information about its submodules and link to their
README files.

Top-level modules (by directory name) are:

1. **migration-adapter:**<br>
   The implementation of VanillaBP common to all
   platforms. The idea is to implement as much as possible in pure Java
   NOT specific to any platform. This should help to bring new features
   to all platforms with a minimum afford. [Details...](./migration-adapter)
2. **spring-boot-integration:**<br>
   Support for using VanillaBP in Spring Boot applications. [Details...](./spring-boot-integration)
3. **quarkus-integration:**<br>
   Support for using VanillaBP in Quarkus applications. [Details...](./quarkus-integration)
4. **test-coverage-report:**<br>
   A module generating test coverage reports. Click the [platform's badge](#documentation-and-supported-platforms)  to open the respective report.
5. **test-utils:**<br>
   A tiny module providing utilities used by tests of all platforms.

### Implementation

In this repository this VanillaBP functionality can be found:

1. Platform integration:
   1. Loading VanillaBP configuration according to the platform's way of configuration.
   2. Detect [workflow modules](/wiki/Workflow-modules).
   3. Deploy BPMS resources (e.g. BPMN files, DMN files, etc.).
   4. Detect [@WorkflowService](https://github.com/vanillabp/spi-for-java#wire-up-a-process) annotated services.
   5. Build a [ProcessService](https://github.com/vanillabp/spi-for-java#start-a-workflow) for each process deployed to be used by workflow services.
   6. Build event listeners for tasks to [implemented by individual workflow services](https://github.com/vanillabp/spi-for-java#wire-up-a-task).
2. Support migration between multiple BPMS:
   1. Migrate from one system to another:
      1. Same BPMS, but on-premise to SaaS.
      2. Same BPMS, different versions.
   2. Migration from one BPMS to another.

## Building

This project uses Java 21. Additionally, `spotless` formatters are
defined for all kind of file types to force a common code style.

Use this command to build the project, run all tests and generate
this code coverage report:

```shell
mvn install verify
```

*Hint:* Note that `mvn install` is used instead of `mvn package`. This is
necessary because Quarkus integration tests builds load modules not from
`target` directories of submodules but instead from local Maven repository
filled by `mvn install`.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
