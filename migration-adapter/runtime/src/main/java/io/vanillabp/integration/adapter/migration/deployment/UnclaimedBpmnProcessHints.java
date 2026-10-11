package io.vanillabp.integration.adapter.migration.deployment;

import java.util.Collection;
import java.util.List;

/**
 * What a platform integration can add when the BPMN processes of a workflow module are
 * refused because no <code>&#64;WorkflowService</code> class claims them. It is one
 * sentence per finding. It is written into the refusal itself, not as a warning of its own.
 * <p>
 * It exists because the answer depends on the platform, while the report does not. On
 * Spring Boot a workflow service is found because it is a bean (decision 21 in the
 * repository's DECISIONS.md). A class with the annotation which nobody made a bean is not
 * in the bean definitions at all. Nothing can tell it from a class which another profile
 * brings. So the boot would end because of the process it declares and not say why.
 * Looking for such a class means reading class resources. That is Spring's work and must
 * not enter the core. On Quarkus the same case fails the BUILD, because the build knows its
 * beans. So that platform adds nothing here.
 * <p>
 * It is asked ONLY where a process is really being reported. That keeps a healthy boot free
 * of the cost: no unclaimed process, no report, no question.
 * <p>
 * An implementation neither throws nor decides anything. It says what it found, and the
 * deployment writes it next to the processes it reports already.
 */
@FunctionalInterface
public interface UnclaimedBpmnProcessHints {

  /**
   * Asks the platform what it can say about those processes, while the report is written.
   * <p>
   * The answer is added to the report, so a sentence reads as part of it and not on its own.
   * Finding nothing is the normal case, and it gets no word.
   *
   * @param workflowModuleId The workflow module being reported
   * @param bpmnProcessIds The BPMN process ids of that module which nothing claims
   * @return One sentence per finding, in the order they should be read. Empty where there is
   *         nothing to add
   */
  List<String> whatElseIsWorthSaying(
      String workflowModuleId,
      Collection<String> bpmnProcessIds);

}
