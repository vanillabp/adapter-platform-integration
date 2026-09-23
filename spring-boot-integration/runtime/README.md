![Header](../../readme/vanillabp-headline.png)

# Spring Boot integration runtime

This module brings the VanillaBP SPI to life in Spring Boot and bridges to the
platform-neutral [migration adapter](../../migration-adapter). It is wired up via
Spring Boot auto-configuration (`META-INF/spring/...AutoConfiguration.imports`):

1. `WorkflowModuleAutoConfiguration` — detects workflow modules and assigns
   `@WorkflowService` beans to them.
2. `JpaSpringDataUtilConfiguration` — JPA-based persistence support (only if JPA is on
   the classpath, exactly one `EntityManagerFactory` exists and no custom
   `SpringDataUtil` bean was defined).
3. `MongoDbSpringDataUtilAutoConfiguration` — MongoDB-based persistence support (only
   if Spring Data MongoDB is on the classpath, a `MongoDatabaseFactory` exists and no
   other `SpringDataUtil` bean was defined). It is ordered after the JPA
   configuration: **if both JPA and MongoDB are configured, JPA wins
   deterministically.** To force MongoDB in this situation, import
   `MongoDbSpringDataUtilConfiguration` explicitly.
4. `SpringBootMigrationAdapterAutoConfiguration` — transforms Spring properties into
   the core `MigrationAdapterProperties`, collects adapters and registers one
   `ProcessService<A>` bean per workflow aggregate (via `WorkflowServiceDiscovery`
   and the `ProcessServiceBeanRegistrar` it feeds).
5. `DeploymentAutoConfiguration` — deploys BPMN resources on `SmartLifecycle` start
   and starts workflow processing on `ApplicationReadyEvent`.

Additionally, `WorkflowModulePropertiesEnvironmentPostProcessor` (registered in
`spring.factories`) merges workflow-module-specific config files into the Spring
`Environment`. They are appended at the END of the property sources, because a
workflow module ships defaults: everything the application configures wins over
them. Appending is what keeps that true for an application bringing sources this
integration cannot know about, so no source name is matched to find a position. It also
names a `<module-id>-<profile>` file lying without its `<module-id>` file: Spring Boot
reads such a file and Quarkus does not, and a workflow module runs on both (decision 61). It
also ends the boot where the same file lies in two of the four places a module may use
(decision 65).

### Workflow module detection

Workflow modules are declared by a `META-INF/workflow-module` marker file whose
content is the workflow module ID. `@WorkflowService` classes are matched to a module
by comparing classpath-root URL prefixes: the URL of the class resource
(`Class#getResource`) minus the class' relative path against the URL of the marker
file minus `META-INF/workflow-module`. Comparing URL prefixes works for all class
loaders — plain classpath (`file:`), JARs (`jar:file:`) and Spring Boot repackaged
fat JARs (`jar:nested:`, used by the Boot loader since 3.2). Services not matching
any marker file belong to the *global* module (the whole application acting as a
single workflow module) — only one global marker is allowed.

### Finding the workflow services

A workflow service is found because it is a Spring bean, whatever JAR its class came
in and whoever registered the bean: the application's component scan, a `@Bean`
method, an `@Import` of a library configuration or the auto-configuration of a common
library are all equally visible. `WorkflowServiceDiscovery` walks the bean
definitions, asks each one for its class and keeps the classes carrying
`@WorkflowService` (found by reflection, so an annotation inherited from a superclass
counts). It runs as a `BeanDefinitionRegistryPostProcessor` ordered last, because an
imported `BeanRegistrar` would run while the configuration classes are still being
read and would miss whatever a later one - an auto-configuration, typically -
contributes.

`WorkflowServiceDiscoveryTest` of the main integration test holds this, including the
library-auto-configuration case which the ordering exists for.

Being a bean is not an extra requirement this discovery invents. Task delivery
resolves the handler object through the bean factory, so a class annotated
`@WorkflowService` without a bean could not serve a task even if it was found. And
the bean definitions are the only place where the question can be answered
correctly: whether a service is part of THIS run is decided by the active profile and
by every other condition, which a classpath - or an index written while the
application was built - knows nothing about.

Which is also why a class carrying the annotation without being a bean is passed over
in silence, rather than reported: it looks exactly like a class another profile
brings. What such an application really misses is answered further down, against the
model the BPMS actually deployed - the task wiring validation ends the boot naming the
BPMN tasks left without a handler.

