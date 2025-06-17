package io.vanillabp.integration.config;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkflowModuleAdapterProperties extends AdapterProperties {

  String workflowModuleId;

  MigrationAdapterProperties defaultProperties;

  private Map<String, AdapterConfiguration> adapters = Map.of();

  private Map<String, WorkflowAdapterProperties> workflows = Map.of();

  public void setWorkflows(
      final Map<String, WorkflowAdapterProperties> workflows) {

    this.workflows = workflows;
    workflows.forEach(
        (
            bpmnProcessId,
            properties) -> {
          properties.bpmnProcessId = bpmnProcessId;
          properties.workflowModule = this;
        });

  }

}
