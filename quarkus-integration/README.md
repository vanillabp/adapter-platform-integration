![Header](../readme/vanillabp-headline.png)

# Quarkus integration

In Quarkus, you add new features to a project through Quarkus extensions (see
https://quarkus.io/extensions/). This is the easiest way for developers. So the Quarkus platform
integration of VanillaBP also uses the extension mechanism of Quarkus.

There is one main extension, `vanillabp`. This module implements it. It has two main jobs:

1. It brings the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) (service provider interface) to life in Quarkus.
2. It manages the VanillaBP adapters at build time and at runtime. They connect to the BPMSs
   (business process management systems).

There are also VanillaBP adapter extensions. You add them next to the `vanillabp` extension, and
they connect to the supported BPMSs at runtime. The [BPMS double](../bpms-double) is an adapter
which pretends to be a BPMS, and the tests of this module run against it. If you write a new
adapter, read [ADAPTER-AUTHORS.md](../migration-adapter/ADAPTER-AUTHORS.md). For adapter extensions
which are ready to use, see [https://www.vanillabp.io](https://www.vanillabp.io).

## Modules

To understand the documentation below, read the Quarkus guide
"[Writing your own extension](https://quarkus.io/guides/writing-extensions)". It explains the
concepts of Quarkus extensions.

1. **[deployment](./deployment):**<br>
   The deployment module of the extension. It analyzes the code, loads the configuration and
   prepares the CDI beans for runtime.
2. **[runtime](./runtime):**<br>
   The runtime module of the extension. At runtime, it connects to the
   [VanillaBP migration adapter](../migration-adapter).
3. **[quarkus-support](./quarkus-support):**<br>
   A small set of useful things for Quarkus. Use it as a dependency instead of
   `io.vanillabp:spi-for-java`.
4. **[integration-tests](./integration-tests):**<br>
   Modules which make sure that the VanillaBP Quarkus extension works as documented.

## Implementation concepts

Spring Boot uses reflection at runtime. The Quarkus integration does as much as possible at
**build time**, as Quarkus extensions are meant to:

1. **Code analysis via Jandex:** The build finds `@WorkflowService` classes and
   `AggregatePersistenceAware` implementations in the Jandex index
   (`ProcessServiceBuildStepProcessor`). So workflow sub-modules with such classes must be indexed
   with the `jandex-maven-plugin`. To detect a workflow module, the Jandex index is *not* needed.
   The `META-INF/workflow-module` descriptor is registered as an additional marker for application
   archives (`AdditionalApplicationArchiveMarkerBuildItem`). So the build also finds JARs which
   hold only the descriptor and BPMS resources, or JARs built without the plugin, for example by
   Gradle.
   The index reports the type which the annotation SITS on. So two cases end the build: an
   interface with `@WorkflowService`, and an annotation of the application which carries it. The
   message names the type and the classes which brought it in. The reason is that an interface
   would serve the methods declared in it, but Spring Boot would serve those of the implementing
   class.
   For the same reason, the build resolves `@Inherited` itself. The class which the annotation
   sits on is a DECLARATION. The workflow services are the classes which serve it. So the build
   goes from each declaration down to the classes which the index reports as its subclasses, and
   registers those (see decision 32 in the repository's DECISIONS.md). Each of them brings its own
   workflow module, its own BPMN process id if the annotation names none, and its own methods for
   the core to scan. A native image gets a registration for reflection. It covers the class and
   every superclass up to the declaration, because a handler which the class inherits is declared
   there. Abstract classes are left out, because CDI gives out no instance of one. If all classes
   of a declaration are abstract, the build ends and names it.
2. **Bean generation via Gizmo:** For each workflow aggregate, the build generates the bytecode of
   a `ProcessService_<Aggregate>` CDI bean class which extends `ProcessServiceBaseCdiBean<A>`. This
   is what Spring does with a `BeanDefinition` registration. The runtime base class connects to the
   `MigrationProcessService` of the [migration adapter](../migration-adapter). It builds one for
   each BPMN process id which its workflow service classes declare, secondary ids included
   (`getProcessServicesOfDeclaredIds`). The startup validations of its `StartupEvent` observer go
   through that list. The deployment runner also asks the election check about it. Everything
   which you can configure for a workflow, you can configure for each declared id. So when only the
   primary id was validated, a secondary id or an id which is only declared stayed unchecked. And
   after a rename, the running workflows keep exactly such an id. The Spring Boot integration does
   the same from `ProcessServiceSpringBean`.
3. **Workflow module detection:** `WorkflowModuleBuildStepProcessor` searches all application
   archives for `META-INF/workflow-module` marker files. The content of such a file is the workflow
   module ID. The root archive is the fallback, the *global* module.
4. **Workflow-module-specific configuration:** For the files
   `<module-id>[-<profile>].properties/.yaml`, the build generates config sources as
   `ConfigBuilder` classes. Their config ordinals are BELOW those of `application.*` (properties:
   230, YAML: 235, compared with 250 and 255). A workflow module ships defaults, and the application
   always wins. SmallRye adds to the ordinal for each active profile, and the gap to 250 makes room
   for that. So the `-prod` file of a module cannot climb above the files of the application. The
   reasons and the full table of ordinals are in the javadoc of
   `WorkflowModuleBuildStepProcessor`.
   One rule tells dev mode and the native image about those files, each in the form it needs
   (`watchAndEmbedWorkflowModuleSpecificConfigFiles`). Dev mode gets the rule as a predicate. The
   native image gets the files in the archives of that build which match the rule, by their path
   relative to their archive. A build writes a list of names once. So a configuration file added
   later is in none of those lists. If dev mode watched the names of the last build, the
   application would keep running with the configuration of that build
   (`ConfigFileAddedInDevModeTest`). The predicate is as narrow as the names which the config
   sources read, so a YAML file of the application restarts nothing. A native image holds only the
   resources it was told about. So if a file is missing from its list, a workflow module quietly
   runs on its defaults. One rule for both keeps them from drifting apart. Profile-specific files
   are part of the rule. That is why `-Dquarkus.profile=<name>` on a binary picks among the files
   of a module in the same way as on the JVM. `native-image-tests` tests this: its
   `WorkflowModuleConfigurationCheck` checks every location and both formats while the application
   starts, in its own archive and in a dependency JAR. On the JVM,
   `WorkflowModuleConfigurationIsADefaultTest` and `WorkflowModuleProfileFileBeatsPlainFileTest`
   test the same rule.
   A profile-specific file needs the file without the profile in the same directory of the same
   archive, because that is how SmallRye pairs the two. So if a module ships only
   `<module-id>-prod.yaml`, nobody reads its settings. VanillaBP does not load such a file behind
   SmallRye's back. At startup, it says which file it found and which file is missing
   (`reportConfigFilesWhichStayUnread`, the message in
   `messageAboutProfileFilesWithoutTheirPlainFile`, decision 61). The files are known when the
   application is built, and the line is written when it starts. So a native binary says it too.
   `WorkflowModuleProfileFileNeedsItsPlainFileTest` of the runtime module tests the SmallRye rule
   behind it.
   A module may put a file in the four places which the `WorkflowModuleConfigFiles` of the core
   names, and the Spring Boot integration reads the same four. They are the classpath root, a
   `config` directory, a directory named after the module, and a `config` directory inside that
   one. They are different styles, not a ranking. So a file belongs in exactly one of them. If the
   same file is found in two of them, the start ends with a message which names both
   (`placesPerFilename`, `WorkflowModuleConfigFiles.messageAboutAFileFoundInMoreThanOnePlace`,
   decision 65). The build finds the files, and the recorder ends the start. So a native binary
   refuses in the same way as the JVM. `ModuleFileInAConfigDirectoryTest` and
   `TheSameFileInTwoPlacesTest` of `deployment-integration-tests` test both halves.
   `WorkflowModuleConfigLocationsTest` of the runtime module asks the config sources themselves,
   with one test for each place.
   The application's own `application-<profile>.yaml` is a different case and stays with Quarkus.
   That list of files is resolved while the image is built. So a profile chosen when the binary
   runs adds nothing to it. During a native build,
   `ProfileSpecificApplicationFilesBuildStepProcessor` says so and names the ways to get those
   values. VanillaBP does not build a second loading mechanism next to the one of Quarkus
   (`ProfileSpecificApplicationFilesTest`).
5. **Aggregate persistence:** A CDI bean which implements `AggregatePersistenceAware` always wins,
   the most specific generic type first (`AggregatePersistenceResolver`, based on Jandex). If an
   aggregate has none, VanillaBP looks at what the aggregate is
   (`DefaultAggregatePersistenceResolver`, also at build time). A Panache repository for it wins
   (Hibernate ORM or MongoDB). Next comes the case that the aggregate is a Panache active record.
   Then a Spring Data repository for it (`quarkus-spring-data-jpa`). The chosen implementation lives
   in the runtime module (package `runtime/persistence`). Gizmo generates one `@Singleton` subclass
   for each aggregate, so the lookup at runtime finds a normal CDI bean. Two repositories for one
   aggregate fail the build. An aggregate which uses none of these ways fails it too, with a
   message which names what was looked for
   (`DefaultAggregatePersistenceResolverTest#severalRepositoriesFailTheBuild` and
   `#unknownAggregateIsNotServed`). Everything about the aggregate's ID (name, type, value) comes
   from reflection (`AggregateIdTypes`), not from the persistence framework. It is needed at build
   time and at startup, and there may be no session at that time.
   The two MongoDB implementations do not write an aggregate which did not change since `loadById`
   loaded it in the same transaction. `DocumentsAtLoad` encodes the aggregate with the codec of its
   collection at the load and right before the save, and keeps the first as a resource of the JTA
   transaction (`MongoParallelMultiInstanceTest`).
6. **Validation at build time:** `EnsureCollectedClassesAreBeansBuildStepProcessor` fails the build
   if collected classes (workflow services, persistence implementations) are not real CDI beans.
   For a persistence implementation, a bean of a subclass counts, because VanillaBP only needs an
   instance to call the methods of the class on. For a workflow service, it does not count. The
   class is registered by its own name, and a subclass of it is a different workflow service. So
   the item asks for a bean which IS that class (`aBeanOfASubclassCounts`). That way, the classes
   which the build registers and the classes which it requires to be beans stay the same set.
7. **Configuration binding:** The `vanillabp.*` tree which users see is modeled ONCE, in the core,
   which does not depend on a platform (`MigrationAdapterProperties`).
   `QuarkusMigrationAdapterProperties` stays a RUN_TIME `@ConfigMapping`, because module config
   files and environment overrides need the runtime config. The transformer copies it onto the core
   model with the hand-written mapper `QuarkusMigrationAdapterPropertiesMapper`. MapStruct wrote
   this mapper until story 639. It was removed because the annotation put `org.mapstruct:mapstruct`
   on the classpath of every application, and no published artifact may make an application fetch
   MapStruct (see decision 81 in DECISIONS.md). What the generator caught at compile time,
   `QuarkusMigrationAdapterPropertiesMapperGuardTest` catches now. It goes through both sides by
   reflection and fails on a value which nothing reads or nothing writes. Defaults and ALL
   validation which guides the user run in the core. The transformer keeps only the capability
   checks.
   Adapter extensions add their own keys to the shared tree through an overlay of their own: a
   RUN_TIME `@ConfigRoot @ConfigMapping(prefix = "vanillabp")` (for an example, see
   `DummyAdapterOverlayProperties` of the BPMS double). On purpose, there is NO general
   `withMappingIgnore("vanillabp.**")`. SmallRye's check for unknown keys passes if ANY registered
   mapping knows a key. So the platform mapping and the adapter overlays check the tree together,
   and a typo under `vanillabp.*` fails the startup. Here Quarkus is stricter than Spring Boot,
   and we accept that. `QuarkusMigrationAdapterPropertiesMapperTest` tests the mapping onto the core
   model, `UnknownPropertyKeyConfigurationTest` tests the typo, and
   `EnvironmentVariableMisbindingTest` tests the environment variable which does not bind.
8. **Runtime deployment pipeline:** `VanillaBpDeploymentRunner` (runtime module) observes the
   `StartupEvent`. It runs the core `DeploymentService` for every workflow module found on the
   classpath:
   `readBpmn → prepareBpmn → wireBpmn → deployResources → startWorkflowProcessing`.
   It follows the `deployment-failure` policy and wires extensions in the order of `getOrder()`.
   On Spring Boot, `SpringBootDeploymentService` does the same.
   Details:
   - **BPMN index:** `resources-location` is RUN_TIME configuration, and a fast-jar cannot search
     for `**/*.bpmn` by pattern at runtime. So at build time, the build indexes all `.bpmn`
     resource paths of all application archives (`DeploymentPipelineBuildStepProcessor`). It
     records them as the synthetic `BpmnResourceIndex` bean, and at runtime the configured location
     filters them. Only classpath locations are supported. A `file:` location fails with a message
     which guides you. Dev mode watches `.bpmn` and `.dmn` files by their extension, not by the
     names of the last build. So a file added while the application runs restarts it, and the file
     is in the index after that restart.
   - **Adapters** announce their deployment services with
     `VanillaBpAdapterDeploymentServiceBuildItem`, like the build item for process services. The
     announced producer gives ONE bean of type `List<AdapterDeploymentService<Object, Object>>`,
     with one instance for each configured adapter id. Both type parameters of the elements are
     LITERALLY `Object`, whatever the adapter's model and context classes are. CDI does not match
     parameterized types with different type arguments in a reliable way across modes. So the
     platform looks the beans up with the exact type. The pipeline matches models through
     `getModelType()`/`getProcessContextType()`, never through the generics. Producer methods must
     be `@Singleton`, because deployment and wiring services have no no-arg constructor and cannot
     have a client proxy.
   - **Extensions** add plain `ExtensionWiringService` element beans. The platform keeps them from
     being removed by ArC as unused beans. See the
     [dummy extension](./integration-tests/dummy-extension) as the template.
   - **Order compared with the phase-two outbox:** The runner observes the `StartupEvent` with
     `@Priority(VanillaBpDeploymentRunner.STARTUP_PRIORITY)`, and the outbox dispatchers with
     `OUTBOX_DISPATCHER_STARTUP_PRIORITY`. So a phase-two operation which survived a crash is never
     sent before the deployment finished and workflow processing started. Spring Boot makes sure of
     the same with `@Order`-ed `ApplicationReadyEvent` listeners.
   - **Lifecycle compared with Spring Boot (documented and accepted):** Spring deploys while the
     context refreshes, and starts workflow processing on `ApplicationReadyEvent`, after the web
     server started to serve. On Quarkus, both happen in the `StartupEvent` observer, so before the
     application serves traffic. On shutdown, Spring stops workflow processing before the web
     server stops serving. The Quarkus `ShutdownEvent` fires after the HTTP server stopped
     accepting requests. Both platforms stop extensions and adapters in the reverse order of their
     start, and stop all process services after that
     (`VanillaBpShutdownObserver` → `VanillaBpDeploymentRunner.stop()`).

## What a message of this module names

An application without a BPMS adapter is told where to get one. If no Quarkus extension publishes a
capability `io.vanillabp.adapter.*`, there is no BPMS to run a workflow module. So the build ends
with that sentence. It adds a link to the wiki page which lists every adapter with the name of its
Quarkus extension. And it notes that adapter versions do not come from the VanillaBP BOM
(`BpmsAdapters`). On purpose, the code does not list the adapters. They are released on their own,
so a list in a JAR soon gets out of date. The Spring Boot side says the same in its own words, but
from its support module. There the adapter is what brings the integration with it. So the
integration cannot be the one to report that the adapter is missing.

When a message names a bean, it names the class which the application wrote, not the runtime
class. The runtime class of a normal-scoped CDI bean is the client proxy of the container. A name
which ends in `_ClientProxy` belongs to no file which the developer can open. `Instance#handles()`
gives a handle for each bean, and `Handle#getBean().getBeanClass()` is the declared class.
`QuarkusTransactionRunnerResolver` reports that class. We never cut the suffix off a runtime class
name. That would guess a naming convention of the container, and it breaks on the day the container
changes it. `NoAdapterExtensionTest` tests the build failure without an adapter.
`QuarkusTransactionRunnerResolverTest` tests the class which a message without an adapter names.

## Hints

### Which tests a build runs

Surefire runs the classes named `*Test`, and Failsafe runs the classes named `*IT`. But Failsafe
does so only if the plugin declaration names its goals. An element without `<executions>` binds
nothing to a phase. Then an `*IT` class below it runs in no build at all. `integration-tests/pom.xml`
was in that state until 2026-09-18, and its two `*IT` classes had never run.

So a class here is named `*IT` only if it needs the packaged application.
`@QuarkusIntegrationTest` starts the built artifact as a process of its own, so its module also has
to run `quarkus-maven-plugin:build`. Everything else is a `*Test`, whatever it starts.
`QuarkusDevModeTest`, `QuarkusProdModeTest` and `QuarkusExtensionTest` build what they need
themselves.

### Logging during tests

There are three things, and each one is there for a reason which was measured:

1. `@ExtendWith(SuppressOutputExtension.class)` on every test class keeps what the class prints in
   a buffer, and writes it out when a test fails. This includes what is logged through JBoss
   LogManager. Swapping `System.out` alone never reached that.
   It has to be the FIRST annotation on the class which registers an extension. JUnit registers
   declarative extensions in the order in which they are written. An extension written above the
   suppression prints before the suppression is active. `@Testcontainers` starts its container and
   logs, `@SpringBootTest` starts a context, and nothing captures either of them yet. Watch out for
   annotations which register an extension without saying so, because they look like
   configuration. `@SpringBootTest`, `@DataJpaTest` and `@QuarkusTest` all have an `@ExtendWith` of
   their own. `TestClassConventions` of `test-utils` fails a build if the suppression is below
   `@Testcontainers`. For the other annotations, the review has to check it.
2. `<quarkus.log.level>INFO</quarkus.log.level>`, not `ERROR` as before. A record which is dropped
   because of its level reaches no handler. So it never reaches the capture either, and a failing
   class then replays nothing useful.
3. `redirectTestOutputToFile`. The tests of these modules start their application IN the test JVM,
   and Quarkus logs that start into a log context of its own, which the capture never sees. Without
   the redirection, a green run of `deployment-integration-tests` printed 311 lines. The start runs
   in the `beforeAll` of the Quarkus extension, after ours and before our first `beforeEach`. What
   is written to `System.out` in that window is now kept and replayed before a failing test. But
   those lines are not written there.

The third one has a cost. The replay of a failing class ends up in
`target/failsafe-reports/<class>-output.txt`, and Surefire prints only its last line. So when a
build fails, the workflow uploads these reports as an artifact. With one download, you have the
whole log of a red run.

The Quarkus test modules of the adapter repositories need no redirection. Their tests start their
application in a FORKED JVM, which writes its own log file. What they print in the test JVM is a
dozen lines per class, from before the first callback.

It is good to know which window the third one covers, because we found it the hard way. These
modules run under JBoss LogManager, which the Surefire property `java.util.logging.manager`
installs. A handler of that log manager keeps the `System.out` which existed when the handler was
created. The extension used to only replace `System.out`. So it reached nothing which was logged
through such a handler. The augmentation line of a `QuarkusProdModeTest` and every Testcontainers
line of the Camunda 8 Quarkus tests went straight into the log of a green build.

Now the extension gives every stream handler it finds below the root logger a stream which looks
up `System.out` at the time of writing. This includes nested handlers of `QuarkusDelayedHandler`.
So the handler follows the capture, and the replay on failure keeps working. Switching the console
handler off would have lost that. The extension cannot cover output written BEFORE its first
callback, because there is no handler to redirect yet. In the Camunda 8 adapter, that case is a
static initializer which starts a container, and only configuration reaches it.
`JbossLogManagerCaptureTest` in `integration-tests/deployment-integration-tests` tests the
mechanism. If you remove the redirection, it fails and the marker is printed to the console.

### Ports during tests

Every test which starts a Quarkus application binds `quarkus.http.test-port`, and the default of
that key is 8081. So two builds on one machine fight over one port, and the one which starts second
dies:

```
io.quarkus.runtime.QuarkusBindException: Port already bound: 8081: Address already in use
```

That happened twice on 2026-09-17, while two agents built the platform at the same time. The class
which died was whichever one started while the other build held the port. So the error looks like a
defect of whichever build had bad luck. A CI runner builds alone, so nothing there shows it.

That is why the Surefire and Failsafe configuration in `quarkus-integration/pom.xml` sets the key to
`0` for every module below it. Zero means that the operating system picks a free port. So no test
ever asks for a port which is taken. Do not write a port number into the `application.yaml` of a
test application, and do not override the key on a `QuarkusExtensionTest`. A number written
anywhere brings the clash back. `HttpTestPortIsFreeTest` in
`integration-tests/deployment-integration-tests` fails if the setting is missing from the build.

A test which has to know the port does not need to fix one. Once the application listens, Quarkus
writes the port it really bound back into `quarkus.http.test-port`, and sets up RestAssured with it.

Two kinds of test never see that key, and both have to ask for a port of their own.

A `QuarkusDevModeTest` starts its application in dev mode, not in test mode. So the application
reads `quarkus.http.port` and takes its default of 8080. Then two builds on one machine ask for the
same port. The second application does not start, and the first one answers the requests of the
second build. So the run looks like a test which got the wrong value, not like a clash. Measured on
2026-09-20: two copies of `main-integration-test` built at the same time were red in two different
classes. The same four classes are green when the module builds alone. So each dev-mode test in
`integration-tests/main-integration-test` writes a port from `OneFreePortPerJvm` into the
`application.properties` of the application it puts together, and asks RestAssured for that port.

A `QuarkusProdModeTest` forks its application and passes `quarkus.http.port` to it. Those tests
also take their port from `OneFreePortPerJvm`.
`MultipleWorkflowServicesTest` in `integration-tests/workflowmodule-integration-tests` is the
example.

## The store of processed task deliveries

`io.vanillabp.integration.runtime.delivery` implements the core's `TaskDeliveryLog` twice. The log
is the inbound counterpart of the outbox: it records which task deliveries were already processed.
The build step `buildTaskDeliveryLog` registers the two depending on which capabilities are there
(`quarkus-agroal`, `quarkus-mongodb-client`), and `preserveTaskDeliveryLogBeans` keeps them. The
beans are looked up for each aggregate at runtime. Application code never injects them.
`QuarkusTaskDeliveryLogResolver` picks one for each workflow aggregate. As with the outbox, an
application with mixed persistence has to say itself which aggregate uses which store
(`TaskDeliveryLogAware`). The Quarkus platform does not know which persistence manages an
aggregate.

The resolver is a bean of its own. `TaskDeliveryLogResolverProducer` produces it, and the build
step `buildTaskDeliveryLogResolver` registers it. The process services inject it and do not build
one each. An extension which reads what a workflow is waiting for injects the same bean.
`PhaseTwoOutboxResolverProducer` and `TransactionRunnerProducer` already have this form, for the
same reason: two separate objects give two different answers as soon as one of them changes.
`ExtensionEnablementTest` injects it through `DeliveryLogUsingExtension`.

- `JdbcTaskDeliveryLog` gets its Agroal connection inside the running JTA transaction, so the
  connection takes part in it. The SQL and the portable DDL (data definition language, the
  statements which create the table) live in the core (`JdbcTaskDeliveryStore`), and Spring Boot
  uses them too. The table name is `vanillabp.outbox.jdbc.delivery-table`, by default
  `VANILLABP_TASK_DELIVERY` (`JdbcDeliveryTableNameTest`). `recordOfTask` and `markTaskClosed` use
  the same store. With them, a task operation chooses its BPMS from the record instead of asking a
  BPMS (decision 30).
- `MongoTaskDeliveryLog` writes into the collection which
  `vanillabp.outbox.mongo.delivery-collection` names, in the database `quarkus.mongodb.database`.
  It reads the name through the configuration, which is loaded lazily. So the bean touches no
  `vanillabp.*` key before the adapter extensions registered their overlays.
  `MongoDeliveryCollectionNameTest` writes with a renamed collection. MongoDB is not a JTA
  resource. So the record is written at once. If the transaction ends in anything but a commit, an
  interposed synchronization deletes it again. `MongoPhaseTwoOutbox` does the same best-effort
  undo for its entries. It creates the indexes of `MongoSchema.DELIVERY_INDEXES`. Among them are the
  one which the retention reads and the one over `taskId`, which `recordOfTask` reads by.
  `MongoTaskDeliveryLogTest` tests the record and its undo (`aRecordIsWrittenAndReadBack`,
  `aRolledBackTransactionLeavesNoRecord`, `theRecordOfATaskAnswersTheElection`,
  `expiredRecordsAreDeleted`).
- Both observe the `StartupEvent`. Then they create their schema, unless
  `vanillabp.outbox.create-schema` is disabled. In that case, the MongoDB half names the indexes
  which the application still has to create. They also start the core's
  `TaskDeliveryRetentionCleanup`, which calls `cleanUpExpiredRecords` as
  `vanillabp.outbox.retention` says.
- The same run refreshes the records of the tasks which are still open. The core collects the keys
  which the BPMS delivered again. `cleanUpExpiredRecords` writes them before it deletes anything.
  The JDBC store writes them in batches, and the MongoDB store with a bulk write.
- `QuarkusStoreAttributionTest` tests which store each aggregate gets
  (`eachAggregateGetsTheStoreOfItsPersistence`, `awareBeansWin`,
  `aMismatchingSingleDefaultIsRefused`).
- `JdbcPhaseTwoOutboxDispatcher` creates its table in the same way as the core's delivery store.
  This includes the case of two instances which create it at the same moment. If the DDL is
  refused, it asks the metadata again through a connection of its own. If the table is there now,
  it stays quiet. The migration adapter's README explains why this is not a question of SQL states.

## What is published where

Both contributions follow the recipe which the meters of the election cache set up. The runtime
module declares an optional dependency (`micrometer-core`, `microprofile-health-api`). The producer
uses its types. A build step registers the producer only if the matching EXTENSION is on the
deployment classpath. The signal is the processor class of the extension, not the API classes. The
API classes can come in as a transitive dependency without the extension. Then nothing produces the
`MeterRegistry` which the producer needs.

- `VanillaBpMetricsBuildStepProcessor` looks for `io.quarkus.micrometer.deployment.MicrometerProcessor`
  and registers `VanillaBpMetricsProducer`.
- `VanillaBpHealthBuildStepProcessor` looks for
  `io.quarkus.smallrye.health.deployment.SmallRyeHealthProcessor` and registers
  `VanillaBpReadinessCheck`.

Both beans are `setUnremovable()`. Micrometer and SmallRye Health collect them at startup, and
nothing injects them. `ObservabilityTest` checks the meters and the readiness answer of a started
application. `OutboxMetricsTest` checks the backlog gauge.

The gauges for pending entries of the outbox stores are registered by a `StartupEvent` observer with
`@Priority(APPLICATION + 800)`. That is later than the outbox dispatchers' own observers. The
dispatchers create their table at startup, and a store cannot count before its table exists. So
without the priority, the gauge would never appear, and nobody would notice
(`OutboxMetricsTest#outboxReportsItsBacklogAndItsDispatches`).

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
