package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Arrays;
import java.util.List;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The version range a <code>&#64;WorkflowService</code> class declares for ONE of its
 * BPMN processes, which the handler methods of that class serve as long as they name no
 * range of their own.
 * <p>
 * That is what binds a whole class to one generation of a model:
 *
 * <pre>
 * &#64;WorkflowService(
 *         workflowAggregateClass = LoanApproval.class,
 *         bpmnProcess = &#64;BpmnProcess(bpmnProcessId = "loan_approval", version = "&lt;10"))
 * </pre>
 *
 * A method which names a range keeps it WORD BY WORD - the class range does not narrow
 * it. An intersection would make the attribute in front of a method mean something
 * different depending on a declaration elsewhere in the file, and a range which cannot
 * be read off the annotation one is looking at is worse than one repeated.
 * <p>
 * Which declaration applies is decided by the process a delivery came from, not by the
 * class: a class declares one <code>bpmnProcess</code> plus any number of
 * <code>secondaryBpmnProcesses</code>, each with a version of its own, and a method may
 * serve elements of either. So this is resolved once per (class, BPMN process), which is
 * also how the handler methods are registered.
 * <p>
 * Why a method which INHERITS a range is as restricted as one naming it, and what that
 * means for a BPMS reporting no version at all, is decision 20 in the repository's
 * DECISIONS.md.
 */
public record InheritedVersions(
                                List<VersionRange> versions,
                                String declaredBy) {

  /**
   * Nothing to inherit: the class named no version for this process, or it is not
   * annotated at all (test doubles registering handler classes directly).
   */
  private static final InheritedVersions NOTHING = new InheritedVersions(List.of(), null);

  /**
   * What a handler method actually serves and where that came from -
   * <code>inheritedFrom</code> is <code>null</code> for a method naming its own range,
   * whose messages need no origin because the attribute is right in front of the
   * reader.
   */
  public record EffectiveVersions(
                                  List<VersionRange> versions,
                                  String inheritedFrom) {
  }

  /**
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
    final var versions = Arrays
        .stream(declaration.version())
        .map(version -> VersionRange.parse(version, declaredBy))
        .toList();
    // '*' on the class is what every class says which says nothing, so there is
    // nothing to inherit and nothing to explain in a message either
    return versions.stream().allMatch(VersionRange::everyVersion)
        ? NOTHING
        : new InheritedVersions(versions, declaredBy);

  }

  /**
   * The declaration a BPMN process ID belongs to. The ID was derived from THIS class'
   * declarations by the platform integration, so one which names no secondary process
   * is the primary one - whether that primary spelled its ID out or left it to the
   * class-name convention is the platform integration's business, not this one's.
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
   * @param namedByTheMethod What the method's own <code>version</code> attribute parses
   *          to - a single <code>*</code> where it names none
   * @return The range the handler serves, and its origin where the class supplied it
   */
  public EffectiveVersions effectiveFor(
      final List<VersionRange> namedByTheMethod) {

    if (versions.isEmpty() || !namedByTheMethod.stream().allMatch(VersionRange::everyVersion)) {
      return new EffectiveVersions(namedByTheMethod, null);
    }
    return new EffectiveVersions(versions, declaredBy);

  }

}
