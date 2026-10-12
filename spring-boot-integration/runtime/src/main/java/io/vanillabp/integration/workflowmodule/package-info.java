/**
 * What a workflow module is on Spring Boot, and where its configuration comes from.
 * <p>
 * A workflow module is a group of BPMN processes and the workflow services which serve
 * them. It is shipped as one artifact and named by a marker file
 * (<code>META-INF/workflow-module</code>). The content of that file is the module's id.
 * At the BPMS boundary, the id keeps the identifiers of one module apart from those of
 * another (see decision 9 in the repository's DECISIONS.md). It is also the middle level
 * of the configuration, between a single workflow and the adapter.
 * <p>
 * This package holds the Spring Boot half of that. The marker files are read twice, on
 * purpose, at two different moments:
 * <ul>
 * <li>{@link io.vanillabp.integration.workflowmodule.WorkflowModulePropertiesEnvironmentPostProcessor}
 * reads them before the application context exists, because the module's own
 * configuration files have to be in the environment before anything is bound;</li>
 * <li>{@link io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration}
 * reads them again into the {@link io.vanillabp.integration.workflowmodule.WorkflowModules}
 * bean. The rest of the integration asks that bean.</li>
 * </ul>
 * A module's configuration files supply defaults, and the application always outranks
 * them. So a module can be copied into an application without taking anything over from
 * it.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: a parameter is non-null
 * unless it says otherwise.
 */
@NullMarked // needed to suppress warning "Not annotated parameter overrides @NullMarked parameter"
package io.vanillabp.integration.workflowmodule;

import org.jspecify.annotations.NullMarked;
