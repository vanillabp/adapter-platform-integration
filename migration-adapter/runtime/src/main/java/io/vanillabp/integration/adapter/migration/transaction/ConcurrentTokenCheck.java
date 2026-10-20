package io.vanillabp.integration.adapter.migration.transaction;

import java.util.Collection;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * The startup hint about two writers of one workflow aggregate. A BPMN process which can
 * have more than one token at a time has branches which write the same workflow aggregate.
 * Without a version attribute, the branch which commits second writes back what it read
 * when it started. Nobody notices, because an ORM saves the whole record.
 * <p>
 * Reading a model is the adapter's job, because only the adapter can parse its BPMN
 * dialect. Deciding what the finding means is the core's job. The adapter reports the
 * elements which can put a second token into a running workflow, and the core knows the
 * aggregate class.
 * <p>
 * Which models are read is the second half of the question. The adapter reports what it
 * finds while it wires the model of THIS boot. The core also asks it about the versions
 * which the BPMS still holds with running workflows
 * ({@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog#concurrentTokenElementsOfVersion}).
 * An older version may have a parallel gateway which the new model dropped. That gateway
 * still forks every workflow started on the older version. Compensation is the same finding
 * in a different shape and arrives through {@link #reportCompensation}. A throw event which
 * starts several handlers leaves the workflow with one token per handler. The warning names
 * the models it comes from next to its text. So the box folds everything about one
 * aggregate into one entry, however many models lead to it.
 * <p>
 * A persistence which notices the collision silences the hint. It throws an exception
 * instead of overwriting, and {@link AggregateWrite} reports that. The persistence itself
 * answers whether it notices. An adapter which cannot read its models (the
 * Process-Engine-API) silences the hint as well. It reports nothing, and nothing is guessed
 * from that.
 * <p>
 * Decision 14 in the repository's DECISIONS.md says why a model which can produce a second
 * token only gets a warning, and only where the aggregate has no version attribute.
 */
public class ConcurrentTokenCheck {

  /**
   * Where the hint goes.
   */
  private final StartupFindings findings;

  /**
   * Built by the registry which wires the workflow tasks, once per application.
   *
   * @param findings Where the hint is reported
   */
  public ConcurrentTokenCheck(
      final StartupFindings findings) {

    this.findings = findings;

  }

  /**
   * Reports the elements of a BPMN process which can put a second token into a running
   * workflow, as an adapter found them. It warns if the workflow aggregate of that process
   * has no version attribute.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param elementIds The BPMN element IDs producing the second token
   */
  public void reportConcurrentTokenElements(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final Collection<String> elementIds) {

    if ((elementIds == null) || elementIds.isEmpty()) {
      return;
    }
    report(
        workflowAggregateClass,
        aggregateNoticesASecondWriter,
        "process '%s' of workflow module '%s' (%s)"
            .formatted(bpmnProcessId, workflowModuleId, describe(elementIds)));

  }

  /**
   * The same finding in the shape of compensation. A throw event which starts more than one
   * handler leaves the workflow with one token per handler, and each of them writes the
   * workflow aggregate.
   * <p>
   * It is reported on its own, because here the developer needs the shape. A flat list of
   * elements would name the throw event and the handlers side by side. The reader would have
   * to work out which starts which. But the point is that ONE event in the model turns into
   * several branches at once.
   * <p>
   * The text is the same as in every other form of this finding. So it folds into the same
   * entry as a parallel gateway of the same process. The thing to change is the aggregate,
   * and that is one thing however many places in the model lead to it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param compensations The compensation throw events starting several handlers, with
   *          those handlers
   */
  public void reportCompensation(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final Collection<CompensationSpec> compensations) {

    if ((compensations == null) || compensations.isEmpty()) {
      return;
    }
    final var startingSeveralHandlers = compensations
        .stream()
        .filter(compensation -> compensation.handlerIds() != null)
        .filter(compensation -> compensation.handlerIds().size() > 1)
        .toList();
    if (startingSeveralHandlers.isEmpty()) {
      return;
    }
    report(
        workflowAggregateClass,
        aggregateNoticesASecondWriter,
        "process '%s' of workflow module '%s' (%s)"
            .formatted(
                bpmnProcessId,
                workflowModuleId,
                startingSeveralHandlers
                    .stream()
                    .map(compensation -> "the compensation throw event '%s' starts the handlers %s"
                        .formatted(compensation.throwEventId(), named(compensation.handlerIds())))
                    .collect(Collectors.joining("; "))));

  }

  /**
   * The same finding for the versions which the BPMS STILL HOLDS with running workflows. A
   * parallel gateway which the model of this boot dropped still forks every workflow started
   * before. And those are the workflows which run longest.
   * <p>
   * There is still one warning per BPMN process, whichever model had the elements. The
   * message is about an aggregate which cannot survive two writers. Saying it once for every
   * version an application ever deployed would bury it. For the same reason the deployed
   * model speaks first where both would speak, because the developer can still change it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param elementIdsByVersion The elements producing a second token, per version identifier
   *          the BPMS reported
   */
  public void reportConcurrentTokenElementsOfHeldVersions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final java.util.Map<String, Collection<String>> elementIdsByVersion) {

    if ((elementIdsByVersion == null) || elementIdsByVersion.isEmpty()) {
      return;
    }
    final var carryingVersions = elementIdsByVersion
        .entrySet()
        .stream()
        .filter(version -> (version.getValue() != null) && !version.getValue().isEmpty())
        .toList();
    if (carryingVersions.isEmpty()) {
      return;
    }
    final var elementIds = carryingVersions
        .stream()
        .flatMap(version -> version.getValue().stream())
        .toList();
    report(
        workflowAggregateClass,
        aggregateNoticesASecondWriter,
        "version(s) %s of process '%s' of workflow module '%s', which the BPMS still holds (%s)"
            .formatted(
                carryingVersions
                    .stream()
                    .map(java.util.Map.Entry::getKey)
                    .collect(Collectors.joining("', '", "'", "'")),
                bpmnProcessId,
                workflowModuleId,
                describe(elementIds)));

  }

  /**
   * The warning itself. The scope says WHICH model can have two tokens: the model of this
   * boot, or the versions the BPMS still holds.
   * <p>
   * The scope carries what differs, and the message carries the aggregate. So if both the
   * deployed model and the held versions of a process fork, the box shows ONE entry which
   * names both. The message is about an aggregate which cannot survive two writers. That is
   * one thing to change, however many models lead to it.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @param aggregateNoticesASecondWriter What the aggregate's persistence answers about
   *          noticing a concurrent change
   * @param whatCanHoldTwoTokens Which model it was drawn from, as the entry names it
   */
  private void report(
      final Class<?> workflowAggregateClass,
      final boolean aggregateNoticesASecondWriter,
      final String whatCanHoldTwoTokens) {

    if (workflowAggregateClass == null) {
      return;
    }
    if (aggregateNoticesASecondWriter) {
      return;
    }

    findings
        .warn(
            StartupTopic.CODE,
            whatCanHoldTwoTokens,
            """
                A BPMN process can hold more than one token at a time, but its workflow aggregate \
                '%s' has no version attribute (@Version): two branches load the aggregate, change \
                different things and save it - and since the persistence layer writes the whole \
                record, whatever the branch committing first changed is lost without any error. \
                Ways out: one entity per phase of the workflow, @DynamicUpdate where the branches \
                write different attributes, a version attribute plus a retry in the transaction \
                your application opens, or an additive relation instead of a mutated attribute. \
                The wiki page 'Workflow aggregates' compares them. A version attribute turns the \
                collision into an exception VanillaBP reports and the BPMS retries, which is why \
                this message is about its absence. On MongoDB inside a transaction, two branches \
                which both change the aggregate get a write conflict instead, which VanillaBP \
                reports the same way. With the persistence of the platform, a branch which \
                changes nothing does not write the aggregate at all."""
                .formatted(workflowAggregateClass.getName()));

  }

  /**
   * The element ids as the warning names them: all of them, in the order they were
   * reported. If a handler were missing from the list, the reader would go looking for it.
   */
  private static String named(
      final Collection<String> elementIds) {

    return elementIds
        .stream()
        .collect(Collectors.joining("', '", "'", "'"));

  }

  /**
   * The element ids as the warning names them, free of duplicates and in a stable order.
   */
  private static String describe(
      final Collection<String> elementIds) {

    return elementIds
        .stream()
        .distinct()
        .sorted()
        .collect(Collectors.joining("', '", "e.g. '", "'"));

  }

}
