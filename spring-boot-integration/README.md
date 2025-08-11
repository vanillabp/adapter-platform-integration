![Draft](../readme/vanillabp-headline.png)

![Draft](../readme/vanillabp-headline.png)

# Spring Boot integration

In Spring Boot one has to use libraries as Maven/Gradle dependencies
to bring new functionality into a project. Also, Spring Boot platform integration
of VanillaBP is done by providing such Maven/Gradle module leveraging Spring Boots
autoconfiguration mechanism to provide the best developer experience.

## Modules

1. **[spring-boot-support](./spring-boot-support):**<br>
   This is the main module which is primarily responsible for two things:
   1. Bringing the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) in Spring Boot to life.
   2. Managing of VanillaBP adapters at runtime connecting to BPMSs.
2. **[dummy-adapter](./dummy-adapter):**<br>
   This adapter is a template for new adapters and is used as a adapter
   by the integration tests. For ready-to-use adapter modules checkout
   [https://www.vanillabp.io](https://www.vanillabp.io).
3. **[integration-tests](./integration-tests):**<br>
   Modules which ensure the VanillaBP Spring Boot extension works as documented.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
