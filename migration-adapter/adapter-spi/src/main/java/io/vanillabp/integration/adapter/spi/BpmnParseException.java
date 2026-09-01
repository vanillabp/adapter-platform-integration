package io.vanillabp.integration.adapter.spi;

/**
 * Thrown by adapters if parsing a BPMN file fails (e.g. the file is not a valid BPMN XML).
 *
 * @see AdapterDeploymentService#readBpmn(String, String, java.io.InputStream, boolean)
 */
public class BpmnParseException extends RuntimeException {

  public BpmnParseException(
      final String message) {

    super(message);

  }

  public BpmnParseException(
      final String message,
      final Throwable cause) {

    super(message, cause);

  }

}
