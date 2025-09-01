package io.vanillabp.integration.workflowmodule;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A bean holding all workflow modules found.
 */
@RequiredArgsConstructor
@Getter
public class WorkflowModules {

  /**
   * The workflow modules
   */
  private final List<WorkflowModule> workflowModules;

}
