package io.vanillabp.integration.adapter.spi;

/**
 * Thrown by adapters if parsing a BPMN file fails (e.g. the file is not a valid BPMN XML).
 *
 * @see AdapterDeploymentService#readBpmn(String, String, java.io.InputStream, boolean)
 */
public class BpmnParseException extends RuntimeException {

  /**
   * Stops the reading of one BPMN file with a message only the adapter can write.
   *
   * @param message What is wrong with the file. Name the file and the workflow module in
   *          it: the adapter is the only one which knows which of the module's files it
   *          was reading, and this message is what a developer fixes the model by
   */
  public BpmnParseException(
      final String message) {

    super(message);

  }

  /**
   * The same, keeping what the parser threw - the form an adapter uses which wraps the
   * parser of its BPMS, so the parser's own report about what it choked on is not lost.
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
