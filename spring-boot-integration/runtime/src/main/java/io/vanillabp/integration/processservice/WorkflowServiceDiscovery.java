package io.vanillabp.integration.processservice;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanRegistryAdapter;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowServiceBelongsOnAClass;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;
import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the workflow services of the application and hands them to the
 * {@link ProcessServiceBeanRegistrar}, which builds a
 * {@link io.vanillabp.spi.process.ProcessService} bean per workflow aggregate.
 * <p>
 * A workflow service is found because it is a Spring bean, not because of where its
 * class sits on the classpath. The handler object of a task delivery is looked up in the
 * bean factory anyway (see
 * {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}).
 * So a class annotated by {@link WorkflowService} which is no bean could not serve a task,
 * even if it was found. And only the bean definitions know which services the active
 * profile brought into THIS run. The alternatives which were measured and rejected are in
 * decision 21 in the repository's DECISIONS.md.
 * <p>
 * This runs as a {@link BeanDefinitionRegistryPostProcessor}, not as an imported
 * {@link org.springframework.beans.factory.BeanRegistrar}. An imported registrar runs
 * WHILE the configuration classes are processed. It would see the definitions of the
 * configuration classes read so far. It would miss everything a later one contributes,
 * for instance a library's auto-configuration which registers its workflow services. It
 * is ordered last, so every {@code ConfigurationClassPostProcessor} and every other
 * ordered post-processor has registered what it brings.
 */
@Slf4j
public class WorkflowServiceDiscovery implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware, EnvironmentAware, Ordered {

  private ConfigurableListableBeanFactory beanFactory;

  private Environment environment;

  /**
   * Built by a bean method of {@link SpringBootMigrationAdapterAutoConfiguration}. That
   * method is static, so this post-processor can be created without creating that
   * configuration class first. Nothing is handed in. The bean factory and the environment
   * arrive through the two setters Spring calls afterwards.
   */
  public WorkflowServiceDiscovery() {

  }

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

    final var aggregateServiceInterfaces = aggregateServiceInterfacesOf(registry);

