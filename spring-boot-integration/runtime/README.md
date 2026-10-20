![Header](../../readme/vanillabp-headline.png)

# Spring Boot integration runtime

This module brings the VanillaBP SPI (service provider interface) to life in Spring Boot. It
connects to the [migration adapter](../../migration-adapter), which does not depend on a platform.
Spring Boot auto-configuration (`META-INF/spring/...AutoConfiguration.imports`) sets it up:

1. `WorkflowModuleAutoConfiguration` finds workflow modules and assigns `@WorkflowService` beans
   to them.
2. `JpaSpringDataUtilConfiguration` supports persistence with JPA. It is active only if JPA is on
   the classpath, exactly one `EntityManagerFactory` exists and no custom `SpringDataUtil` bean
   was defined.
3. `MongoDbSpringDataUtilAutoConfiguration` supports persistence with MongoDB. It is active only
   if Spring Data MongoDB is on the classpath, a `MongoDatabaseFactory` exists and no other
   `SpringDataUtil` bean was defined. It runs after the JPA configuration. So **if both JPA and
   MongoDB are configured, JPA always wins.** To use MongoDB in this case, import
   `MongoDbSpringDataUtilConfiguration` explicitly.
4. `SpringBootMigrationAdapterAutoConfiguration` turns the Spring properties into the core
   `MigrationAdapterProperties`, collects the adapters and registers one `ProcessService<A>` bean
   for each workflow aggregate (through `WorkflowServiceDiscovery` and the
   `ProcessServiceBeanRegistrar` it feeds).
5. `DeploymentAutoConfiguration` deploys the BPMN resources on `SmartLifecycle` start, and starts
   workflow processing on `ApplicationReadyEvent`.

Also, `WorkflowModulePropertiesEnvironmentPostProcessor` (registered in `spring.factories`) adds
the config files of each workflow module to the Spring `Environment`. It adds them at the END of
the property sources, because a workflow module ships defaults. Everything which the application
configures wins over them. Adding them at the end keeps this true even when an application brings
sources which this integration does not know. So no source name is matched to find a position. It
also reports a `<module-id>-<profile>` file which has no `<module-id>` file next to it. Spring Boot
reads such a file, but Quarkus does not, and a workflow module runs on both (decision 61). It also
ends the start when the same file lies in two of the four places which a module may use
(decision 65).

### Workflow module detection

A workflow module is declared by a `META-INF/workflow-module` marker file. The content of the file
is the workflow module ID. To match a `@WorkflowService` class to a module, the integration
compares the URL prefixes of their classpath roots. One prefix is the URL of the class resource
(`Class#getResource`) without the relative path of the class. The other is the URL of the marker
file without `META-INF/workflow-module`. The two prefixes are compared as the JAR or directory they
stand for, not as strings (`WorkflowModule#isDeclaredInTheArtifactOf`). The reason is a
repackaged Spring Boot JAR. Its classes sit below `BOOT-INF/classes/`, but the marker file stays at
the top of the JAR. So the class URL reads
`jar:nested:/app.jar/!BOOT-INF/classes/!/` (or `jar:file:/app.jar!/BOOT-INF/classes!/`
with the loader of Spring Boot before 3.2), and the marker URL reads
`jar:file:/app.jar!/`. Both mean the same JAR. A JAR below `BOOT-INF/lib/` stays a JAR of its
own. The same comparison decides if a module comes from the application's own artifact. That
decides where its BPMN files are searched. `StandaloneFatJarIT` starts such a JAR with
`java -jar`, and also unpacked. Services which match no marker file belong to the *global* module
(the whole application acts as a single workflow module). Only one global marker is allowed.

### Finding the workflow services

A workflow service is found because it is a Spring bean. It does not matter which JAR its class
came in or who registered the bean. The application's component scan, a `@Bean` method, an
`@Import` of a library configuration and the auto-configuration of a common library are all
visible in the same way. `WorkflowServiceDiscovery` goes through the bean definitions. It asks
each one for its class and keeps the classes with `@WorkflowService`. It finds them by reflection,
so an annotation inherited from a superclass counts. It runs as a
`BeanDefinitionRegistryPostProcessor` which is ordered last. An imported `BeanRegistrar` would
run while the configuration classes are still being read. It would miss what a later one adds,
usually an auto-configuration.

