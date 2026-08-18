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
   a dependency instead of `io.vanillabp:spi-for-java`. It also carries the one
   check which has to work without any of the rest (see below).
3. **[integration-tests](./integration-tests):**<br>
   Modules which ensure the VanillaBP Spring Boot extension works as documented.

## An application without a BPMS adapter

On Spring Boot a BPMS adapter brings `vanillabp-spring-boot-integration` along, so an
application which forgot the adapter dependency has no VanillaBP runtime at all - the
autoconfiguration below `runtime` is not on the classpath and cannot report anything. What
such an application does have is `vanillabp-spring-boot-support`, because every workflow
module compiles against it. That is why the check reporting a missing adapter
(`NoBpmsAdapterCheck`, story 81) lives in the support module and not here: it is the only
VanillaBP code present in the case it was written for. Without it the bean container
answers instead ("No qualifying bean of type `ProcessService<...>`"), a message mentioning
neither an adapter nor VanillaBP.

It is a `BeanFactoryPostProcessor`, so it runs before the first bean of the application is
created. It only speaks up when a workflow module is on the classpath, because an
application without one has nothing to run and boots as it always did. The neighbouring
case, an integration which is loaded but got no adapter with it, stays here in
`SpringBootMigrationAdapterAutoConfiguration`, which knows the adapters actually loaded.
Both messages name the adapter artifacts to add (`BpmsAdapters` of the support module) and
say that the VanillaBP BOM does not manage their versions, since adapters are released on
their own schedule.

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

## The store of processed task deliveries

`io.vanillabp.integration.delivery` implements the core's `TaskDeliveryLog` (story 51,
the inbound counterpart of the outbox) twice, and `SpringTaskDeliveryLogResolver` picks
one per workflow aggregate - the same resolution the phase-two outbox uses, since a
record has to ride the aggregate's own transaction. The persistence technology behind an
aggregate is detected once in `SpringPersistenceTechnology`, shared by both resolvers.

- `JdbcTaskDeliveryLog` writes through `DataSourceUtils.getConnection(dataSource)`, so
  the connection belongs to the Spring-managed transaction. The SQL and the portable DDL
  of table `VANILLABP_TASK_DELIVERY` live in the core (`JdbcTaskDeliveryStore`), shared
  with Quarkus. Deliberately NOT gruelbox: gruelbox stores calls to be dispatched, a
  delivery record is a fact to be read back.
- `MongoTaskDeliveryLog` writes `TaskDeliveryDocument`s into `vanillabp-task-deliveries`
  through the `MongoTemplate`, keyed by the delivery key (the document ID gives
  uniqueness). A duplicate is detected by a pre-check read: inside a MongoDB transaction
  a duplicate-key error would abort the whole transaction, the aggregate changes
  included.
- Both come with an auto-configuration of their own
  (`vanillabp.outbox.jdbc.enabled` / `.mongo.enabled`), create their schema unless
  `vanillabp.outbox.create-schema` is disabled and delete expired records per
  `vanillabp.outbox.retention` (`cleanUpExpiredRecords`, scheduled by the core's
  `TaskDeliveryRetentionCleanup`). Table and index are created from a
  `SmartInitializingSingleton`, not while the bean is built - the DDL must not
  materialize the data source before the application's configuration is complete.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
