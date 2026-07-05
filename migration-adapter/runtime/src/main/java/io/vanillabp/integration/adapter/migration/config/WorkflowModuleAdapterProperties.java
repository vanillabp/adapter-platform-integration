package io.vanillabp.integration.adapter.migration.config;

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
public class WorkflowModuleAdapterProperties extends AdaptersConfigurationProperties {

  String workflowModuleId;

  @Builder.Default
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * The workflows of the workflow module. The key is the BPMN process ID.
   * <p>
   * <i>Hint:</i> Back-references (BPMN process ID, workflow module) are linked by
   * {@link MigrationAdapterProperties#validateAndLink()}.
   */
  @Builder.Default
  private Map<String, WorkflowAdapterProperties> workflows = Map.of();

}
