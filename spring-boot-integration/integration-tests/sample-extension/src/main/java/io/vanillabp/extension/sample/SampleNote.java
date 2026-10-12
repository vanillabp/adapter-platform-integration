package io.vanillabp.extension.sample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The own SPI of the sample extension. It marks a method of a
 * <code>&#64;WorkflowService</code> class which builds the note this extension publishes
 * about a BPMN element. It has the shape of the Business Cockpit's
 * <code>&#64;UserTaskDetailsProvider</code>.
 * <p>
 * It lives in the extension, not in VanillaBP. An application which does not use this
 * extension must never see this annotation. VanillaBP adds the machinery behind it, and
 * the extension asks for it with a {@code HandlerContract}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(SampleNotes.class)
public @interface SampleNote {

  /**
   * The default of both attributes. The name of the method is the key it serves.
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
   * The versions of the BPMN process this method builds the note of. They are written as
   * in <code>&#64;WorkflowTask(version = ...)</code>. Naming none means every version. An
   * application which knows only one generation of its model writes none.
   *
   * @return The version specifications
   */
  String[] version() default {};

}
