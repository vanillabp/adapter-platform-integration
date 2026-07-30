package io.vanillabp.buildtools.mapstruct;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;

import org.mapstruct.ap.spi.DefaultAccessorNamingStrategy;

/**
 * MapStruct accessor-naming strategy which additionally recognizes fluent
 * (record-style) accessors - parameterless non-void methods whose name carries
 * no <code>get</code>/<code>is</code> prefix - as getters. SmallRye
 * <code>@ConfigMapping</code> interfaces use exactly this accessor style, so this
 * strategy lets MapStruct read them as mapping SOURCES (targets stay ordinary
 * JavaBeans with <code>set*</code> methods).
 * <p>
 * Registered via <code>META-INF/services</code> and picked up when this artifact
 * is put on a compiler's <code>annotationProcessorPaths</code> next to
 * <code>mapstruct-processor</code> - it is a build-time-only artifact, never a
 * runtime dependency.
 */
public class FluentAccessorNamingStrategy extends DefaultAccessorNamingStrategy {

  @Override
  public boolean isGetterMethod(
      final ExecutableElement method) {

    if (super.isGetterMethod(method)) {
      return true;
    }
    return method.getParameters().isEmpty() && (method.getReturnType().getKind() != TypeKind.VOID);

  }

  @Override
  public String getPropertyName(
      final ExecutableElement getterOrSetterMethod) {

    final var name = getterOrSetterMethod.getSimpleName().toString();
    if (name.startsWith("get") || name.startsWith("set") || name.startsWith("is")) {
      return super.getPropertyName(getterOrSetterMethod);
    }
    return name;

  }

}
