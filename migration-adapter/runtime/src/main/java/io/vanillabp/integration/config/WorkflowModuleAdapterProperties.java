package io.vanillabp.integration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class WorkflowModuleAdapterProperties extends AdapterProperties {

  String workflowModuleId;

  @Builder.Default
  private Map<String, AdapterConfiguration> adapters = Map.of();

  @Builder.Default
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
