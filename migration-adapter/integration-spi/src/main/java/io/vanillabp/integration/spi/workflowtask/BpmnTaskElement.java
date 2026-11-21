package io.vanillabp.integration.spi.workflowtask;

/**
 * The names of one task of a BPMN process, as a BPMS adapter read them from the model.
 * <p>
 * A BPMS adapter describes a task as <code>BpmnTaskSpec</code> of the adapter SPI, which
 * implements this interface. An extension which receives such a description, for example
 * from an API of a BPMS adapter, reads it through this interface. It never names
 * <code>BpmnTaskSpec</code>.
 */
public interface BpmnTaskElement {

  /**
   * The BPMN activity ID: the <code>id</code> attribute of the task element.
   *
   * @return The activity ID
   */
  String activityId();

  /**
   * The task definition, for example the job type of Camunda 8 or the topic of Camunda 7.
   *
   * @return The task definition, or <code>null</code> if the task carries none
   */
  String taskDefinition();

  /**
   * The <code>name</code> attribute of the BPMN element: what a modeller wrote on it.
   *
   * @return The name, or <code>null</code> where the element has none or the adapter does
   *         not read it
   */
  String name();

}
