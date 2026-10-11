package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * What an extension checks about one use of its own annotation while VanillaBP scans
 * the <code>&#64;WorkflowService</code> classes. At that moment the method which carries
 * the annotation is at hand.
 * <p>
 * An extension has rules which the {@link HandlerContract} cannot express. For example
 * an attribute which only makes sense together with another one, a value which has to
 * name something the extension knows, or an attribute which a version of the extension
 * does not serve yet. If the checks run here, the application does not start with a
 * wiring nobody would ever reach. And the extension does not have to walk through the
 * classes of the application a second time to find the method again. This hook replaces
 * that second walk, which never saw the same methods anyway: the scan reads the PUBLIC
 * methods of the class.
 * <p>
 * <b>Refuse by throwing.</b> The developer reads the message, so write what is wrong and
 * what to do about it. VanillaBP puts the annotation, the class, the method and the
 * extension in front of it, and stops the start of the application.
 */
@FunctionalInterface
public interface HandlerAnnotationCheck {

  /**
   * Checks one use of the extension's annotation.
   *
   * @param annotation The use, of the contract's annotation type
   * @param method The method carrying it, of the <code>&#64;WorkflowService</code>
   *          class being scanned
   * @throws RuntimeException Naming what is wrong with it and what to do
   */
  void check(
      Annotation annotation,
      Method method);

}
