package io.vanillabp.integration.adapter.migration.handler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;

import io.vanillabp.integration.extension.spi.handler.HandlerParameter;

/**
 * What an extension's binder sees of a parameter of a handler method: the plain reflection
 * data, plus a description from which a guiding message can be built.
 *
 * @param parameter The reflected parameter
 * @param location Names the parameter, its method and its class
 */
record ReflectiveHandlerParameter(
                                  Parameter parameter,
                                  String location) implements HandlerParameter {

  @Override
  public Class<?> getType() {

    return parameter.getType();

  }

  @Override
  public <A extends Annotation> A getAnnotation(
      final Class<A> annotationType) {

    return parameter.getAnnotation(annotationType);

  }

  @Override
  public boolean isAnnotationPresent(
      final Class<? extends Annotation> annotationType) {

    return parameter.isAnnotationPresent(annotationType);

  }

  @Override
  public String describe() {

    return location;

  }

}
