package io.vanillabp.integration.processservice;

import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanRegistryAdapter;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the workflow services of the application and hands them to the
 * {@link ProcessServiceBeanRegistrar}, which builds a
 * {@link io.vanillabp.spi.process.ProcessService} bean per workflow aggregate.
 * <p>
 * A workflow service is found because it is a Spring bean, not because of where its
 * class sits on the classpath: the handler object of a task delivery is resolved through
 * the bean factory anyway (see
 * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}),
 * so a class annotated by {@link WorkflowService} which is no bean could not serve a
 * task even if it was found - and only the bean definitions know which services the
 * active profile brought into THIS run. The alternatives which were measured and
 * rejected are in decision 21 in the repository's DECISIONS.md.
 * <p>
 * This runs as a {@link BeanDefinitionRegistryPostProcessor} rather than as an imported
 * {@link org.springframework.beans.factory.BeanRegistrar}, because an imported registrar
 * runs WHILE the configuration classes are processed: it would see the definitions of the
 * configuration classes read so far and miss everything a later one contributes - a
 * library's auto-configuration registering its workflow services, for instance. Ordered
 * last, so every {@code ConfigurationClassPostProcessor} and every other ordered
 * post-processor has registered what it brings.
 */
@Slf4j
public class WorkflowServiceDiscovery implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware, EnvironmentAware, Ordered {

  private ConfigurableListableBeanFactory beanFactory;

  private Environment environment;

  @Override
  public void setBeanFactory(
      final BeanFactory beanFactory) throws BeansException {

    this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;

  }

  @Override
  public void setEnvironment(
      final Environment environment) {

    this.environment = environment;

  }

  @Override
  public int getOrder() {

    return Ordered.LOWEST_PRECEDENCE;

  }

  @Override
  public void postProcessBeanDefinitionRegistry(
      final BeanDefinitionRegistry registry) throws BeansException {

    final var workflowServiceClasses = workflowServiceClassesOf(registry);

    log.debug(
        "Found {} workflow service(s) among the bean definitions: {}",
        workflowServiceClasses.size(),
        workflowServiceClasses.stream().map(Class::getName).toList());

    // the adapter turns the BeanRegistry API - the generics-aware bean type and the
    // lazy supplier the registrar builds its definitions with - into definitions of
    // this registry
    new BeanRegistryAdapter(
        registry, beanFactory, environment, ProcessServiceBeanRegistrar.class)
        .register(new ProcessServiceBeanRegistrar(workflowServiceClasses));

  }

  /**
   * All classes annotated by {@link WorkflowService} the application registered a bean
   * of.
   *
   * @param registry The bean definitions of the application
   * @return The workflow service classes, each of them once no matter how many beans of
   *     it exist (which bean serves a task is decided when the task is delivered, by
   *     asking the bean factory for the one bean of that class)
   */
  private List<Class<?>> workflowServiceClassesOf(
      final BeanDefinitionRegistry registry) {

    final var workflowServiceClasses = new LinkedHashSet<Class<?>>();
    for (final var beanName : registry.getBeanDefinitionNames()) {
      final var beanClass = beanClassOf(beanName);
      if (beanClass == null) {
        continue;
      }
      // findAnnotation walks the superclass chain, so a subclass of an annotated class
      // is a workflow service as well - which is what @Inherited promises the developer
      if (AnnotationUtils.findAnnotation(beanClass, WorkflowService.class) == null) {
        continue;
      }
      workflowServiceClasses.add(beanClass);
    }
    return List.copyOf(workflowServiceClasses);

  }

  /**
   * The class of a bean, determined without creating it: the declared class of an
   * annotated component, the return type of an {@code @Bean} method, and for a bean
   * behind a proxy the class the application wrote.
   *
   * @param beanName The name of the bean definition
   * @return The class or {@code null} where it cannot be determined - a factory bean
   *     which would have to be created to answer, or a class an optional dependency
   *     left behind. Neither can be the workflow service of this run: a class which
   *     cannot be loaded cannot handle a task either.
   */
  private Class<?> beanClassOf(
      final String beanName) {

    try {
      final var beanType = beanFactory.getType(beanName, false);
      return beanType == null
          ? null
          : ClassUtils.getUserClass(beanType);
    } catch (Exception e) {
      log.trace("Could not determine the class of bean '{}'", beanName, e);
      return null;
    }

  }

}