`WorkflowServiceDiscoveryTest` of the main integration test tests this. It includes the case of a
library auto-configuration, which is the reason for the order.

Being a bean is not an extra rule which this discovery makes up. Task delivery gets the handler
object through the bean factory. So a class annotated with `@WorkflowService` but without a bean
could not serve a task even if it was found. And only the bean definitions can answer the question
correctly. The active profile and every other condition decide if a service is part of THIS run.
A classpath knows nothing about that, and neither does an index written while the application was
built.

That is also why a class with the annotation which is not a bean is skipped without a message. It
looks exactly like a class which another profile brings. What such an application really misses is
checked later, against the model which the BPMS really deployed. The task wiring validation ends
the start and names the BPMN tasks which have no handler.

This has one consequence for a `@Bean` method. It has to declare the workflow service class as its
return type. A method which returns an interface tells Spring nothing about the class behind it.
The discovery reads the type, never the instance.

### ProcessService beans

`ProcessServiceBeanRegistrar` registers the `ProcessService<A>` beans. It is a Spring Framework
`BeanRegistrar`, and the discovery hands it the workflow service classes. Registration touches no
bean. Each bean is registered

- with a target type which knows the generics (`ProcessService<Ride>` via
  `ParameterizedTypeReference`/`ResolvableType`), so generic autowiring works, and
