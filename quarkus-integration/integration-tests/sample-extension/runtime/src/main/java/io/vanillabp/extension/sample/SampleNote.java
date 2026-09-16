package io.vanillabp.extension.sample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The sample extension's own SPI: a method of a <code>&#64;WorkflowService</code> class
 * building the note this extension publishes about a BPMN element - the shape the
 * Business Cockpit's <code>&#64;UserTaskDetailsProvider</code> has.
 * <p>
 * It lives in the extension, not in VanillaBP: an application not using this extension
 * must never see this annotation. What VanillaBP contributes is the machinery behind it,
 * asked for by a {@code HandlerContract}.
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
   * The versions of the BPMN process this method builds the note of, written the way
   * <code>&#64;WorkflowTask(version = ...)</code> writes them. Naming none means every
   * version, and that is what an application which knows one generation of its model
   * writes.
   *
   * @return The version specifications
   */
  String[] version() default {};

}
