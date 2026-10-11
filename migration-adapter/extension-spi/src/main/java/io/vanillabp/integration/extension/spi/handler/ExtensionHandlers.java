package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The mechanics behind <code>&#64;WorkflowTask</code>, opened up for the annotations an
 * extension brings. It scans the <code>&#64;WorkflowService</code> classes of the
 * application, matches a method by its name or by what its annotation names, binds its
 * parameters, loads the workflow aggregate, invokes the method and saves. All of this
 * happens in one transaction.
 * <p>
 * An extension registers its {@link HandlerContract}s once, and calls
 * {@link #invoke(HandlerCall)} whenever its own event happens. Both platforms provide
 * this as a bean.
 * <p>
 * <b>Registration may happen at any time.</b> The workflow services are scanned while
 * the process-service beans are created. On both platforms that happens before or after
 * the extension's own bean exists, depending on what else the application does. So a
 * contract registered late is also applied to the classes registered so far. An extension
 * never has to think about the order in which its bean is created.
 * <p>
 * <b>Exceptions of a handler method are passed on unchanged.</b> The extension decides
 * what a failure means for its own event, because only the extension knows whether the
 * BPMS repeats the notification.
 * <p>
 * <b>The BPMN process of an element</b> is the process the element belongs to, wherever a
 * method here asks for one. For an element of a called process (a call activity) that is
 * the called process. It is not the process at the top, which the workflow aggregate was
 * started with. Methods are bound per workflow service class and per process it declares,
 * also the called one through <code>secondaryBpmnProcesses</code>. A class serves every
 * process it declares, so the id of the process at the top may find the same method. But
 * the version an event names belongs to the called process. Under the id of the process
 * at the top, it is compared with the versions of the wrong process.
 */
public interface ExtensionHandlers {

  /**
   * Registers what an extension's annotation means. If a second contract is registered
   * for the same annotation, the application does not start, because the second one would
   * silently replace the first.
   *
   * @param contract The contract
   */
  void register(
      HandlerContract contract);

  /**
   * Whether the application has a method for that key. An extension asks this before it
   * builds an event nobody would receive. If the answer is no, the extension uses its own
   * default (the Business Cockpit passes the prefilled details through unchanged).
   * <p>
   * <b>The order of the keys decides.</b> If an extension offers several keys for one
   * element, the first key which some method serves wins. So the list says which key ranks
   * above which. VanillaBP is moving to the BPMN element id as the identity of an element.
   * So offer the element id first and the task definition after it (see decision 51 in the
   * repository's DECISIONS.md). A method which serves every key of the process also answers
   * <code>true</code>. But it is the answer only if nobody names any key of the list.
   * <p>
   * This asks about a call which names no version, so a method which names one answers
   * <code>false</code>. If your event knows the version of its BPMN process, ask
   * {@link #hasHandler(Class, String, String, List, String)} instead.
   *
   * @param annotationType The annotation of a registered contract
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process the element belongs to - for an element of a
   *          called process the called process, not the one at the top
   * @param lookupKeys The keys a method may be matched by, most wanted first
   * @return Whether a method serves any of those keys
   */
  default boolean hasHandler(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    return hasHandler(annotationType, workflowModuleId, bpmnProcessId, lookupKeys, null);

  }

  /**
   * Whether the application has a method for that key and that version of the BPMN
   * process. It is the question above, asked for the version an event came from.
   * <p>
   * Ask it this way whenever your event names a version. Pass the version the BPMS reports,
   * spelled the way the BPMS spells it. A method which names versions answers
   * <code>true</code> for the versions it names and <code>false</code> for the rest. So an
   * extension learns that nobody serves the version at hand before it builds the event. A
   * version of <code>null</code> is answered by the methods which name no version. These
   * are all methods of a contract which says nothing about versions.
   *
   * @param annotationType The annotation of a registered contract
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process the element belongs to - for an element of a
   *          called process the called process, not the one at the top
   * @param lookupKeys The keys a method may be matched by, most wanted first
   * @param processVersion The version the BPMS reports, or <code>null</code> if it
   *          reports none
   * @return Whether a method serves any of those keys for that version
   */
  boolean hasHandler(
      Class<? extends Annotation> annotationType,
      String workflowModuleId,
      String bpmnProcessId,
      List<String> lookupKeys,
      String processVersion);

  /**
   * The process variables which the methods serving these keys read with
   * <code>&#64;TaskParam</code>, with the names as the annotation spells them. Some BPMS
   * hand out only the variables somebody named, for example a Camunda 8 job worker. There
   * an extension has to ask its BPMS for these variables before the first event arrives.
   * <p>
   * The keys are resolved the way {@link #invoke(HandlerCall)} resolves them, but for
   * every version of the BPMN process at once. The first key which some method serves
   * wins. A later key, or the method which serves every key, only counts in the versions
   * which no earlier key covers. So the answer is the UNION over every method which may
   * run for this element in some version, because the event has to satisfy whichever of
   * them runs. If several methods of one key split the versions between them, the method
   * which serves every key is counted as well, although it may never run. So the answer
   * can name one variable too many, but it never misses one (see decision 121 in the
   * repository's DECISIONS.md). Pass the keys in the same order as to
   * {@link HandlerCall.Builder#lookupKeys}.
   * <p>
   * The answer is per workflow module and BPMN process, like
   * <code>WorkflowTaskWiring#taskParameterNames</code> answers it for
   * <code>&#64;WorkflowTask</code> methods. It holds exactly the
   * <code>&#64;TaskParam</code> names VanillaBP binds. A parameter which one of your own
   * binders claims is yours, and it is not among them. Neither is anything a
   * multi-instance parameter reads. The names are sorted and have no duplicates, so a
   * subscription built from them stays the same across restarts.
   * <p>
   * The answer covers the workflow services scanned so far, the same as
   * {@link #hasHandler(Class, String, String, List)} does. The default answers nothing.
   * Only the core implements this interface, so the default is for test doubles, not for
   * applications.
   *
   * @param annotationType The annotation of a registered contract
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process the element belongs to - for an element of a
   *          called process the called process, not the one at the top
   * @param lookupKeys The keys a method may be matched by, most wanted first
   * @return The parameter names, sorted. Empty if no method serves the keys, or if none of
   *         them takes a <code>&#64;TaskParam</code>
   * @throws IllegalStateException If no contract is registered for the annotation
   */
  default Collection<String> taskParameterNames(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> lookupKeys) {

    return List.of();

  }

  /**
   * Names the elements of a model which the adapter just wired, where a method of this
   * contract may serve them. Call it from your <code>wireBpmn</code>, once for each BPMN
   * process, if the contract says {@link HandlerContract.Builder#theDeployedVersionNeedsAMethod()}.
   * The BPMS gives the model a version. Then VanillaBP checks the methods of each element
   * against that version. If an element has methods, but none of them covers that version,
   * the start ends.
   * <p>
   * Each element is a list of keys. Use the keys you would pass to
   * {@link HandlerCall.Builder#lookupKeys} for that element, in the same order. Then the check
   * picks the same methods as a call would pick. An empty list stands for the BPMN process
   * itself. Only a method which serves every key ({@link HandlerContract#EVERY_KEY}) serves
   * it. If no method serves an element at all, the check does not ask about it.
   * <p>
   * The elements are kept for each workflow module and BPMN process, not for each adapter.
   * Each adapter checks them against the version which its own BPMS gave the model. If two
   * adapters deploy different models of one process, they share what was reported for it.
   * <p>
   * A second call adds to what was reported. If the contract does not ask for a method for
   * the deployed version, the elements are kept, but nobody asks about them. The default does
   * nothing. Only the core implements this interface, so the default is for test doubles,
   * not for applications.
   *
   * @param annotationType The annotation of your contract
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process of the elements. For an element of a called
   *          process, this is the called process, not the one at the top
   * @param lookupKeysOfEachElement The lookup keys of each element, the most wanted first
   */
  default void elementsOfTheDeployedModel(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<List<String>> lookupKeysOfEachElement) {

    // a test double has no start to end

  }

  /**
   * Runs the method the call addresses. It loads the workflow aggregate (or takes the one
   * passed in), binds the parameters, invokes the method and saves, all in one transaction
   * of the platform.
   *
   * @param call What to invoke and what to bind it from
   * @return What the method returned. Empty if no method serves the call's keys in the
   *         call's version, or if the contract does not deliver return values
   * @throws IllegalStateException If no contract is registered for the call's
   *           annotation
   */
  Optional<Object> invoke(
      HandlerCall call);

  /**
   * The workflow aggregate a BPMN process of a workflow module works on. An extension needs
   * it before it can ask anything else about that workflow. The aggregate is the type
   * argument of the extension's own service per aggregate, and the type it passes to a
   * resolver of the platform.
   * <p>
   * The answer is the class named by the <code>&#64;WorkflowService</code> which declares
   * that process, and the scan already knows it. If an extension worked it out again, it
   * would read the same annotations again, through the bean proxies of a platform it
   * should not have to know about.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process, primary or secondary
   * @return The workflow-aggregate class, or empty if no
   *         <code>&#64;WorkflowService</code> of this application declares that process
   */
  Optional<Class<?>> workflowAggregateOf(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Every BPMN process of a workflow module which a <code>&#64;WorkflowService</code> class
   * declares. Primary and secondary ones are both included, in the order they were
   * registered, each named once. An extension which has something to publish per workflow
   * (a set of user-task templates, a registration with a server of its own) asks this. It
   * does not scan the beans of the application.
   * <p>
   * A BPMN process which the application deploys without claiming it in a
   * <code>&#64;WorkflowService</code> is not in the list. Nothing declares which aggregate
   * it works on, so an extension could not do anything with it.
   *
   * @param workflowModuleId The workflow module
   * @return The BPMN process ids, empty if the module has no workflow service
   */
  List<String> bpmnProcessesOf(
      String workflowModuleId);

  /**
   * The <code>name</code> a modeller wrote on a BPMN element, as the adapter read it from
   * the model it deployed. A person expects to see this label next to a task, and the
   * application does not have it anywhere. Without this method, an extension which wants
   * it would parse the same BPMN bytes a second time.
   * <p>
   * The answer is plain text, not a type of the adapter SPI, because an extension builds
   * against this artifact and nothing else. It is empty if the element has no name, if the
   * adapter does not read one, or if nothing was deployed under that process at all.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process the element belongs to, spelled the way the
   *          application writes it - for an element of a called process the called process
   * @param activityId The <code>id</code> attribute of the element
   * @return The element's name, or empty
   */
  Optional<String> bpmnTaskNameOf(
      String workflowModuleId,
      String bpmnProcessId,
      String activityId);

}
