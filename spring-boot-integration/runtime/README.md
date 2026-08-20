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
   `ProcessService<A>` bean per workflow aggregate (via the imported
   `ProcessServiceBeanRegistrar`).
5. `DeploymentAutoConfiguration` — deploys BPMN resources on `SmartLifecycle` start
   and starts workflow processing on `ApplicationReadyEvent`.

Additionally, `WorkflowModulePropertiesEnvironmentPostProcessor` (registered in
`spring.factories`) merges workflow-module-specific config files into the Spring
`Environment` before regular config resources, so module properties take precedence
over `application.*`.

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

### ProcessService beans

`ProcessService<A>` beans are registered by `ProcessServiceBeanRegistrar`, a Spring
Framework `BeanRegistrar` imported by `SpringBootMigrationAdapterAutoConfiguration`.
At registration time only the classpath is scanned for `@WorkflowService` classes —
no beans are touched. Each bean is registered

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

Adapters of remote BPMS report `needsTwoPhaseCommitForStartingWorkflows()` and
require a transaction outbox (`PhaseTwoOutbox` SPI of the
[migration adapter](../../migration-adapter)) which schedules phase two of starting a
workflow within the local transaction and dispatches it reliably after the commit
(also after a crash/restart, retrying with a backoff). Dispatched calls are routed
through the core-owned `PhaseTwoRouter` (see the
[migration adapter's README](../../migration-adapter/README.md) for the chain and
the outbox contract — idempotency key, DONE instead of delete, retention). Each
`ProcessServiceSpringBean` registers itself with the router at bean creation; the
serialized aggregate ID is converted back to the aggregate's ID type by the CORE
(`MigrationProcessService.convertAggregateId`, based on
`AggregatePersistenceAware.getAggregateIdType()` - Spring Data implementations
answer authoritatively, a `null` ID type means the custom persistence layer owns
the serialized form and the String is passed through unchanged).

The outbox is selected **per workflow aggregate** (story 26i): the most specific
`PhaseTwoOutboxAware` bean wins; without one, the platform default matching the
persistence technology managing the aggregate is used (detected from the
aggregate's Spring Data repository type - `SpringPhaseTwoOutboxResolver`). Both
defaults may be active in the SAME application (mixed persistence), each entry
riding its aggregate's own transaction. Resolution happens AT STARTUP
(`SmartInitializingSingleton` in the auto-configuration) for every process service
whose first-priority adapter needs a two-phase commit - a missing outbox fails the
boot naming the remedies. Every outbox instance needs its OWN store: two
dispatchers polling the same store would compete and double-dispatch (dedicated
stores are configured via `vanillabp.outbox.jdbc.table` /
`vanillabp.outbox.mongo.collection`, or set up as additional user-defined beans -
see the `outbox-mixed-integration-test` for the recipe).

This module provides two
default implementations, both configured by the `vanillabp.outbox.*` properties
(`poll-interval`, `attempt-frequency`, `block-after-attempts`, `create-schema`,
`retention`, plus per-default `jdbc.*`/`mongo.*` sections with `enabled` flags and
store names). The defaults coexist with user-defined `PhaseTwoOutbox` beans -
disable an unwanted default via its `enabled` flag:

1. **JPA (gruelbox-based):** `GruelboxPhaseTwoOutboxAutoConfiguration` sets up a
   [gruelbox transaction-outbox](https://github.com/gruelbox/transaction-outbox)
   using `SpringTransactionManager`/`SpringInstantiator`, active under the same
   conditions as the JPA `SpringDataUtil`. The `PhaseTwoCall` is flattened into
   String parameters of the scheduled `GruelboxPhaseTwoDispatch` invocation
   (gruelbox's invocation serializer only supports a whitelist of types) and
   rebuilt at dispatch time. The contract maps onto gruelbox's native
   capabilities: the idempotency key becomes the `uniqueRequestId` (unique
   constraint of `TXNO_OUTBOX`; duplicates are a no-op), "DONE instead of delete"
   is gruelbox's retention of processed entries (`vanillabp.outbox.retention` maps
   to the retention threshold). Recovery, retries and retention cleanup are done by
   a fixed-delay poller calling `TransactionOutbox.flush()` on a **private
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
   not cover, because the schema belongs to gruelbox (story 95, decided against
   shipping foreign DDL and against replacing gruelbox for now); VanillaBP's own two
   tables are checked by `JdbcTaskDeliveryStore#validateSchemaExists` respectively the
   Quarkus dispatcher, all three through
   `io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema#tableExists`. The default's beans
   reference each other BY NAME (`vanillaBpTransactionOutbox`), so additional
   user-defined gruelbox instances (e.g. a dedicated hot-process outbox) do not
   suppress the default; with several transaction managers (mixed persistence)
   the JDBC/JPA one has to be named `transactionManager`.
2. **MongoDB (own implementation, gruelbox is JDBC-only):** `MongoPhaseTwoOutbox`
   writes entries into the collection `vanillabp-phase-two-outbox` via
   `MongoTemplate` within the current transaction, persisting all `PhaseTwoCall`
   fields plus the idempotency key (sparse unique index, created automatically
   unless `create-schema` is disabled — then create it manually).
   `MongoPhaseTwoOutboxDispatcher` claims due OPEN entries atomically
   (find-and-modify with attempts/backoff), marks them DONE after successful
   dispatch and deletes DONE entries once the retention passed; repeatedly failing
   entries are marked BLOCKED. It also runs on a private single-thread executor
   (no `TaskScheduler`). **Note:** transactional enlisting requires MongoDB
   transactions, i.e. a replica set and a `MongoTransactionManager` bean —
   otherwise scheduling is best-effort. Duplicate schedules are detected by a
   pre-check read since a duplicate-key error would abort the whole MongoDB
   transaction; the unique index remains the backstop for concurrent duplicates.

If both JPA and MongoDB are configured, JPA wins deterministically (consistent with
the `SpringDataUtil` auto-configurations). To use a different outbox (e.g. another
database or an existing outbox infrastructure), define a bean implementing
`io.vanillabp.integration.spi.PhaseTwoOutbox` — both auto-configurations
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
in the classpath root or folder `config` by using these source code folders within your workflow module:

- `src/main/resources/loan-approval.yaml`<br>
  (common properties of workflow module `loan-approval`)
- `src/main/resources/loan-approval-environment-dev.yaml`<br>
  (properties specific to Spring Boot profile `environment-dev`)

Alternative using the `config` sub-folder:
- `src/main/resources/config/loan-approval.yaml`
- `src/main/resources/config/loan-approval-environment-dev.yaml`

To avoid name-clashes of properties a workflow module has to use a separate properties section typically named
using the workflow module ID:

- `src/main/resources/config/loan-approval.yaml`:<br>

  ```yaml
  loan-approval: # all properties of workflow module "loan approval"
    max-loan-amount: 10000
    banking-system:
      url: to-be-defined-for-each-environment # invalid URL to ensure value is overwritten for each target environment
      read-timeout: 30000 # property used by banking-system client
  ```
- `src/main/resources/config/loan-approval-environment-dev.yaml`:<br>

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
