package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Properties passed by platform integration implementations.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class WorkflowAdapterProperties extends AdaptersConfigurationProperties {

  String bpmnProcessId;

  WorkflowModuleAdapterProperties workflowModule;

}
