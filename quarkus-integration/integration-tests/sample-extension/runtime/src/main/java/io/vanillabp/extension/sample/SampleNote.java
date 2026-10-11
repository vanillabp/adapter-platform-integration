package io.vanillabp.extension.sample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The sample extension's own SPI. It marks a method of a <code>&#64;WorkflowService</code>
 * class which builds the note this extension publishes about a BPMN element. The Business
 * Cockpit's <code>&#64;UserTaskDetailsProvider</code> has the same shape.
 * <p>
 * It lives in the extension, not in VanillaBP. An application which does not use this
 * extension must never see this annotation. VanillaBP contributes the machinery behind it,
 * and a {@code HandlerContract} asks for that machinery.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(SampleNotes.class)
public @interface SampleNote {

  /**
   * The default of both attributes: the method's own name is the key it serves.
   */
  String USE_METHOD_NAME = "";

  /**
   * The BPMN element this method builds the note of.
   *
   * @return The element id
   */
  String element() default USE_METHOD_NAME;

  /**
   * The task definition this method builds the note of.
   *
   * @return The task definition
   */
  String taskDefinition() default USE_METHOD_NAME;

  /**
   * The versions of the BPMN process this method builds the note of. They are written the
   * way <code>&#64;WorkflowTask(version = ...)</code> writes them. Naming none means every
   * version. An application which knows only one generation of its model writes that.
   *
   * @return The version specifications
   */
  String[] version() default {};

}
