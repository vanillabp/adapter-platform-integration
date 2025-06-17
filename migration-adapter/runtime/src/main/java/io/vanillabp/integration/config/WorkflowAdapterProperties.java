package io.vanillabp.integration.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkflowAdapterProperties extends AdapterProperties {

  String bpmnProcessId;

  WorkflowModuleAdapterProperties workflowModule;

}
