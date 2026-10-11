package io.vanillabp.integration.adapter.spi;

/**
 * Thrown by adapters if parsing a BPMN file fails (e.g. the file is not a valid BPMN XML).
 *
 * @see AdapterDeploymentService#readBpmn(String, String, java.io.InputStream, boolean)
 */
public class BpmnParseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Stops the reading of one BPMN file with a message only the adapter can write.
   *
   * @param message What is wrong with the file. Name the file and the workflow module in
   *          it. Only the adapter knows which file of the module it was reading, and the
   *          developer fixes the model with the help of this message
   */
  public BpmnParseException(
      final String message) {

    super(message);

  }

  /**
   * The same, but it keeps what the parser threw. An adapter which wraps the parser of its
   * BPMS uses this form. Then the parser's own report about what went wrong is not lost.
   *
   * @param message What is wrong with the file, naming the file and the workflow module
   * @param cause What the parser threw
   */
  public BpmnParseException(
      final String message,
      final Throwable cause) {

    super(message, cause);

  }

}
