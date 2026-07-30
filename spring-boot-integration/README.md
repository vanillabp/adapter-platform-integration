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

## Configuration binding

The user-facing `vanillabp.*` configuration tree is modeled ONCE, in the
platform-neutral core (`MigrationAdapterProperties` of the migration adapter).
Spring Boot binds the core POJOs directly: the thin subclass
`VanillaBpConfigurationProperties` only carries
`@ConfigurationProperties("vanillabp")`, so relaxed names, profiles and
environment-variable overrides work out of the box and every property added to
the core model is picked up without platform code. Defaulting (`normalize()`)
and ALL validation live in the core - the auto-configuration only feeds in the
classpath facts (adapter types found, workflow-module ids) and the raw property
names (used to detect `VANILLABP_*` environment variables not taken over by the
binding).

The runtime module runs the Spring Boot configuration processor and ships
`META-INF/spring-configuration-metadata.json` (types plus hand-written
descriptions of the stable top-level keys in
`additional-spring-configuration-metadata.json`) for IDE completion. Map-typed
dynamic keys (`vanillabp.adapters.<id>.*`, `vanillabp.workflow-modules.<id>.*`)
are declared once with their value type - IDEs drill into the value types on
the classpath.

BPMS adapters contribute their own keys to the same tree (e.g.
`vanillabp.adapters.<id>.rest-address`) by binding an adapter-owned second
`@ConfigurationProperties("vanillabp")` overlay class: same-prefix classes
coexist, and keys unknown to the core view are ignored by the JavaBean binding.
The adapter-id set is always derived from the core properties
(`adapterTypes()`), never from an overlay map.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
