![Header](../readme/vanillabp-headline.png)

# Spring Boot integration

In Spring Boot, you add new features to a project through libraries, as Maven or Gradle
dependencies. So the Spring Boot platform integration of VanillaBP is also such a Maven/Gradle
module. It uses the autoconfiguration of Spring Boot to make the developer's work as easy as
possible.

## Modules

1. **[runtime](./runtime):**<br>
   This is the main module. It has two main jobs:
   1. It brings the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) (service provider interface) to life in Spring Boot.
   2. It manages the VanillaBP adapters at runtime. They connect to the BPMSs (business process
      management systems).
2. **[spring-boot-support](./spring-boot-support):**<br>
   A small set of useful things for Spring Boot. Use it as a dependency instead of
   `io.vanillabp:spi-for-java`. It also has the one check which has to work without any of the
   rest (see below).
3. **[integration-tests](./integration-tests):**<br>
   Modules which make sure that the VanillaBP Spring Boot extension works as documented.

## An application without a BPMS adapter

On Spring Boot, a BPMS adapter brings `vanillabp-spring-boot-integration` with it. So if an
application forgot the adapter dependency, it has no VanillaBP runtime at all. The
autoconfiguration below `runtime` is not on the classpath and cannot report anything. But such an
application does have `vanillabp-spring-boot-support`, because every workflow module compiles
against it. That is why the check which reports a missing adapter (`NoBpmsAdapterCheck`) lives in
the support module and not here. In the case it was written for, it is the only VanillaBP code
there is. Without it, the bean container answers instead ("No qualifying bean of type
`ProcessService<...>`"). That message mentions neither an adapter nor VanillaBP.

It is a `BeanFactoryPostProcessor`, so it runs before the application creates its first bean. It
only reports something when a workflow module is on the classpath. An application without one has
nothing to run, and it starts as it always did. The case next to it is an integration which is
loaded but got no adapter with it. That case stays here, in
`SpringBootMigrationAdapterAutoConfiguration`, which knows the adapters which were really loaded.
Both messages say where to look up an adapter (`BpmsAdapters` of the support module points at the
wiki page which lists them). They also say that the VanillaBP BOM does not manage adapter versions,
because adapters are released on their own schedule. On purpose, compiled code does not list the
adapters. A list in a JAR is out of date on the day a new adapter appears.

`NoBpmsAdapterCheckTest` tests the check itself (`aWorkflowModuleWithoutAnAdapterEndsTheBoot`,
`theCheckRunsBeforeTheFirstBean`, `theNeighbouringCasesAreLeftAlone`).
`NoAdapterBootTest#bootWithoutAnyAdapterYieldsGuidingMessage` tests the case next to it, from the
auto-configuration.

## Which class is the workflow service

`WorkflowServiceDiscovery` goes through the bean definitions. It keeps every bean whose class has
`@WorkflowService`. It finds the annotation with `AnnotationUtils.findAnnotation`, which also looks
at the superclasses. The class it registers is the class of the BEAN. So a bean of a class which
extends an annotated class is a workflow service of its own:

- the attributes of the annotation reach it through `@Inherited`,
- its own simple name is the BPMN process id if the annotation names none,
- `getMethods()` returns the handler methods it declares and the ones it inherited.

Quarkus reads it the same way while it builds an application, from the Jandex index (see decision
32 in the repository's DECISIONS.md).

`findAnnotation` looks further than `@Inherited` does: into implemented interfaces, and into
annotations of the application's own. In those two cases, the class is refused and not registered.
The message uses the wording which the core keeps in `WorkflowServiceBelongsOnAClass`. A class which
is annotated but is not a bean is skipped without a message, for the reason which decision 21
gives.

## Configuration binding

The `vanillabp.*` configuration tree which users see is modeled ONCE, in the core, which does not
depend on a platform (`MigrationAdapterProperties` of the migration adapter). Spring Boot binds the
core POJOs directly. The thin subclass `VanillaBpConfigurationProperties` only has
`@ConfigurationProperties("vanillabp")`. So relaxed names, profiles and overrides through
environment variables work out of the box. And every property added to the core model is picked up
without platform code. Defaults (`normalize()`) and ALL validation live in the core. The
auto-configuration only passes in facts from the classpath (adapter types found, workflow-module
ids) and the raw property names. It uses the names to find `VANILLABP_*` environment variables which
the binding did not take over.

The runtime module runs the Spring Boot configuration processor. It ships
`META-INF/spring-configuration-metadata.json` for completion in the IDE. That file has the types,
and hand-written descriptions of the stable top-level keys from
`additional-spring-configuration-metadata.json`. Dynamic keys of map type
(`vanillabp.adapters.<id>.*`, `vanillabp.workflow-modules.<id>.*`) are declared once with their
value type. IDEs look into the value types on the classpath.

A key is described in one place. If a class of this module declares the field, the javadoc of the
field is the description, because the processor writes that into the metadata. If the type which
has the key comes as a dependency, the processor cannot reach it. Then
`additional-spring-configuration-metadata.json` describes it instead. Do not write both. Spring
merges the two files, and the hand-written text wins. So nobody would see the javadoc, and it could
say something else without anybody noticing. `AKeyIsDescribedInOnePlaceTest` fails a key which is
described twice. `EveryKeyOfASectionIsDescribedTest` fails a key of a section of the core model which
nobody described at all.

BPMS adapters add their own keys to the same tree (for example
`vanillabp.adapters.<id>.rest-address`). To do so, they bind a second class of their own with
`@ConfigurationProperties("vanillabp")`, an overlay. Classes with the same prefix can exist side by
side, and the JavaBean binding ignores keys which the core view does not know. The set of adapter
ids always comes from the core properties (`adapterTypes()`), never from an overlay map.

`VanillaBpConfigurationBindingTest` tests the binding onto the core model, the overlay classes side
by side and the rules for environment variables. The validation which guides the user lives in the
core, and `MigrationAdapterPropertiesTest` tests it.

`WorkflowModulePropertiesEnvironmentPostProcessor` reads the files which a workflow module ships.
It adds them below everything the application brings. It reads `<module-id>-<profile>.yaml`
wherever it finds it, also where `<module-id>.yaml` is missing. Quarkus does not (decision 61). A
workflow module is a library and runs on both platforms. So at startup the post processor names such
a file, and it says which file would make Quarkus read it. It writes the line through a
`DeferredLogFactory`, because an environment post processor runs before the logging system is
ready. `ProfileFileWithoutItsPlainFileTest` of `globalclasspath-integration-test` tests both halves:
the values which are there, and the line which names the file.

It searches the four places which the `WorkflowModuleConfigFiles` of the core names, and Quarkus
reads the same four (decision 65). They are different styles, not a ranking. So a file may lie in
exactly one of them. If a module ships the same file twice, the start ends with a message which
names both places. `ModuleFileInEachOfTheFourPlacesTest` and `TheSameFileInTwoPlacesTest` of
`globalclasspath-integration-test` test both halves.

## The store of processed task deliveries

`io.vanillabp.integration.delivery` implements the core's `TaskDeliveryLog` twice. The log is the
inbound counterpart of the outbox: it records which task deliveries were already processed.
`SpringTaskDeliveryLogResolver` picks one of the two for each workflow aggregate. The phase-two
outbox resolves in the same way, because a record has to be written in the aggregate's own
transaction. `SpringPersistenceTechnology` finds out once which persistence technology is behind an
aggregate, and both resolvers share it. The resolver is the bean `vanillaBpTaskDeliveryLogResolver`.
So an extension which reads what a workflow is waiting for asks the same object which the process
services write through (`ExtensionElectionAndConfigurationTest` injects it through
`DeliveryLogUsingExtension`).

- `JdbcTaskDeliveryLog` writes through `DataSourceUtils.getConnection(dataSource)`. So the
  connection belongs to the transaction which Spring manages. The SQL and the portable DDL of the
  table live in the core (`JdbcTaskDeliveryStore`), and Quarkus uses them too. The table name is
  `vanillabp.outbox.jdbc.delivery-table`, by default `VANILLABP_TASK_DELIVERY`
  (`JdbcDeliveryTableNameTest`). On purpose, it is NOT a table of the outbox. An outbox stores
  calls which still have to be sent. A delivery record is a fact which is read back later.
  `recordOfTask` and `markTaskClosed` use the same store. With them, a task operation chooses its
  BPMS from the record instead of asking a BPMS (decision 30). They use the caller's connection, so
  the read sees what the caller's own transaction wrote.
- `MongoTaskDeliveryLog` writes `TaskDeliveryDocument`s into the collection which
  `vanillabp.outbox.mongo.delivery-collection` names, through the `MongoTemplate`. The key is the
  delivery key (the document ID makes it unique). A read before the write finds a duplicate. Inside a
  MongoDB transaction, a duplicate-key error would abort the whole transaction, together with the
  changes to the aggregate. Its auto-configuration creates the indexes of
  `MongoSchema.DELIVERY_INDEXES` on the same collection which the log writes to. One of them is the
  index over `taskId`, which `recordOfTask` reads by. `MongoDeliveryCollectionNameTest` renames the
  collection and asks for them.
- Both have an auto-configuration of their own (`vanillabp.outbox.jdbc.enabled` /
  `.mongo.enabled`). Both create their schema unless `vanillabp.outbox.create-schema` is disabled.
  Both delete expired records as `vanillabp.outbox.retention` says (`cleanUpExpiredRecords`,
  scheduled by the core's `TaskDeliveryRetentionCleanup`). Table and indexes are created from a
  `SmartInitializingSingleton`, not while the bean is built. The DDL must not create the data source
  before the application's configuration is complete. If the application looks after its own
  schema, the MongoDB half names the indexes which are missing and does not create them.
- The same run refreshes the records of the tasks which are still open. The core collects the keys
  which the BPMS delivered again. `cleanUpExpiredRecords` writes them before it deletes anything.
  The JDBC store writes them in batches, and the MongoDB store with a bulk write.

## What is published where

Both contributions below, metrics and health, are optional dependencies of this module. So an
application which brings neither starts as before.

Metrics use `micrometer-core`. `MicrometerVanillaBpMetrics` is a bean of the nested
`WorkflowAdapterCacheMetricsConfiguration`. Its condition is the `MeterRegistry` class, given BY
NAME. The annotation of a nested configuration class is read by reflection. So a class literal of
an optional dependency which is missing would fail before the condition is evaluated. The metrics
auto-configuration of the Actuator binds it like any other `MeterBinder`. The gauges for pending
entries of the outbox stores are registered by a `SmartInitializingSingleton`, not while the beans
are built. If the stores were resolved earlier, the persistence infrastructure would be created
before the application asked for it.

Health uses `spring-boot-health` (Spring Boot 4 moved `HealthIndicator` out of the Actuator
artifact into that one). The bean is named `vanillabpHealthIndicator`. The bean name without the
suffix names the health component, and that name has to stay `vanillabp`.

The process services get the metrics from `ProcessServiceBeanRegistrar` right after they are built,
primary and secondary ones alike. `PhaseTwoRouter` gets them from the auto-configuration. They come
through a setter, not through another constructor parameter. The metrics exist once for each
application, but process services exist for each BPMN process. And that constructor is long enough.

`WorkflowServicesWhichAreNoBeans` is the Spring half of a refusal: a BPMN process which nothing
claims is refused (`UnclaimedBpmnProcessHints` in the core). It reads the class resources of the
reported workflow module through a `PathMatchingResourcePatternResolver` and a
`MetadataReaderFactory`. So it loads no candidate class to answer a question about it. It names
every class which has `@WorkflowService` for one of the reported processes but is not a bean. Two
conditions narrow the search, and both are needed. The process has to be one of the reported ones.
And the classpath root of the class has to belong to the reported module. Otherwise a class which
another profile brings for a different module would show up on every start. It reads the whole
classpath only if the module's own root found nothing. The reason is that the workflow services of
the global module live in a root with no marker file at all. A class found that way is kept only if
its root has no descriptor either. The cost is the measurement of decision 21: 42 816 class
resources, 15.9 of 24.4 seconds under `spring-boot:run`. It is paid once, on a start which already
reports a problem.
`WorkflowServiceDiscoveryTest#theBootReadsNoClassResourcesToFindTheWorkflowServices`
tests that a healthy start reads nothing at all.

The same registrar gives the bean the process services of every id which its workflow service
classes declare (`ProcessServiceSpringBean#getProcessServicesOfDeclaredIds`). The startup
validations go through that list: `vanillaBpProcessServiceStartupValidation` in the
auto-configuration, and the election check of `SpringBootDeploymentService`. The SPI's injection
contract is one `ProcessService` bean for each aggregate. But everything which you can configure for
a workflow, you can configure for each declared id. So when only the primary id was validated, a
secondary id or an id which is only declared stayed unchecked. And after a rename, the running
workflows keep exactly such an id. The Quarkus integration does the same from
`ProcessServiceBaseCdiBean`.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
