package io.vanillabp.integration.adapter.migration.workflowtask;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The version range which a <code>&#64;WorkflowService</code> class declares for ONE of its
 * BPMN processes. The handler methods of that class serve it, as long as they name no range
 * of their own.
 * <p>
 * That ties a whole class to one generation of a model:
 *
 * <pre>
 * &#64;WorkflowService(
 *         workflowAggregateClass = LoanApproval.class,
 *         bpmnProcess = &#64;BpmnProcess(bpmnProcessId = "loan_approval", version = "&lt;10"))
 * </pre>
 *
 * A method which names a range keeps it WORD FOR WORD. The class range does not narrow it.
 * With an intersection, the attribute at a method would mean something different depending
 * on a declaration elsewhere in the file. A range which cannot be read from the annotation
 * at hand is worse than one which is repeated.
 * <p>
 * The process from which a delivery came decides which declaration applies, not the class.
 * A class declares one <code>bpmnProcess</code> and any number of
 * <code>secondaryBpmnProcesses</code>, each with a version of its own. A method may serve
 * elements of either. So this is resolved once per pair of class and BPMN process. The
 * handler methods are registered the same way.
 * <p>
 * Decision 20 in the repository's DECISIONS.md says why a method which INHERITS a range is
 * as restricted as one which names it, and what that means for a BPMS which reports no
 * version at all.
 *
 * @param versions What the class declared for that process, or
 *          {@link ServedVersions#EVERY_VERSION} where it declared nothing
 * @param declaredBy The declaration from which the ranges were read, worded for a message
 *          which a developer reads. It is <code>null</code> where there is nothing to
 *          inherit, because a message about an inherited range has to name where the range
 *          is
 */
public record InheritedVersions(
                                ServedVersions versions,
                                String declaredBy) {

  /**
   * Nothing to inherit: the class named no version for this process, or it has no
   * annotation at all (test doubles which register handler classes directly).
   */
  private static final InheritedVersions NOTHING = new InheritedVersions(ServedVersions.EVERY_VERSION, null);

  /**
   * Reads the class annotation once, before the handler methods of that class are
   * registered.
   * <p>
   * A class which says nothing about versions is the normal case. It gets the same answer as
   * a class which names <code>*</code>. There is nothing to inherit, so no message about
   * such a method mentions an origin.
   *
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param bpmnProcessId The BPMN process its handlers are being registered for
   * @return What the methods of that class inherit for that process
   */
  public static InheritedVersions declaredFor(
      final Class<?> workflowServiceClass,
      final String bpmnProcessId) {

    final var workflowService = workflowServiceClass.getAnnotation(WorkflowService.class);
    if (workflowService == null) {
      return NOTHING;
    }
    final var declaration = declarationOf(workflowService, bpmnProcessId);
    if (declaration == null) {
      return NOTHING;
    }
    final var declaredBy = "the @BpmnProcess(bpmnProcessId = \"%s\") of '%s'"
        .formatted(bpmnProcessId, workflowServiceClass.getName());
    final var versions = ServedVersions.parse(declaration.version(), declaredBy);
    // '*' on the class is what every class says which says nothing. So there is nothing to
    // inherit, and nothing to explain in a message either
    return versions.everyVersion()
        ? NOTHING
        : new InheritedVersions(versions, declaredBy);

  }

  /**
   * The declaration to which a BPMN process ID belongs. The platform integration derived the
   * ID from THIS class' declarations. So one which names no secondary process is the primary
   * one. Whether that primary spelled its ID out, or left it to the class-name convention, is
   * the platform integration's business and not this one's.
   */
  private static BpmnProcess declarationOf(
      final WorkflowService workflowService,
      final String bpmnProcessId) {

    for (final var secondary : workflowService.secondaryBpmnProcesses()) {
      if (secondary.bpmnProcessId().equals(bpmnProcessId)) {
        return secondary;
      }
    }
    final var primary = workflowService.bpmnProcess();
    return primary.bpmnProcessId().isEmpty() || primary.bpmnProcessId().equals(bpmnProcessId)
        ? primary
        : null;

  }

  /**
   * What one handler method of that class really serves. The method wins.
   * <p>
   * The two ranges are not intersected, so the class does not narrow a method which names a
   * range. Only a method which names none takes over the class range. It then carries the
   * declaration it came from.
   *
   * @param namedByTheMethod What the method's own <code>version</code> attribute parses to,
   *          or {@link ServedVersions#EVERY_VERSION} where it names none
   * @return The versions which the handler serves, naming their origin where the class
   *         supplied them
   */
  public ServedVersions effectiveFor(
      final ServedVersions namedByTheMethod) {

    if (versions.everyVersion() || !namedByTheMethod.everyVersion()) {
      return namedByTheMethod;
    }
    return versions.inheritedFrom(declaredBy);

  }

}
