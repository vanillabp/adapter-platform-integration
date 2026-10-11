package io.vanillabp.integration.test.workflowversion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotation of {@link DeployedVersionNoteExtension}. The contract of this test
 * extension asks for a method for the version which a start deployed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DeployedVersionNote {

  /**
   * @return The BPMN element the method serves, <code>*</code> for the process itself
   */
  String element();

  /**
   * @return The versions of the BPMN process the method serves
   */
  String version();

}