    // the adapter turns the BeanRegistry API into definitions of this registry. The
    // registrar builds its definitions with that API: the bean type which knows generics,
    // and the lazy supplier
    new BeanRegistryAdapter(
        registry, beanFactory, environment, ProcessServiceBeanRegistrar.class)
        .register(new ProcessServiceBeanRegistrar(workflowServiceClasses, aggregateServiceInterfaces));

  }

  /**
   * The service interfaces which the extensions of this application offer per workflow
   * aggregate. They are read from their {@code AggregateServiceFactory} beans. Like the
   * workflow services, they are found because they are beans. Their classes are read
   * without creating anything.
   *
   * @param registry The bean definitions of the application
   * @return The service interfaces, each of them once
   * @throws IllegalStateException If a factory names a service interface which cannot
   *     carry the aggregate as its type argument
   */
  private List<Class<?>> aggregateServiceInterfacesOf(
      final BeanDefinitionRegistry registry) {

    final var interfaces = new LinkedHashSet<Class<?>>();
    for (final var beanName : registry.getBeanDefinitionNames()) {
      final var beanClass = beanClassOf(beanName);
      if ((beanClass == null) || !AggregateServiceFactory.class.isAssignableFrom(beanClass)) {
        continue;
      }
      final var serviceInterface = serviceInterfaceOf(beanName, beanClass);
      if (serviceInterface == null) {
        throw new IllegalStateException(
            """
                The AggregateServiceFactory '%s' does not name the service interface it builds! \
                Implement AggregateServiceFactory<YourService> rather than the raw interface - \
                VanillaBP registers one bean of that interface per workflow aggregate and needs to \
                know its type."""
                .formatted(beanClass.getName()));
      }
      if (serviceInterface.getTypeParameters().length != 1) {
        throw new IllegalStateException(
            """
                The service interface '%s' offered by the AggregateServiceFactory '%s' has %d type \
                parameters! It is injected as '%s<YourWorkflowAggregate>', so it has to take exactly \
                one - the workflow aggregate."""
                .formatted(
                    serviceInterface.getName(),
                    beanClass.getName(),
                    serviceInterface.getTypeParameters().length,
                    serviceInterface.getSimpleName()));
      }
      interfaces.add(serviceInterface);
    }
    if (!interfaces.isEmpty()) {
      log.debug(
          "Found {} per-aggregate extension service(s) among the bean definitions: {}",
          interfaces.size(),
          interfaces.stream().map(Class::getName).toList());
    }
    return List.copyOf(interfaces);

  }

  /**
   * All classes annotated by {@link WorkflowService} of which the application registered a
   * bean.
   *
   * @param registry The bean definitions of the application
   * @return The workflow service classes, each of them once, no matter how many beans of
   *     it exist. Which bean serves a task is decided when the task is delivered: the bean
   *     factory is asked for the one bean of that class
   * @throws IllegalStateException If the annotation was found on a type which cannot
   *     serve tasks: an interface the bean's class implements, or an annotation of the
   *     application's own. Only this place knows both that the annotation was found and
   *     that the class itself does not carry it. Everything later reads it from the class
   *     alone.
   */
  private List<Class<?>> workflowServiceClassesOf(
      final BeanDefinitionRegistry registry) {

    final var workflowServiceClasses = new LinkedHashSet<Class<?>>();
    // the types which declared an annotation the bean's class does not carry, each with
    // the classes which brought it in. All of them are reported in one message. So a
    // developer does not learn about the second one only on the next start
    final var declaredElsewhere = new LinkedHashMap<Class<?>, List<Class<?>>>();
    for (final var beanName : registry.getBeanDefinitionNames()) {
      final var beanClass = beanClassOf(beanName);
      if (beanClass == null) {
        continue;
      }
      // findAnnotation walks the superclass chain. So a subclass of an annotated class is
      // a workflow service as well. That is what @Inherited promises the developer, and
      // the class registered is the SUBCLASS (see decision 32 in the repository's
      // DECISIONS.md)
      if (AnnotationUtils.findAnnotation(beanClass, WorkflowService.class) == null) {
        continue;
      }
      // but it walks further than @Inherited does: into implemented interfaces, and into
      // annotations of the application's own. Such a class passes as a workflow service
      // here, but it carries nothing the registry and the task scanner can read
      if (beanClass.getAnnotation(WorkflowService.class) == null) {
        final var declaringType = typeDeclaringTheAnnotation(beanClass);
        if (declaringType != null) {
          declaredElsewhere
              .computeIfAbsent(declaringType, type -> new LinkedList<>())
              .add(beanClass);
          continue;
        }
        // found somewhere this search does not reach. That says less than the refusal
        // would, but the registrar names the class it fails on
      }
      workflowServiceClasses.add(beanClass);
    }
    if (!declaredElsewhere.isEmpty()) {
      throw new IllegalStateException(refusalOf(declaredElsewhere));
    }
    return List.copyOf(workflowServiceClasses);

  }

  /**
   * The service interface one factory offers, read where the generic type really is. A
   * factory class which implements {@code AggregateServiceFactory<TheService>} carries it.
   * A {@code @Bean} method which declares the raw interface as its return type carries it
   * only in the method's signature. The bean DEFINITION knows that signature, and the
   * bean's class does not.
   *
   * @param beanName The name of the factory's bean definition
   * @param beanClass The class of that bean
   * @return The service interface, or <code>null</code> where the factory names none
   */
  private Class<?> serviceInterfaceOf(
      final String beanName,
      final Class<?> beanClass) {

    final var fromDefinition = beanFactory
        .getMergedBeanDefinition(beanName)
        .getResolvableType()
        .as(AggregateServiceFactory.class)
        .getGeneric(0)
        .resolve();
    return fromDefinition != null
        ? fromDefinition
        : ResolvableType
            .forClass(beanClass)
            .as(AggregateServiceFactory.class)
            .getGeneric(0)
            .resolve();

  }

  /**
   * The type which carries the annotation that a bean's class does not carry itself. It is
   * searched the way {@link AnnotationUtils#findAnnotation} searches: breadth first over
   * the implemented interfaces, the annotations present and the superclass. The
   * annotations present include an annotation of the application which composes
   * {@link WorkflowService}. The search stops at the first type which DECLARES the
   * annotation.
   *
   * @param beanClass The class of the bean
   * @return The interface or annotation to name in the message, or <code>null</code>
   *     where the annotation sits somewhere this search does not reach
   */
  private static Class<?> typeDeclaringTheAnnotation(
      final Class<?> beanClass) {

    final var visited = new LinkedHashSet<Class<?>>();
    final var pending = new LinkedList<Class<?>>();
    pending.add(beanClass);
    while (!pending.isEmpty()) {
      final var type = pending.removeFirst();
      if (!visited.add(type)) {
        continue;
      }
      if ((type != beanClass) && (type.getDeclaredAnnotation(WorkflowService.class) != null)) {
        return type;
      }
      pending.addAll(List.of(type.getInterfaces()));
      Stream
          .of(type.getDeclaredAnnotations())
          .map(Annotation::annotationType)
          .forEach(pending::add);
      if (type.getSuperclass() != null) {
        pending.add(type.getSuperclass());
      }
    }
    return null;

  }

  /**
   * The message ending the start, one paragraph per type which declared the annotation.
   *
   * @param declaredElsewhere The declaring types with the classes which brought them in
   * @return The message. Its wording is the core's. Quarkus refuses the same two shapes
   *     while an application is built, and it says the same thing
   */
  private static String refusalOf(
      final Map<Class<?>, List<Class<?>>> declaredElsewhere) {

    return declaredElsewhere
        .entrySet()
        .stream()
        .map(declaration -> {
          final var broughtInBy = declaration
              .getValue()
              .stream()
              .map(Class::getName)
              .sorted()
              .toList();
          return declaration.getKey().isAnnotation()
              ? WorkflowServiceBelongsOnAClass
                  .foundOnAnAnnotation(declaration.getKey().getName(), broughtInBy)
              : WorkflowServiceBelongsOnAClass
                  .foundOnAnInterface(declaration.getKey().getName(), broughtInBy);
        })
        .collect(Collectors.joining("\n"));

  }

  /**
   * The class of a bean, found without creating it: the declared class of an annotated
   * component, the return type of an {@code @Bean} method, and for a bean behind a proxy
   * the class the application wrote.
   *
   * @param beanName The name of the bean definition
   * @return The class, or {@code null} where it cannot be found: a factory bean which
   *     would have to be created to answer, or a class which an optional dependency left
   *     behind. Neither can be the workflow service of this run. A class which cannot be
   *     loaded cannot handle a task either.
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
