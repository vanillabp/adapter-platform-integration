![Draft](./readme/vanillabp-headline.png)

# VanillaBP platform integration

[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fadapter-platform-integration%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/adapter-platform-integration/)
[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository is about functionality common to all BPM system adapters which ensures the same behavior when migrating
from one to another. In addition, it provides specific integration into
[supported platforms and documentation](#documentation-and-supported-platforms) to ensure optimal developer experience.

## Documentation and supported platforms

To learn about how to bring VanillaBP into your projects checkout the [Wiki](./wiki) for documentation.

Documentation of VanillaBP integration specific each platform can be found here, but is also deep-linked on the respective
Wiki page:
1. [Spring Boot](./spring-boot-integration)
2. [Quarkus](./quarkus-integration)

## Implementation

To find out what functions the code of this repository offers, please read on:

### Components

1. *Application framework integrations:*<br>
   Responsible for loading configuration properties, workflow modules
   detection and integration of BPMS adapters into each [platform supported](#documentation-and-supported-platforms).
2. *Migration adapter:*<br>
   Implements VanillaBPs SPI for Java using BPMS adapters available.
   Additionally, it is able to migrate workflow modules from one
   adapter to another.

### Tasks

These tasks are fulfilled:

1. Detect workflow modules.
2. Deploy BPMS resources.<br>
   e.g. BPMN files, DMN files, etc.
3. Detect @WorkflowService annotated services.
4. Build services for each workflow deployed to be used by workflow services.
   e.g. ProcessService
5. Build event listeners for tasks to hook into workflow services.
   e.g. @WorkflowTask annotated methods

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2022 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
