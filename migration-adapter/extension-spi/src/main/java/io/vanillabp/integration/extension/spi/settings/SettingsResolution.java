package io.vanillabp.integration.extension.spi.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The walk over the levels a plug-in may be configured at, written once for every plug-in
 * (see decision 53 in the repository's DECISIONS.md).
 * <p>
 * There are four levels, and each of them has two positions: what the level says in
 * general, and what it says for one adapter. That makes eight positions, from the least
 * specific to the most specific one:
 *
 * <pre>
 * 1. application                    vanillabp.&lt;section&gt;
 * 2. application, one adapter       vanillabp.adapters.&lt;adapter&gt;.&lt;section&gt;
 * 3. workflow module                vanillabp.workflow-modules.&lt;module&gt;.&lt;section&gt;
 * 4. workflow module, one adapter   vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;adapter&gt;.&lt;section&gt;
 * 5. workflow                       ...workflows.&lt;workflow&gt;.&lt;section&gt;
 * 6. workflow, one adapter          ...workflows.&lt;workflow&gt;.adapters.&lt;adapter&gt;.&lt;section&gt;
 * 7. task                           ...workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.&lt;section&gt;
 * 8. task, one adapter              ...workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.adapters.&lt;adapter&gt;.&lt;section&gt;
 * </pre>
 *
 * The most specific position which says something about a setting wins, and what an
 * adapter is told beats what the same level says in general. Which is the same rule an
 * adapter setting follows (decision 7), and the reason a plug-in does not write this walk
 * a second time (decision 48).
 * <p>
 * What the sections MEAN is the plug-in's business. This class only knows their order.
 */
public final class SettingsResolution {

  private SettingsResolution() {
  }

  /**
   * The positions of the configuration which apply to the given scope, the least specific
   * one first. A position nothing is configured at is left out, so the list is shorter
   * than eight entries wherever the application wrote less, and it is shorter than the
   * levels asked for wherever the scope names no workflow module, no workflow or no task.
   *
   * @param <S> What a section is for the plug-in asking
   * @param application The most general level of the plug-in's configuration tree
   * @param workflowModuleId The workflow module, or <code>null</code> to stop at the
   *          application
   * @param bpmnProcessId The BPMN process, or <code>null</code> to stop at the workflow
   *          module
   * @param taskId The task (task definition), or <code>null</code> to stop at the
   *          workflow
   * @param adapterId The adapter, or <code>null</code> to ask about the general sections
   *          alone
   * @return The sections which say something, least specific first, never
   *         <code>null</code>
   */
  public static <S> List<S> positions(
      final SettingsLevel<S> application,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId) {

    final var found = new ArrayList<S>(8);
    final var idsOfTheLevelsBelow = Arrays.asList(workflowModuleId, bpmnProcessId, taskId);

    var level = application;
    for (var depth = 0; level != null; ++depth) {
      addIfConfigured(found, level.settings());
      if (adapterId != null) {
        addIfConfigured(found, level.settingsOfAdapter(adapterId));
      }
      final var idBelow = depth < idsOfTheLevelsBelow.size()
          ? idsOfTheLevelsBelow.get(depth)
          : null;
      level = idBelow == null
          ? null
          : level.levelBelow(idBelow);
    }

    return List.copyOf(found);

  }

  /**
   * The value the most specific position writes.
   *
   * @param <S> What a section is for the plug-in asking
   * @param <T> The type of the value read
   * @param positionsFromLeastSpecific The positions, as {@link #positions} answers them
   * @param valueExtractor Reads the value out of one section; has to answer
   *          <code>null</code> where that section says nothing about it
   * @return The value of the most specific position which writes it, or <code>null</code>
   *         where no position does
   */
  public static <S, T> T resolve(
      final List<S> positionsFromLeastSpecific,
      final Function<S, T> valueExtractor) {

    for (var position = positionsFromLeastSpecific.size() - 1; position >= 0; --position) {
      final var value = valueExtractor.apply(positionsFromLeastSpecific.get(position));
      if (value != null) {
        return value;
      }
    }
    return null;

  }

  /**
   * The value the most specific position of the given scope writes -
   * {@link #positions} and {@link #resolve(List, Function)} in one call.
   *
   * @param <S> What a section is for the plug-in asking
   * @param <T> The type of the value read
   * @param application The most general level of the plug-in's configuration tree
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task (task definition), or <code>null</code>
   * @param adapterId The adapter, or <code>null</code>
   * @param valueExtractor Reads the value out of one section
   * @return The value of the most specific position which writes it, or <code>null</code>
   */
  public static <S, T> T resolve(
      final SettingsLevel<S> application,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId,
      final Function<S, T> valueExtractor) {

    return resolve(
        positions(application, workflowModuleId, bpmnProcessId, taskId, adapterId),
        valueExtractor);

  }

  private static <S> void addIfConfigured(
      final List<S> found,
      final S section) {

    if (section != null) {
      found.add(section);
    }

  }

}
