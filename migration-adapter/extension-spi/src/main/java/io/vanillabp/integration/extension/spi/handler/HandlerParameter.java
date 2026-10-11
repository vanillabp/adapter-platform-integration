package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;

/**
 * One parameter of a handler method. While the method is scanned, the parameter binders
 * of an extension get to see it. A binder decides from the type and the annotations
 * whether it serves the parameter.
 *
 * @see HandlerParameterBinder
 */
public interface HandlerParameter {

  /**
   * What the parameter is declared as, before anything is converted. A binder decides from
   * it and from the annotations whether it serves the parameter.
   *
   * @return The declared type of the parameter
   */
  Class<?> getType();

  /**
   * One annotation of the parameter, so a binder can read what it says, for example the
   * name of the value it asks for.
   *
   * @param <A> The annotation type
   * @param annotationType The annotation to look for
   * @return The annotation of the parameter, or <code>null</code>
   */
  <A extends Annotation> A getAnnotation(
      Class<A> annotationType);

  /**
   * Whether the parameter is marked with an annotation. Ask this for an annotation which
   * has nothing to read.
   *
   * @param annotationType The annotation to look for
   * @return Whether the parameter carries that annotation
   */
  boolean isAnnotationPresent(
      Class<? extends Annotation> annotationType);

  /**
   * Names the parameter, its method and its class. A binder which rejects a parameter
   * builds its message from this, so the developer reads where to look.
   *
   * @return The description, e.g.
   *         <code>parameter 'details' of @UserTaskDetailsProvider method 'com.acme.Loan#approve'</code>
   */
  String describe();

}
