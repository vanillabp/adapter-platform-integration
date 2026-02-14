![Header](../../readme/vanillabp-headline.png)

### SpringDataUtil versus AggregatePersistenceSupport

TODO

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
