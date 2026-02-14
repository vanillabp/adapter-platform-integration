![Header](../readme/vanillabp-headline.png)

# Spring Boot integration

In Spring Boot one has to use libraries as Maven/Gradle dependencies
to bring new functionality into a project. Also, Spring Boot platform integration
of VanillaBP is done by providing such Maven/Gradle module leveraging Spring Boots
autoconfiguration mechanism to provide the best developer experience.

## Modules

1. **[runtime](./runtime):**<br>
   This is the main module which is primarily responsible for two things:
   1. Bringing the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) in Spring Boot to life.
   2. Managing of VanillaBP adapters at runtime connecting to BPMSs.
2. **[spring-boot-support](./spring-boot-support):**<br>
   A tiny collection of useful things in the context of Spring Boot to be used as
   a dependency instead of `io.vanillabp:spi-for-java`.
3. **[integration-tests](./integration-tests):**<br>
   Modules which ensure the VanillaBP Spring Boot extension works as documented.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
