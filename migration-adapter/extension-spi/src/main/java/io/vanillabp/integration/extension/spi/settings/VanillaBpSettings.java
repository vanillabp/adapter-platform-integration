package io.vanillabp.integration.extension.spi.settings;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an extension reads from VanillaBP's own configuration, after the platform has bound
 * and checked it. Both platforms provide one bean of this type. An extension injects it and
 * needs no class of the core for this.
 * <p>
 * It is a view for reading. The platform binds the configuration, fills in what a convention
 * derives and checks it at startup. This interface has no part in that. So it answers the
 * same on both platforms, and an extension cannot change what the core sees.
 * <p>
 * It holds two kinds of answers. The first kind is about the application: which adapters are
 * configured, of which type, in which order, and which workflow modules exist. An extension
 * which registers something per adapter id asks {@link #adapterIdsOfType(String)} (decision
 * 39 in the repository's DECISIONS.md). The second kind is the extension's own section of the
 * configuration, below <code>vanillabp.extensions.&lt;extension&gt;</code>. It is resolved over
 * the eight positions of decision 7 in the repository's DECISIONS.md. An extension which binds
 * a tree of its own under its own name walks it with {@link SettingsResolution} instead.
 */
public interface VanillaBpSettings {

  /**
   * The root of every property which VanillaBP reads: <code>vanillabp</code>. An extension
   * which binds a tree of its own below the same root uses it, so that its keys are spelled
   * the way the core spells its own.
   */
  String PREFIX = "vanillabp";

  /**
   * Every configured adapter id with the type of its adapter. An adapter section which
   * names no type has its id as its type.
   *
   * @return The adapter ids (key) and their types (value)
   */
  Map<String, String> adapterTypes();

  /**
   * The adapter ids which one adapter TYPE serves, in a stable (sorted) order. A platform
   * needs this to register one set of beans per configured adapter id. An extension which
   * bridges to a BPMS needs it to register one bridge per engine of that BPMS. Several ids of
   * one type are the migration case.
   * <p>
   * Three things decide it, and leaving one out loses adapters silently:
   * <ol>
   * <li>every adapter section whose type (or default type) is this one, see
   * {@link #adapterTypes()};</li>
   * <li>every id in <code>prioritized-adapters</code> which IS this type and has no section,
   * or a section which does not contradict it. Convention over configuration says that such
   * an id needs no section of its own;</li>
   * <li>nothing configured at all: the single adapter dependency IS the configuration, and
   * the id which the core derives is the type.</li>
   * </ol>
   * The second rule is the one which cannot be read from the sections an extension can see.
   * So an extension asks here and does not derive the ids itself. Decision 39 in the
   * repository's DECISIONS.md says why this lives in one place.
   *
   * @param adapterType The adapter's type, for example <code>camunda7</code>
   * @return The adapter ids of that type, sorted, possibly none
   */
  List<String> adapterIdsOfType(
      String adapterType);

  /**
   * The application's <code>vanillabp.prioritized-adapters</code>: which adapters serve the
   * workflows, and in which order they are asked where several of them could serve. A
   * workflow module or a workflow may write an order of its own. This is the one of the
   * application.
   *
   * @return The adapter ids, in the order written, possibly none
   */
  List<String> getPrioritizedAdapters();

  /**
   * The ids of the workflow modules of this application, in the order the platform bound
   * them. A module which the platform found without a section of its own is part of it as
   * well.
   *
   * @return The workflow module ids, read-only
   */
  Set<String> workflowModuleIds();

  /**
   * The settings of an extension as they apply to a workflow module. That is what
   * {@link #extensionProperties(String, String, String, String, String)} answers for the
   * module alone, without a workflow, a task or an adapter.
   *
   * @param workflowModuleId The workflow module, or <code>null</code> for the global
   *          settings alone
   * @param extension The extension's id
   * @return The settings, never <code>null</code>
   */
  Map<String, String> extensionProperties(
      String workflowModuleId,
      String extension);

  /**
   * The settings of an extension as they apply to one task of one workflow of one workflow
   * module, read without naming an adapter.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param extension The extension's id
   * @return The settings, never <code>null</code>
   */
  Map<String, String> extensionProperties(
      String workflowModuleId,
      String bpmnProcessId,
      String taskId,
      String extension);

  /**
   * The settings of an extension as they apply to one task of one workflow of one workflow
   * module on one adapter: the eight positions which {@link SettingsResolution} walks,
   * merged KEY BY KEY. So a workflow may change one value without repeating the rest. That is
   * the same rule and the same levels as for an adapter setting (see decision 7 in the
   * repository's DECISIONS.md).
   *
   * <pre>
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;  (most specific)
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.workflow-modules.&lt;module&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;.&lt;key&gt;
   * vanillabp.extensions.&lt;extension&gt;.&lt;key&gt;                                                                                 (least specific)
   * </pre>
   *
   * A level whose id is <code>null</code>, or which is not configured, is skipped. So the same
   * method answers the global, the module, the workflow and the task question, with an adapter
   * and without one. What the keys below an extension MEAN is the extension's own business.
   * The core owns the location and this resolution.
   *
   * @param workflowModuleId The workflow module, or <code>null</code> for the global
   *          settings alone
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param adapterId The adapter, or <code>null</code> to read the general sections alone
   * @param extension The extension's id
   * @return The settings, never <code>null</code>
   */
  Map<String, String> extensionProperties(
      String workflowModuleId,
      String bpmnProcessId,
      String taskId,
      String adapterId,
      String extension);

  /**
   * One value of an extension's settings, resolved the same way as
   * {@link #extensionProperties(String, String)} resolves all of them.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param extension The extension's id
   * @param key The key below the extension's section, for example
   *          <code>rest.base-url</code>
   * @return The value, or <code>null</code> where nothing is configured there
   */
  String extensionProperty(
      String workflowModuleId,
      String extension,
      String key);

  /**
   * One value of an extension's settings, resolved over the positions without an adapter.
   * This is the counterpart of the adapter's own lookup, for an extension which asks about no
   * adapter in particular.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param extension The extension's id
   * @param key The key below the extension's section, for example
   *          <code>rest.base-url</code>
   * @return The most specific value configured, or <code>null</code>
   */
  String resolveForExtension(
      String workflowModuleId,
      String bpmnProcessId,
      String taskId,
      String extension,
      String key);

  /**
   * One value of an extension's settings, resolved over the eight positions which
   * {@link #extensionProperties(String, String, String, String, String)} describes.
   * <p>
   * The extension id is a parameter, and not bound to a view of these settings. This object
   * is one bean of the platform, but an extension is not a bean of the platform at all. It
   * asks with its own id, the same way as an adapter asks with its adapter id.
   *
   * @param workflowModuleId The workflow module, or <code>null</code>
   * @param bpmnProcessId The BPMN process, or <code>null</code>
   * @param taskId The task ID (task definition), or <code>null</code>
   * @param adapterId The adapter, or <code>null</code> to read the general sections alone
   * @param extension The extension's id
   * @param key The key below the extension's section, for example
   *          <code>rest.base-url</code>
   * @return The most specific value configured, or <code>null</code>
   */
  String resolveForExtension(
      String workflowModuleId,
      String bpmnProcessId,
      String taskId,
      String adapterId,
      String extension,
      String key);

}