One consequence for a `@Bean` method: it has to declare the workflow service class as
its return type. A method declared to return an interface tells Spring nothing about
the class behind it, and the discovery reads the type, never the instance.

### ProcessService beans

`ProcessService<A>` beans are registered by `ProcessServiceBeanRegistrar`, a Spring
Framework `BeanRegistrar` the discovery hands the workflow service classes to. At
registration time no bean is touched. Each bean is registered

- with a generics-aware target type (`ProcessService<Ride>` via
  `ParameterizedTypeReference`/`ResolvableType`), so generic autowiring works, and
- with a lazy supplier resolving all dependencies (properties, persistence support,
  the adapters' `MigratableProcessService`s) through the registrar's
  `SupplierContext` at bean-creation time.

Registering definitions instead of instances avoids circular dependencies between
the `@WorkflowService` bean and its `ProcessService`. Deferring dependency resolution
to the supplier keeps Hibernate/DataSource and adapter beans out of the bean-factory
post-processing phase, so AOP proxying and `@ConfigurationProperties` binding work
for all beans involved. Each bean wraps a `MigrationProcessService` of the migration
adapter which implements the actual behavior.

### Deployment lifecycle

`SpringBootDeploymentService` implements `SmartLifecycle`:

- `start()` loads and deploys all BPMN resources — during context refresh, after all
  singletons were created;
- `startWorkflowProcessing` is triggered by `ApplicationReadyEvent` (only once the
  application is fully ready to process workflows);
- `stop()` runs on graceful shutdown: it calls `stopWorkflowProcessing` of the core
  `DeploymentService` (notifying extensions and adapters in reverse start order) and
  stops all `ProcessServiceSpringBean`s.

The lifecycle phase is `SmartLifecycle.DEFAULT_PHASE` (`Integer.MAX_VALUE`): on
shutdown, lifecycle beans stop in descending phase order, so workflow processing
stops in the very first group — before Spring Boot's web server graceful shutdown
(`DEFAULT_PHASE - 1024`) and before messaging listener containers. This way no new
workflow jobs are processed while the infrastructure they may depend on is torn
down.

### SpringDataUtil versus AggregatePersistenceAware

The migration adapter accesses workflow aggregates through its
`AggregatePersistenceAware` interface (save an aggregate, determine its ID). In Spring
Boot there are two ways to provide it:

1. **Custom bean:** The application (or an adapter) provides a bean implementing the
   `AggregatePersistenceAware` interface from
   [spring-boot-support](../spring-boot-support). If several candidates exist, the one
   with the most specific generic aggregate type is chosen
   (`AggregatePersistenceResolver`, based on inheritance distance).
2. **Fallback via `SpringDataUtil`:** If no specific bean exists, the generic
   `SpringDataUtilBasedAggregatePersistenceSupport` is used. It relies on the
   `SpringDataUtil` abstraction for which two implementations exist:
   - `JpaSpringDataUtil` (auto-configured if JPA is present and exactly one
     `EntityManagerFactory` exists): resolves the Spring Data repository of the
     aggregate class, determines IDs via the `PersistenceUnitUtil` of the persistence
     unit responsible for the aggregate's type (multi-persistence-unit safe) and
     unproxies Hibernate proxies.
   - `MongoDbSpringDataUtil` (auto-configured if Spring Data MongoDB is present and a
     `MongoDatabaseFactory` exists). If both JPA and MongoDB are configured, JPA wins
     deterministically — import `MongoDbSpringDataUtilConfiguration` explicitly to
     force MongoDB-based aggregate persistence in this situation.

So `SpringDataUtil` is the Spring-Data-generic mechanism used *behind* the
`AggregatePersistenceAware` abstraction, while a custom `AggregatePersistenceAware`
bean is the extension point for any other persistence technology.

### Phase-two outbox

Everything an application sends to its BPMS leaves after the caller's transaction
committed, so every application needs a transaction outbox (`PhaseTwoOutbox` SPI of the
[migration adapter](../../migration-adapter)): it schedules phase two within the local
transaction and dispatches it reliably after the commit (also after a crash/restart,
retrying with a backoff). Dispatched calls are routed
through the core-owned `PhaseTwoRouter` (see the
[migration adapter's README](../../migration-adapter/README.md) for the chain and
the outbox contract — idempotency key, DONE instead of delete, retention). Each
`ProcessServiceSpringBean` registers itself with the router at bean creation; the
serialized aggregate ID is converted back to the aggregate's ID type by the CORE
(`MigrationProcessService.convertAggregateId`, based on
`AggregatePersistenceAware.getAggregateIdType()` - Spring Data implementations
answer authoritatively, a `null` ID type means the custom persistence layer owns
the serialized form and the String is passed through unchanged).

The outbox is selected **per workflow aggregate**: the most specific
`PhaseTwoOutboxAware` bean wins; without one, the platform default matching the
persistence technology managing the aggregate is used (detected from the
aggregate's Spring Data repository type - `SpringPhaseTwoOutboxResolver`). Both
defaults may be active in the SAME application (mixed persistence), each entry
riding its aggregate's own transaction. Resolution happens AT STARTUP
(`SmartInitializingSingleton` in the auto-configuration) for every process service -
a missing outbox fails the boot naming the remedies. Every outbox instance needs its OWN store: two
dispatchers polling the same store would compete and double-dispatch (dedicated
stores are configured via `vanillabp.outbox.jdbc.table` /
`vanillabp.outbox.mongo.collection`, or set up as additional user-defined beans -
see the `outbox-mixed-integration-test` for the recipe). The attribution is held by
`StoreAttributionTest` (`anOutboxAwareBeanWinsOverTheDefaults`,
`twoOutboxDefaultsAndAnUndetectableAggregateFailGuiding`,
`aProxiedStoreIsReportedByItsTargetClass`) and, against a booted application, by
`MixedPersistenceOutboxTest`.

### A call which carries a payload

A phase-two call carries identifiers, and an extension which wants to hand over the state
it saw at its sync point passes bytes as well:
`PhaseTwoCall.of(operation, module, process, aggregateId, adapterId, args, payload)`. The
bytes do not travel in the outbox entry. They are written into a store of their own, in the
same transaction, and the entry names them by a reference in its arguments - the reasoning
is decision 62 in `DECISIONS.md`.

The store is a table `VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD` for the JDBC-backed outboxes
(`vanillabp.outbox.jdbc.payload-table`) and a collection `vanillabp-phase-two-outbox-payloads`
for the MongoDB ones (`vanillabp.outbox.mongo.payload-collection`). It is created together with
the other tables unless `vanillabp.outbox.create-schema` is disabled, and
`io.vanillabp:vanillabp-schema` describes it for Liquibase and Flyway.

Both names follow the outbox they belong to. Where neither key is set, VanillaBP appends
`_PAYLOAD` to the name of the outbox table and `-payloads` to the name of the outbox collection. So
an application which renames its outbox to keep two deployments apart on one schema keeps the
payloads apart too. A name written into one of the two keys is used as it stands.

The form costs one read by primary key per dispatch attempt of a call which carries a
payload, and nothing at all for a call which carries none - such a call writes no row
either. A payload is at most `PhaseTwoCall.MAX_PAYLOAD_SIZE` bytes, one mebibyte, and that
limit holds for every store, so an application meets the same one whichever store it runs.
On MongoDB the bytes are a field of the payload document, which is why MongoDB's own limit
of 16 MB per document is never reached.

The payload is removed when its entry is marked dispatched, and again with that entry
itself once `vanillabp.outbox.retention` passed. The retention counts at the entry, so an
entry which still waits, or which is blocked until somebody repairs it, keeps its payload
for as long as it is in the store. What the housekeeping removes by age is a payload no
entry names any more, which is what a crash between the two writes leaves behind: it asks
the entries of its outbox first, and only where a payload outlived the retention. A
payload is removed earlier in one case: where a younger call replaced the entry which
named it, see below.

### A younger call which takes the waiting entry's place

A call carrying a payload carries a state, and a state goes stale. So such a call may ask
to take the place of the entry of its key which is still waiting, instead of being dropped
against it: `outbox.scheduleReplacingWhatIsStillWaiting(call.replacingWhatIsStillWaiting())`.
The entry keeps its key, gets everything the dispatch reads, and the payload it named before
is removed in the same transaction. Decision 68 in `DECISIONS.md` says why the direction
turned and why the mark hangs on the call.

Every store of this module refuses to replace an entry a dispatch has already taken; the
younger call becomes an entry of its own then, holding no key. On the JDBC store that is the
`ATTEMPTS` column and on MongoDB the `attempts` field, which the dispatchers count up when
they claim an entry. On gruelbox it
takes two answers, because gruelbox reaches a dispatch two ways: an entry a flush picked up
was pushed back first and carries `version > 0`, while an entry submitted right after its
commit still reads as untouched although gruelbox holds its row with `SELECT ... FOR UPDATE`
until the handler returns. The second case is asked of `GruelboxRedispatchAwareSubmitter`,
which sees every entry before its invocation and keeps the ids it is dispatching. Without
that question a delete would wait for the lock, and with it a business transaction would wait
for a remote call.

This module provides two
default implementations and a third one an application can ask for, all configured by the `vanillabp.outbox.*` properties
(`poll-interval`, `attempt-frequency`, `block-after-attempts`, `dispatch-threads`,
`create-schema`,
`retention`, plus per-default `jdbc.*`/`mongo.*` sections with `enabled` flags and
store names). The defaults coexist with user-defined `PhaseTwoOutbox` beans -
disable an unwanted default via its `enabled` flag:

1. **JPA (the JDBC store VanillaBP writes itself):** `JdbcPhaseTwoOutboxAutoConfiguration`
   creates the bean `vanillaBpJdbcPhaseTwoOutbox`, active when Spring Data JPA is on the
   classpath and exactly one `EntityManagerFactory` exists. What it stores and how it
   dispatches is the core's `JdbcPhaseTwoOutboxStore` with its
   `JdbcPhaseTwoOutboxDispatcher`, the same code a Quarkus application runs (decision 75 in
   `DECISIONS.md`). Two halves stay Spring's and live here: the connection
   `DataSourceUtils` binds to the running transaction, so an entry becomes visible exactly
   when its aggregate does, and the synchronization which tells the store that the
   transaction committed. Entries lie in the table `VANILLABP_PHASE_TWO_OUTBOX`
   (`vanillabp.outbox.jdbc.table`), which is created while the bean is built unless
   `vanillabp.outbox.create-schema` is off - the table's existence is verified instead then,
   and `io.vanillabp:vanillabp-schema` carries the statements for Liquibase and Flyway. The
   poller starts on `ApplicationReadyEvent` AFTER workflow processing did
   (`SpringBootDeploymentService.OUTBOX_DISPATCHER_LISTENER_ORDER`), so nothing a crashed
   instance left behind is carried to a BPMS which has not seen the models yet.
   `vanillabp.outbox.dispatch-threads` says how many entries are dispatched at the same
   time, four by default, and which of the threads takes an entry follows from the workflow
   aggregate, so two operations of one workflow keep the order they were written in
   (`DispatchLanes` in the core). The elected adapter id is a column of this store, so an id
   the configuration no longer names is reported while the application STARTS rather than at
   the first dispatch (`AStaleAdapterIdIsNamedAtTheStartTest`, decision 47 in `DECISIONS.md`).
   An application which ran gruelbox before is told at startup how many entries its
   `TXNO_OUTBOX` still holds and what it can do about them.
2. **MongoDB (own implementation):** `MongoPhaseTwoOutbox`
   writes entries into the collection `vanillabp-phase-two-outbox` via
   `MongoTemplate` within the current transaction, persisting all `PhaseTwoCall`
   fields plus the idempotency key, deduplicating over `dedupKey` (unique index,
   created automatically unless `create-schema` is disabled — then create it
   manually; the sparse index earlier versions created over `idempotencyKey` is
   dropped where it is still there). That field carries the key while the entry waits
   and the entry's own id once it was dispatched.
   `MongoPhaseTwoOutboxDispatcher` claims due OPEN entries atomically
   (find-and-modify with attempts/backoff), marks them DONE after successful
   dispatch and deletes DONE entries once the retention passed; repeatedly failing
   entries are marked BLOCKED. It also runs on a private single-thread executor
   (no `TaskScheduler`), and it keeps that one thread: the lanes of the JDBC store are not
   tied to JDBC, but whether this store needs them is a question somebody has to measure
   first. **Note:** transactional enlisting requires MongoDB
   transactions, i.e. a replica set and a `MongoTransactionManager` bean —
   otherwise scheduling is best-effort. Duplicate schedules are detected by a
   pre-check read since a duplicate-key error would abort the whole MongoDB
   transaction; the unique index remains the backstop for concurrent duplicates.
3. **JPA on gruelbox (opt-in):** set `vanillabp.outbox.gruelbox.enabled` to `true` and the
   JDBC default of item 1 backs off while `GruelboxPhaseTwoOutboxAutoConfiguration` takes
   over. It is there for an application which already runs gruelbox and wants to keep its
   table and its entries for now; nothing in the platform depends on gruelbox any more.
   `GruelboxPhaseTwoOutboxAutoConfiguration` sets up a
   [gruelbox transaction-outbox](https://github.com/gruelbox/transaction-outbox)
   using `SpringTransactionManager`/`SpringInstantiator`, active under the same
   conditions as the JPA `SpringDataUtil`. The `PhaseTwoCall` is flattened into
   String parameters of the scheduled `GruelboxPhaseTwoDispatch` invocation
   (gruelbox's invocation serializer only supports a whitelist of types) and
   rebuilt at dispatch time. The contract maps onto gruelbox's native
   capabilities: the idempotency key becomes the `uniqueRequestId` (unique
   constraint of `TXNO_OUTBOX`; duplicates are a no-op), "DONE instead of delete"
   is gruelbox's retention of processed entries (`vanillabp.outbox.retention` maps
   to the retention threshold). Since deduplication has to span the entries still
   waiting for their dispatch only (decision 22) and gruelbox' table has no column
   for a released key, the store reads the row of that `uniqueRequestId` before it
   schedules: a row gruelbox marked `processed` is deleted in the caller's
   transaction so the operation can be planned again, a row which is not processed
   yet makes the schedule a no-op. That is also why the derived key is bounded to
   250 characters — gruelbox refuses a longer one before any database sees it. That read,
   the replacement of a waiting entry and the question which payloads an entry still names
   all go to gruelbox' table, so an application which builds the store itself passes the
   data source and the table name; a store without them is refused where it is built,
   because it would discard the next operation of a workflow whose key is still retained
   and would let no payload be removed ever again. Recovery, retries and retention cleanup
   are done by a fixed-delay poller calling `TransactionOutbox.flush()` on a **private
   single-thread executor** — no `TaskScheduler` bean is registered or used, so an
   application's `@EnableScheduling` setup stays unaffected. The outbox table
   `TXNO_OUTBOX` is created by gruelbox's auto-DDL — set
   `vanillabp.outbox.create-schema: false` to manage the schema manually (use
   gruelbox's `DefaultPersistor.writeSchema(Writer)`, which emits its migrations as
   SQL for the configured dialect). NOTE: gruelbox's migrations always target the
   DEFAULT table, so a custom `vanillabp.outbox.jdbc.table` requires that table
   (structured like `TXNO_OUTBOX`) to be created manually. Wherever the migration is
   off, the auto-configuration verifies AT STARTUP that the table exists
   (`validateOutboxTableExists`) and ends the boot naming table and property. This
   table is the one piece of a schema handover `io.vanillabp:vanillabp-schema` does
   not cover, because the schema belongs to gruelbox, and shipping foreign DDL was
   decided against. What this store costs next to the JDBC one is in
   `migration-adapter/README.md` and on the wiki: a dispatched entry deleted to free
   its key, a re-dispatch after a hard crash which carries no attempt count, one fixed
   retry distance, one dispatching thread, and a stale adapter id named at the first
   dispatch instead of at the start. VanillaBP's own tables are checked by
   `JdbcTaskDeliveryStore#validateSchemaExists` respectively the JDBC outbox, all through
   `io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema#tableExists`. This store's beans
   reference each other BY NAME (`vanillaBpTransactionOutbox`), so additional
   user-defined gruelbox instances (e.g. a dedicated hot-process outbox) do not
   suppress it; with several transaction managers (mixed persistence)
   the JDBC/JPA one has to be named `transactionManager`.
   `GruelboxOutboxWiringTest` holds the wiring rules of this store
   (`theDefaultConfigurationCreatesTheOutboxTable`, `aCustomTableNameSwitchesTheMigrationOff`,
   `aMissingTableStopsTheStartupInsteadOfTheFirstWorkflow`,
   `theConventionallyNamedTransactionManagerWins`),
   `GruelboxOutboxSchemaHandoverTest` the handover to an application-managed schema,
   `GruelboxDeduplicationWindowTest` the released key of a dispatched entry,
   `GruelboxRefusesAStoreWithoutItsTableTest` the store which is not built without its
   table, and `EnableSchedulingRegressionTest#noVanillaBpTaskSchedulerBean` the private
   executor.
   The submitter of this outbox is a bean of its own
   (`GruelboxRedispatchAwareSubmitter`, `vanillaBpGruelboxSubmitter`), because the
   dispatcher needs the very submitter the outbox was built with. Gruelbox hands an
   entry to its submitter as soon as the scheduling transaction commits, while Spring
   Boot opens the web server before VanillaBP deployed its models. A start arriving in
   that window reaches a BPMS which does not hold the process, and an adapter reports
   that as a failure no repetition can fix, so the entry would be blocked although the
   next attempt would have worked. The submitter therefore keeps such an entry:
   gruelbox treats a submitter which does not take the work like a busy executor, the
   row stays committed and due, and the first `flush()` carries it. Building the
   dispatcher closes that gate, starting its poller opens it for good. An outbox built
   without a dispatcher keeps dispatching right after the commit, so a test or an
   application flushing the outbox itself does not wait for a gate nobody opens. What
   it costs is one poll interval for the entries of that window, once per application
   start. Held by `GruelboxHoldsEntriesBackUntilDispatchingStartedTest`.
   What gruelbox has no idea of is VanillaBP's classification of a failure, so
   `GruelboxPhaseTwoFailureListener` is registered on the outbox: it blocks an entry the
   adapter called permanent (`PhaseTwoPermanentFailure`) after the first attempt by
   writing the blocked flag through the persistor, and it gives every blocked entry an
   ERROR naming the workflow, because gruelbox' own line names the entry id only. It is
   handed the persistor and the transaction manager the outbox was built with, and it
   works because gruelbox calls a listener AFTER it committed the failed attempt, so the
   entry carries the version that write left behind. Listener beans the application
   brings are chained behind it (`TransactionOutboxListener#andThen`) - gruelbox takes
   exactly one. Held by `GruelboxBlocksAPermanentFailureTest`.

If both JPA and MongoDB are configured, JPA wins deterministically (consistent with
the `SpringDataUtil` auto-configurations). To use a different outbox (e.g. another
database or an existing outbox infrastructure), define a bean implementing
`io.vanillabp.integration.spi.PhaseTwoOutbox` — the auto-configurations
back off.

### Separating workflow module properties from application properties

Read the [Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules) to learn about reasons for having multiple workflow modules.
In this situation the `application.yaml` files should not list properties specific to one of those workflow modules
because otherwise properties of the workflow module would be part of a different (Maven/Gradle) module than the
code. Testing the encapsulated workflow module would not be possible due to missing properties.
Additionally, changing properties in `application.yaml` of one workflow module might affect other unintentional.

To overcome those problems, VanillaBP integration for Spring Boot loads additional YAML files specific to each
workflow module.

Instead of `application-xxxx.yaml` files (where `xxxx` might name active Spring boot profiles)
those specific config files are named `yyyy-xxxx.yaml`, where `yyyy` is the ID of the workflow module. Place your files
in the classpath root by using these source code folders within your workflow module:

- `src/main/resources/loan-approval.yaml`<br>
  (common properties of workflow module `loan-approval`)
- `src/main/resources/loan-approval-environment-dev.yaml`<br>
  (properties specific to Spring Boot profile `environment-dev`)

There are four places to choose from, and they are styles rather than a ranking:

- `src/main/resources/loan-approval.yaml`
- `src/main/resources/config/loan-approval.yaml`
- `src/main/resources/loan-approval/loan-approval.yaml`
- `src/main/resources/loan-approval/config/loan-approval.yaml`

Quarkus reads the same four. Because none of them ranks above another, the same file may lie in
exactly one of them: a module which ships `loan-approval.yaml` at the root and in `config` ends
the boot with a message naming both (decision 65).

To avoid name-clashes of properties a workflow module has to use a separate properties section typically named
using the workflow module ID:

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

Finally, one has to define and configure a Java class holding those values:

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

This setup shown above ensures property values are part of the workflow module. The bean of the class
`LoanApprovalProperties` can be injected into services of the workflow module to access those properties set by
Spring Boot according to the active Spring profiles.

#### These files are defaults

`loan-approval.yaml` above sets the banking system's URL to an invalid value on purpose, which only works
because the application can override it. That is the rule: a workflow module file is the weakest source in the
environment, so system properties, environment variables and every configuration file of the application beat
it, while `loan-approval-environment-dev.yaml` still beats `loan-approval.yaml`. It also means a placeholder
written in a module file (`${external-systems.banking.hostname}`) resolves against whatever the application
configured. Both directions have acceptance tests in
`integration-tests/workflowmodule-integration-tests/submodule-integration-test`, and Quarkus answers the same
way through the ordinals in `WorkflowModuleBuildStepProcessor`.
