package io.vanillabp.integration.extension.spi;

/**
 * An implementation prepares the BPMN and wires it with the business code. VanillaBP extensions
 * and the adapters of the platform integration provide implementations.
 * <p>
 * A BPMS adapter does not implement this interface directly. The adapter SPI extends it and adds
 * everything which is needed to read and deploy a model.
 * <p>
 * <b>The adapter of the BPMS runs before every extension.</b> It has wired a BPMN process before
 * any extension sees that process. And it processes workflows before any extension is started.
 * On shutdown the order is the other way round: extensions are stopped first, the adapters last.
 * So an extension adds its parts to a model RELATIVE to what the adapter already put there, for
 * example behind the last listener of a kind. It does not have to guess a position. If something
 * has to see an element before the adapter touched it, it needs an entry point of its own and has
 * to ask for one.
 * <p>
 * <b>The order of two extensions among themselves is not promised.</b> They are sorted by
 * {@link #getOrder()}. Two extensions which do not know each other cannot agree on a number. So
 * two of the same order run in the order in which the platform happened to collect their beans.
 * Nothing promises that this order stays. An extension which only needs to run after the adapter
 * is not affected by any of this. That is why VanillaBP has no <code>MIN_VALUE</code> and
 * <code>MAX_VALUE</code> for anybody to claim. Both halves are decision 52 in the repository's
 * DECISIONS.md, and an extension author reads about them on the wiki page <code>Extensions</code>.
 *
 * @param <BPMN> The BPMN model type
 * @param <PC> The context to store all information needed by the adapter for wiring and deploying BPMN
 */
public interface ExtensionWiringService<BPMN, PC> {

  /**
   * The BPMN model class this service can wire. It decides whether the service is asked
   * about a model at all. The deployment asks the service if this type is the same as the
   * model type of the adapter which read the file, or a supertype of it. So a service
   * written for another BPMS is skipped. It does not get a model it cannot read.
   * <p>
   * Answer with a class literal. The deployment matches this value, never the type
   * argument of the implementing class.
   *
   * @return The model type
   */
  Class<BPMN> getModelType();

  /**
   * The class of the context this service gets while a model is wired, and while the
   * workflows of a module are started. It is matched like {@link #getModelType()}, and
   * both have to fit. So a service is either wired and started, or neither.
   *
   * @return The process context type
   */
  Class<PC> getProcessContextType();

  /**
   * The order among the EXTENSIONS of one model type, ascending. It says nothing about the
   * adapter, which runs before all of them anyway (see the type javadoc). Two extensions of the
   * same order run in the order the platform collected them in, and nobody promises that order.
   *
   * @return The order of this service. Defaults to <code>0</code>, so an extension implements it
   *         only if it has to run before or after another one. A BPMS adapter leaves the default.
   *         The core takes the adapters out of this list before it sorts, so the number of an
   *         adapter is never read. Which adapter of a workflow module goes first comes from
   *         <code>vanillabp.prioritized-adapters</code> instead
   */
  default int getOrder() {

    return 0;

  }

  /**
   * Names this extension, e.g. <code>business-cockpit</code>. At startup, the name is used to find
   * the version file <code>META-INF/vanillabp/extension-&lt;name&gt;.properties</code> which the
   * extension ships. The file says which VanillaBP platform integration the extension was built
   * against. If an extension names itself and ships that file, it cannot run against a platform
   * integration it does not belong to. If it names itself and ships no file, it starts anyway,
   * and the startup says once that this pair is unknown. If it does not name itself, it is not
   * checked at all, because the startup could only say the name of a class about it
   * (see decision 71 in the repository's DECISIONS.md).
   * <p>
   * A BPMS adapter does not answer this question. It is found by its adapter type instead.
   *
   * @return The name of this extension, or <code>null</code> if it does not say
   */
  default String getExtensionName() {

    return null;

  }

  /**
   * Wires the given model with the business code.
   *
   * @param workflowModuleId The workflow module ID
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmnProcessId The BPMN process ID
   * @param model The model
   * @param context The context passed to startProcessing (usually used to collect wiring information)
   */
  void wireBpmn(
      String workflowModuleId,
      String filename,
      String bpmnProcessId,
      BPMN model,
      PC context);

  /**
   * Starts running the workflows of the BPMN processes deployed before. For a BPMS adapter,
   * this is where workers, subscriptions and job executors open. Before this call, nothing of
   * that module runs.
   * <p>
   * It is called once per workflow module, after every adapter of that module has finished
   * deploying. The adapters of a module are started before any extension of it. The call
   * runs on the thread which starts the application. So an adapter which needs threads of
   * its own starts them here, and limits their number itself, because every running handler
   * holds a database connection. If the call throws, the application does not start. Unlike
   * a failure while deploying, <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>
   * does not soften this.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context specific to the BPMS
   */
  void startWorkflowProcessing(
      String workflowModuleId,
      PC bpmsProcessingContext);

  /**
   * Stops running the workflows of the BPMN processes started before. It is called on a
   * graceful shutdown of the application, before the web or messaging infrastructure of
   * the platform is stopped. The default implementation does nothing.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context specific to the BPMS
   */
  default void stopWorkflowProcessing(
      final String workflowModuleId,
      final PC bpmsProcessingContext) {
    // by default there is nothing to stop
  }

}
