![Draft](../readme/vanillabp-headline.png)

# Spring Boot integration

To learn about how to bring VanillaBP into your projects checkout the [Wiki](../../wiki) for documentation. Chapters of
the Wiki documentation will link to the respective chapter below.

## Workflow Modules

Each workflow module has its own ID. This identifier is used to define scopes of visibility and to avoid name-clashes
(see [Wiki](../../wiki/Workflow-modules)). BPMS configuration specific to a workflow module is loaded based on this ID.

In addition, to underline separation of workflow modules, each workflow module may have
[its own configuration files](#separating-workflow-module-properties-from-application-properties) next the
main files `application.yaml` or `application.properties`, to load Spring Boot properties from.

### Define a workflow module

Find out how you can define your workflow modules and their IDs depending on the situation:

#### Standalone applications

In simple situations one workflow module is one separate Spring Boot application which is called a standalone
workflow module. To create a new standalone workflow module application checkout the
[VanillaBP blueprints](https://www.vanillabp.io/blueprints) available.

If no [dedicated workflow module](#multiple-workflow-modules-in-one-application) is defined, the application's name
is used as the workflow module ID which can be set using Spring Boot's properties:

*application.yaml:*

```
spring:
  application:
    name: loan-approval
```

#### Multiple workflow modules in one application

In this scenario each workflow module (Maven/Gradle module) has to identify itself as a workflow module. This is
done by providing a `WorkflowModuleProperties` bean:

```java
@Configuration
public class LoanApprovalConfiguration {

  public static final String WORKFLOW_MODULE_ID = "loan-approval";

  @Bean
  public static WorkflowModuleProperties loanApprovalWorkflowModuleProperties() {
    return new WorkflowModuleProperties(RideProperties.class, WORKFLOW_MODULE_ID);
  }
  
}
```

*Important:* The `WorkflowModuleProperties` bean has to be defined as `static` in order to be available
before Spring Boot property files are read during booting the Spring Boot application. This is necessary to support
[separating workflow module properties from application properties](#separating-workflow-module-properties-from-application-properties).

### Separating workflow module properties from application properties

Read the [Wiki](../../wiki/Workflow-modules) to learn about reasons for having multiple workflow modules.
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
