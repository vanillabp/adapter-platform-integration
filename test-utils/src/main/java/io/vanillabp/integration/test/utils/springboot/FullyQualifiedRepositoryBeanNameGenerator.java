package io.vanillabp.integration.test.utils.springboot;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.lang.NonNull;

/**
 * Names a Spring Data repository bean after its full class name instead of its simple
 * one. A test which boots two stores at once has one repository interface per store, and
 * two of them may carry the same simple name in different packages. Spring's default
 * generator derives the bean name from that simple name, so the two clash. Naming this
 * class as the {@code nameGenerator} of {@code @EnableMongoRepositories} and
 * {@code @EnableJpaRepositories} keeps them apart.
 */
public class FullyQualifiedRepositoryBeanNameGenerator extends AnnotationBeanNameGenerator {

  /**
   * Spring builds the generator itself, from the class the annotation names. A test
   * never creates it and never calls it.
   */
  public FullyQualifiedRepositoryBeanNameGenerator() {
  }

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
