package io.vanillabp.integration.test.utils.springboot;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.lang.NonNull;

public class FullyQualifiedRepositoryBeanNameGenerator extends AnnotationBeanNameGenerator {

  @NonNull
  @Override
  protected String buildDefaultBeanName(
      final BeanDefinition definition) {

    final var result = definition.getBeanClassName();
    if (result == null) {
      throw new NullPointerException("getBeanClassName must not be null");
    }
    return result;
  }

}