- with a lazy supplier. When the bean is created, the supplier resolves all dependencies
  (properties, persistence support, the adapters' `MigratableProcessService`s) through the
  registrar's `SupplierContext`.

The registrar registers definitions, not instances. This avoids circular dependencies between the
`@WorkflowService` bean and its `ProcessService`. The supplier resolves the dependencies later. So
Hibernate/DataSource beans and adapter beans stay out of the post-processing phase of the bean
factory, and AOP proxies and `@ConfigurationProperties` binding work for all beans involved. Each
bean wraps a `MigrationProcessService` of the migration adapter, which implements the real
behavior.

### Deployment lifecycle

`SpringBootDeploymentService` implements `SmartLifecycle`:

- `start()` loads and deploys all BPMN resources. This happens while the context refreshes, after
  all singletons were created.
- `ApplicationReadyEvent` triggers `startWorkflowProcessing`, so only when the application is fully
  ready to process workflows.
- `stop()` runs on graceful shutdown. It calls `stopWorkflowProcessing` of the core
  `DeploymentService`, which tells extensions and adapters in the reverse order of their start.
  Then it stops all `ProcessServiceSpringBean`s.

The lifecycle phase is `SmartLifecycle.DEFAULT_PHASE` (`Integer.MAX_VALUE`). On shutdown, lifecycle
beans stop from the highest phase to the lowest. So workflow processing stops in the very first
group. That is before the graceful shutdown of Spring Boot's web server (`DEFAULT_PHASE - 1024`)
and before messaging listener containers. So no new workflow jobs are processed while the
infrastructure they may depend on shuts down.

### SpringDataUtil versus AggregatePersistenceAware

The migration adapter accesses workflow aggregates through its `AggregatePersistenceAware`
interface (save an aggregate, find its ID). In Spring Boot, there are two ways to provide it:

1. **Custom bean:** The application (or an adapter) provides a bean which implements the
   `AggregatePersistenceAware` interface from
   [spring-boot-support](../spring-boot-support). If there are several candidates, the one with
   the most specific generic aggregate type wins (`AggregatePersistenceResolver`, based on the
   distance in the class hierarchy).
2. **Fallback via `SpringDataUtil`:** If there is no specific bean, the generic
   `SpringDataUtilBasedAggregatePersistenceSupport` is used. It uses the `SpringDataUtil`
   abstraction, which has two implementations:
   - `JpaSpringDataUtil` (auto-configured if JPA is there and exactly one `EntityManagerFactory`
     exists). It finds the Spring Data repository of the aggregate class. It gets IDs through the
     `PersistenceUnitUtil` of the persistence unit which is responsible for the aggregate's type,
     so it works with several persistence units. It also unwraps Hibernate proxies.
   - `MongoDbSpringDataUtil` (auto-configured if Spring Data MongoDB is there and a
     `MongoDatabaseFactory` exists). If both JPA and MongoDB are configured, JPA always wins. To
     use MongoDB for aggregate persistence in this case, import
     `MongoDbSpringDataUtilConfiguration` explicitly.

So `SpringDataUtil` is the general Spring Data mechanism *behind* the `AggregatePersistenceAware`
abstraction. A custom `AggregatePersistenceAware` bean is the extension point for any other
persistence technology.

`SpringDataUtilBasedAggregatePersistenceSupport` does not write an aggregate which did not change
since `loadById` loaded it in the same transaction. It asks `SpringDataUtil#writtenFormOf` for the
form the aggregate would be written in, once at the load and once right before the save. The
MongoDB implementation answers with the document of the `MongoConverter`, copied into values which
compare by value. The JPA implementation answers nothing, so every save writes, and Hibernate
decides itself. `FormsAtLoad` keeps the forms as a resource of the running Spring transaction, by
object identity. `ParallelMultiInstanceTest` and `WhatCountsAsAChangeTest` in
[outbox-mongo-integration-test](../integration-tests/outbox-mongo-integration-test) show what counts
as a change.

### Phase-two outbox

Everything which an application sends to its BPMS (business process management system) leaves
after the caller's transaction committed. So every application needs a transaction outbox
(`PhaseTwoOutbox` SPI of the [migration adapter](../../migration-adapter)). The outbox schedules
phase two inside the local transaction. After the commit, it sends it reliably, also after a crash
or restart, and retries with a backoff. Sent calls go through the `PhaseTwoRouter`, which the core
owns. The [migration adapter's README](../../migration-adapter/README.md) describes the chain and
the outbox contract: idempotency key, DONE instead of delete, retention. Each
`ProcessServiceSpringBean` registers itself with the router when the bean is created. The CORE
converts the serialized aggregate ID back to the aggregate's ID type
(`MigrationProcessService.convertAggregateId`, based on
`AggregatePersistenceAware.getAggregateIdType()`). Spring Data implementations give a reliable
answer. A `null` ID type means that the custom persistence layer owns the serialized form, and the
String is passed on unchanged.

The outbox is chosen **for each workflow aggregate**. The most specific `PhaseTwoOutboxAware` bean
wins. Without one, the platform default which matches the persistence technology of the aggregate
is used. It is detected from the type of the aggregate's Spring Data repository
(`SpringPhaseTwoOutboxResolver`). Both defaults may be active in the SAME application (mixed
persistence), and each entry is written in its aggregate's own transaction. The resolution happens
AT STARTUP (`SmartInitializingSingleton` in the auto-configuration) for every process service. If
an outbox is missing, the start fails and names the ways to fix it. Every outbox instance needs
its OWN store. Two dispatchers which poll the same store would compete and send entries twice.
You configure separate stores with `vanillabp.outbox.jdbc.table` /
`vanillabp.outbox.mongo.collection`, or set them up as additional beans of your own. The
`outbox-mixed-integration-test` shows how. `StoreAttributionTest` tests which store each
aggregate gets (`anOutboxAwareBeanWinsOverTheDefaults`,
`twoOutboxDefaultsAndAnUndetectableAggregateFailGuiding`,
`aProxiedStoreIsReportedByItsTargetClass`). `MixedPersistenceOutboxTest` tests it against a
started application.

### A call which carries a payload

A phase-two call carries identifiers. An extension may want to hand over the state it saw at its
sync point. Then it passes bytes as well:
`PhaseTwoCall.of(operation, module, process, aggregateId, adapterId, args, payload)`. The bytes are
not stored in the outbox entry. They are written into a store of their own, in the same
transaction. The entry names them by a reference in its arguments. Decision 62 in `DECISIONS.md`
gives the reasons.

For the outboxes which use JDBC, the store is a table `VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD`
(`vanillabp.outbox.jdbc.payload-table`). For the MongoDB ones, it is a collection
`vanillabp-phase-two-outbox-payloads` (`vanillabp.outbox.mongo.payload-collection`). It is created
together with the other tables unless `vanillabp.outbox.create-schema` is disabled.
`io.vanillabp:vanillabp-schema` describes it for Liquibase and Flyway.

Both names follow the outbox they belong to. If neither key is set, VanillaBP adds `_PAYLOAD` to
the name of the outbox table and `-payloads` to the name of the outbox collection. Some
applications rename their outbox to keep two deployments apart on one schema. Such an application
keeps the payloads apart too. A name written into one of the two keys is used as it is.

This costs one read by primary key for each attempt to send a call which carries a payload. A call
which carries none costs nothing, and it writes no row either. A payload is at most
`PhaseTwoCall.MAX_PAYLOAD_SIZE` bytes, one mebibyte. That limit holds for every store, so an
application meets the same limit whichever store it runs. On MongoDB, the bytes are a field of the
payload document. So MongoDB's own limit of 16 MB per document is never reached.

The payload is removed when its entry is marked dispatched. It is also removed together with that
entry once `vanillabp.outbox.retention` has passed. The retention counts from the entry. So an
entry which still waits, or which is blocked until somebody repairs it, keeps its payload as long
as it is in the store. The housekeeping removes by age only a payload which no entry names any
more. A crash between the two writes leaves such a payload behind. The housekeeping asks the
entries of its outbox first, and only for a payload which outlived the retention. In one case, a
payload is removed earlier: when a younger call replaced the entry which named it, see below.

### A younger call which takes the waiting entry's place

A call with a payload carries a state, and a state gets old. So such a call may ask to take the
place of the entry of its key which is still waiting, instead of being dropped because of it:
`outbox.scheduleReplacingWhatIsStillWaiting(call.replacingWhatIsStillWaiting())`. The entry keeps
its key and gets everything which the dispatch reads. The payload it named before is removed in the
same transaction. Decision 68 in `DECISIONS.md` says why the direction changed and why the mark is
on the call.

No store of this module replaces an entry which a dispatch has already taken. In that case, the
younger call becomes an entry of its own, without a key. On the JDBC store, this is the `ATTEMPTS`
column, and on MongoDB the `attempts` field. The dispatchers count it up when they claim an entry.

This module has two default implementations. The `vanillabp.outbox.*` properties configure both
(`poll-interval`, `attempt-frequency`, `block-after-attempts`, `wait-for-visibility-at-most`,
`dispatch-threads`,
`create-schema`,
`retention`, and for each default a `jdbc.*`/`mongo.*` section with an `enabled` flag and store
names). The defaults can exist next to `PhaseTwoOutbox` beans of your own. To switch off a default
you do not want, use its `enabled` flag:

1. **JPA (the JDBC store VanillaBP writes itself):** `JdbcPhaseTwoOutboxAutoConfiguration` creates
   the bean `vanillaBpJdbcPhaseTwoOutbox`. It is active when Spring Data JPA is on the classpath
   and exactly one `EntityManagerFactory` exists. What it stores and how it sends is the core's
   `JdbcPhaseTwoOutboxStore` with its `JdbcPhaseTwoOutboxDispatcher`. A Quarkus application runs
   the same code (decision 75 in `DECISIONS.md`). Two parts stay with Spring and live here. One is
   the connection which `DataSourceUtils` binds to the running transaction, so an entry becomes
   visible exactly when its aggregate does. The other is the synchronization which tells the store
   that the transaction committed. Entries are in the table `VANILLABP_PHASE_TWO_OUTBOX`
   (`vanillabp.outbox.jdbc.table`). It is created while the bean is built, unless
   `vanillabp.outbox.create-schema` is off. In that case, the integration checks that the table
   exists. `io.vanillabp:vanillabp-schema` has the statements for Liquibase and Flyway. The
   poller starts on `ApplicationReadyEvent`, AFTER workflow processing started
   (`SpringBootDeploymentService.OUTBOX_DISPATCHER_LISTENER_ORDER`). So nothing which a crashed
   instance left behind goes to a BPMS which has not seen the models yet.
   `vanillabp.outbox.dispatch-threads` says how many entries are sent at the same time, four by
   default. The workflow aggregate decides which thread takes an entry. So two operations of one
   workflow keep the order in which they were written (`DispatchLanes` in the core). The elected
   adapter id is a column of this store. So an id which the configuration no longer names is
   reported when the application STARTS, not at the first dispatch
   (`AStaleAdapterIdIsNamedAtTheStartTest`, decision 47 in `DECISIONS.md`). An application which
   ran gruelbox before is told at startup how many entries its `TXNO_OUTBOX` still holds, and what
   it can do about them.
2. **MongoDB (own implementation):** `MongoPhaseTwoOutbox` writes entries into the collection
   `vanillabp-phase-two-outbox` through `MongoTemplate`, inside the current transaction. It stores
   all `PhaseTwoCall` fields and the idempotency key. It finds duplicates through `dedupKey`, which
   has a unique index. The index is created automatically, unless `create-schema` is disabled. In
   that case, the startup names it, with the other indexes of `MongoSchema`, as something the
   application has to create itself. Earlier versions created a sparse index over
   `idempotencyKey`. It is dropped where it is still there. That field holds the key while the
   entry waits, and the entry's own id once it was sent.
   `MongoPhaseTwoOutboxDispatcher` claims due OPEN entries atomically (find-and-modify with
   attempts/backoff). It marks them DONE after a successful dispatch. Once the retention has passed,
   the housekeeping deletes them in its night window. Entries which fail again and again are marked BLOCKED. Each of those
   writes names `leasedBy` next to the id. So when another node took over an entry while a node
   sent it, that node writes no result over the result of the node which holds the entry now
   (`AMongoDispatchWhichLostItsLeaseTest`).
   Its poller runs on a private executor with a single thread (no `TaskScheduler`). The dispatch
   itself runs on the same `vanillabp.outbox.dispatch-threads` lanes which the JDBC store uses. So
   the entries of one workflow keep their order, and entries of different workflows are sent at the
   same time (`MongoEntriesOfOneAggregateKeepTheirOrderTest`, decision 77 in `DECISIONS.md`).
   **Note:** To take part in the transaction, MongoDB transactions are needed, so a replica set and
   a `MongoTransactionManager` bean. Otherwise scheduling is only best effort. A read before the
   write finds duplicate schedules, because a duplicate-key error would abort the whole MongoDB
   transaction. The unique index stays as the last guard against duplicates at the same time.

If both JPA and MongoDB are configured, JPA always wins (as with the `SpringDataUtil`
auto-configurations). To use a different outbox (for example another database or an existing
outbox infrastructure), define a bean which implements
`io.vanillabp.integration.spi.PhaseTwoOutbox`. Then the auto-configurations back off.

A third store exists outside this repository. `io.vanillabp:gruelbox-phase-two-outbox` keeps the
entries in the [gruelbox transaction-outbox](https://github.com/gruelbox/transaction-outbox). That
is where VanillaBP kept them until release 2.0. It registers the bean
`vanillaBpJdbcPhaseTwoOutbox` itself and is applied before the JDBC default. The JDBC default has
`@ConditionalOnMissingBean` on that name. So adding the artifact is all it takes to switch, and
`vanillabp.outbox.gruelbox.enabled=false` is the way back. Both keys and the store itself belong
to that artifact. This module neither binds nor describes them. What that store can and cannot do
is documented where it is built.

What is left here is one message. The JPA default says how many entries of `TXNO_OUTBOX` were not
sent yet. So an application which upgrades to this store learns that its old entries lie in a
table which nothing reads any more.

### What an IDE proposes for the `vanillabp` keys

Spring Boot ships the keys it knows in `META-INF/spring-configuration-metadata.json`. A development
environment reads that file to propose keys and show their documentation. The annotation processor
writes it while this module is compiled, but it only sees the properties classes of this module.
The sections below them (`vanillabp.outbox.*`, `vanillabp.delivery.*` and the other core sections)
come as a dependency, and the processor does not look into a model which it gets that way. Their
keys come from `src/main/resources/META-INF/additional-spring-configuration-metadata.json`, which is
written by hand.

So nothing proposes a key added to the core model until it is described there. And a key which
only lives in a condition is in no properties class at all. `vanillabp.outbox.gruelbox.enabled` is
such a key. The store which it switches lives in another artifact. This module only reads the key
to end a start which asks for a store that nothing can build. It is described in the hand-written
file, and `EveryPropertyIsOfferedByTheIdeTest` names it as the one key described for another
artifact. `EveryKeyOfAConditionIsInTheMetadataTest` reads the conditions of every
auto-configuration and asks the metadata about each key they name. So the next such key fails the
build, not somebody's editor.

`EveryPropertyIsOfferedByTheIdeTest` checks the other direction. It goes through the core's
properties classes in the same way as the binder, and compares what it finds with the file, in
both directions. A property added without its entry fails the build. So does an entry left behind
by a key which was renamed. Both tests exist because the file is written by hand, and people forget
files which are written by hand.

### Separating workflow module properties from application properties

Read the [Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules) to learn why you might have several workflow modules.
In that case, the `application.yaml` files should not list properties which belong to one of those
workflow modules. Otherwise the properties of the workflow module would be in a different
(Maven/Gradle) module than its code. You could not test the workflow module on its own, because
the properties would be missing. Also, a change to the properties of one workflow module in
`application.yaml` might affect others without anybody meaning to.

To solve this, the VanillaBP integration for Spring Boot loads additional YAML files for each
workflow module.

These files are not named `application-xxxx.yaml` (where `xxxx` may name active Spring Boot
profiles). They are named `yyyy-xxxx.yaml`, where `yyyy` is the ID of the workflow module. Put your
files in the classpath root, with these source folders in your workflow module:

- `src/main/resources/loan-approval.yaml`<br>
  (common properties of workflow module `loan-approval`)
- `src/main/resources/loan-approval-environment-dev.yaml`<br>
  (properties for the Spring Boot profile `environment-dev`)

There are four places to choose from. They are different styles, not a ranking:

- `src/main/resources/loan-approval.yaml`
- `src/main/resources/config/loan-approval.yaml`
- `src/main/resources/loan-approval/loan-approval.yaml`
- `src/main/resources/loan-approval/config/loan-approval.yaml`

Quarkus reads the same four. None of them ranks above another, so the same file may lie in exactly
one of them. If a module ships `loan-approval.yaml` at the root and in `config`, the start ends with
a message which names both places (decision 65).

To avoid name clashes between properties, a workflow module has to use its own properties section.
It is usually named after the workflow module ID:

- `src/main/resources/loan-approval.yaml`:<br>

  ```yaml
  loan-approval: # all properties of workflow module "loan approval"
    max-loan-amount: 10000
    banking-system:
      url: to-be-defined-for-each-environment # invalid URL to ensure value is overwritten for each target environment
      read-timeout: 30000 # property used by banking-system client
  ```
- `src/main/resources/loan-approval-environment-dev.yaml`:<br>

  ```yaml
  loan-approval:
    banking-system:
      url: https://core-banking-system # set URL for environment 'dev'
      read-timeout: 10000 # override default value for environment 'dev'
  ```

Finally, define and configure a Java class which holds those values:

```java
@Configuration
@EnableConfigurationProperties(LoanApprovalProperties.class)  // add this
public class LoanApprovalConfiguration {
  ...
}

@ConfigurationProperties(prefix = LoanApprovalConfiguration.WORKFLOW_MODULE_ID)
@Getter
@Setter
public class LoanApprovalProperties {
  private int maxLoanAmount;
  private BankingSystemProperties bankingSystem;
}

@Getter
@Setter
public class BankingSystemProperties {
  private String url;
  private long readTimeout;
}
```

With this setup, the property values are part of the workflow module. You can inject the bean of
the class `LoanApprovalProperties` into services of the workflow module. It gives them the
properties which Spring Boot set for the active Spring profiles.

#### These files are defaults

`loan-approval.yaml` above sets the URL of the banking system to an invalid value on purpose. This
only works because the application can override it. That is the rule: a workflow module file is
the weakest source in the environment. System properties, environment variables and every
configuration file of the application win over it. But `loan-approval-environment-dev.yaml` still
wins over `loan-approval.yaml`. It also means that a placeholder in a module file
(`${external-systems.banking.hostname}`) resolves against whatever the application configured.
Both directions have acceptance tests in
`integration-tests/workflowmodule-integration-tests/submodule-integration-test`. Quarkus gives the
same answer through the ordinals in `WorkflowModuleBuildStepProcessor`.
