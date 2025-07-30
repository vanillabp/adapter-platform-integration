![Draft](../readme/vanillabp-headline.png)

# Quarkus integration

To learn about how to bring VanillaBP into your projects checkout the [Wiki](../../wiki) for documentation. Chapters of
the Wiki documentation will link to the respective chapter below.

## Workflow Modules

Each workflow module has its own ID. This identifier is used to define scopes of visibility and to avoid name-clashes
(see [Wiki](../../wiki/Workflow-modules)). BPMS configuration specific to a workflow module is loaded based on this ID.

In addition, to underline separation of workflow modules, each workflow module may have
[its own configuration files](#separating-workflow-module-properties-from-application-properties) next the
main files `application.yaml` or `application.properties`, to load Spring Boot properties from.

### Define a workflow module

...

#### Standalone applications

...

#### Multiple workflow modules in one application

...

### Separating workflow module properties from application properties

...
