package io.vanillabp.integration.extension.spi;

/**
 * An implementation is responsible for preparing the BPMN and wiring it with the business code.
 * The implementation may be provided by custom VanillaBP extensions or by platform integration adapters.
 * <p>
 * A BPMS adapter does not implement this interface directly: the adapter SPI extends it and adds
 * everything reading and deploying a model takes.
 * <p>
 * <b>The adapter of the BPMS runs before every extension.</b> It has wired a BPMN process before
 * any extension sees that process, and it is processing workflows before any extension is started.
 * On the way down the order is mirrored: extensions are stopped first and the adapters last. So an
 * extension hooks what it adds to a model in RELATIVE to what the adapter already put there, for
 * example behind the last listener of a kind, rather than at a position it has to guess. Something
 * which has to see an element before the adapter touched it needs an entry point of its own and has
 * to ask for one.
 * <p>
 * <b>What is not promised is the order of two extensions among themselves.</b> They are sorted by
 * {@link #getOrder()}, and two extensions which do not know each other cannot agree on a number, so
 * two of the same order run in the order the platform happened to collect their beans in. Nothing
 * promises that this order stays. An extension which only depends on running after the adapter is
 * unaffected by all of it, which is why VanillaBP has no <code>MIN_VALUE</code> and
 * <code>MAX_VALUE</code> for anybody to claim. Both halves are decision 52 in the repository's
 * DECISIONS.md, and the wiki page <code>Extensions</code> is where an extension author reads them.
 *
 * @param <BPMN> The BPMN model type
 * @param <PC> The context to store all information needed by the adapter for wiring and deploying BPMN
 */
public interface ExtensionWiringService<BPMN, PC> {

  /**
   * The BPMN model class this service can wire. It decides whether the service is asked
   * about a model at all: the deployment asks it where this type is the same as, or a
   * supertype of, the model type of the adapter which read the file, so a service written
   * for another BPMS is skipped instead of being handed a model it cannot read.
   * <p>
   * Answer with a class literal. This value is what the deployment matches, never the type
   * argument of the implementing class.
   *
   * @return The model type
   */
  Class<BPMN> getModelType();

  /**
   * The class of the context this service is handed while a model is wired and while the
   * workflows of a module are started. It is matched the way {@link #getModelType()} is
   * and both have to fit, so a service is either wired and started or neither of the two.
   *
   * @return The process context type
   */
  Class<PC> getProcessContextType();

  /**
   * The order among the EXTENSIONS of one model type, ascending. It says nothing about the adapter,
   * which runs before all of them either way (see the type javadoc), and two extensions of the same
   * order run in the order the platform collected them in, which nobody promises.
   *
   * @return The order of this service. Defaults to <code>0</code>, so implementations (especially
   *         adapters) only need to implement this method if a specific order is required.
   */
  default int getOrder() {

    return 0;

  }

  /**
   * Names this extension, e.g. <code>business-cockpit</code>. The name is how the boot finds the
   * version descriptor <code>META-INF/vanillabp/extension-&lt;name&gt;.properties</code> the
   * extension ships, which says which VanillaBP platform integration the extension was built
   * against. An extension which names itself and ships that file is kept from running against a
   * platform integration it does not belong to. One which names itself and ships no file starts
   * anyway, and the boot says once that this pair is unknown. One which does not name itself is
   * not judged at all, because the only thing the boot could say about it is the name of a class
   * (see decision 71 in the repository's DECISIONS.md).
   * <p>
   * A BPMS adapter does not answer this question: it is found by its adapter type instead.
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
   * Start running the workflows BPMN processes previously deployed.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmsProcessingContext The processing context specific to the BPMS
   */
  void startWorkflowProcessing(
      String workflowModuleId,
      PC bpmsProcessingContext);

  /**
   * Stop running the workflows BPMN processes previously started. Called on graceful
   * shutdown of the application, before the platform's web or messaging
   * infrastructure is stopped. The default implementation does nothing.
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
