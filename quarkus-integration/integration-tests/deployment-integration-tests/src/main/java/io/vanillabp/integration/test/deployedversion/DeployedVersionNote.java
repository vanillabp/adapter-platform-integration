package io.vanillabp.integration.test.deployedversion;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotation of {@link DeployedVersionNoteWiringService}. That test extension has a
 * contract which asks for a method for the version that a start deployed.
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
