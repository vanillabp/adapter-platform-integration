![Header](../../readme/vanillabp-headline.png)

# Spring Boot integration runtime

This module brings the VanillaBP SPI to life in Spring Boot and bridges to the
platform-neutral [migration adapter](../../migration-adapter). It is wired up via
Spring Boot auto-configuration (`META-INF/spring/...AutoConfiguration.imports`):

1. `WorkflowModuleAutoConfiguration` — detects workflow modules and assigns
   `@WorkflowService` beans to them.
2. `JpaSpringDataUtilConfiguration` — JPA-based persistence support (only if JPA is on
   the classpath and no custom `SpringDataUtil` bean exists).
3. `SpringBootMigrationAdapterAutoConfiguration` — transforms Spring properties into
   the core `MigrationAdapterProperties`, collects adapters and registers one
   `ProcessService<A>` bean per workflow aggregate.
4. `DeploymentAutoConfiguration` — deploys BPMN resources on startup and starts
   workflow processing on `ApplicationReadyEvent`.

Additionally, `WorkflowModulePropertiesEnvironmentPostProcessor` (registered in
`spring.factories`) merges workflow-module-specific config files into the Spring
`Environment` before regular config resources, so module properties take precedence
over `application.*`.

### Workflow module detection

Workflow modules are declared by a `META-INF/workflow-module` marker file whose
content is the workflow module ID. `@WorkflowService` classes are matched to a module
by comparing the code-source URI of the class with the URI the marker file was loaded
from. Services not matching any marker file belong to the *global* module (the whole
application acting as a single workflow module) — only one global marker is allowed.

### ProcessService beans

`ProcessService<A>` beans are registered as `BeanDefinition`s (not instances) via a
`BeanDefinitionRegistryPostProcessor`, using `ResolvableType` with the aggregate class
as generic parameter, so generic autowiring (`ProcessService<Ride>`) works. Registering
definitions instead of beans avoids circular dependencies between the
`@WorkflowService` bean and its `ProcessService`. Each bean wraps a
`MigrationProcessService` of the migration adapter which implements the actual
behavior.

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
   - `JpaSpringDataUtil` (auto-configured if JPA is present): resolves the Spring Data
     repository of the aggregate class, determines IDs via `PersistenceUnitUtil` and
     unproxies Hibernate proxies.
   - `MongoDbSpringDataUtil`: must be activated explicitly via
     `@Import(MongoDbSpringDataUtilConfiguration.class)`.

So `SpringDataUtil` is the Spring-Data-generic mechanism used *behind* the
`AggregatePersistenceAware` abstraction, while a custom `AggregatePersistenceAware`
bean is the extension point for any other persistence technology.

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
