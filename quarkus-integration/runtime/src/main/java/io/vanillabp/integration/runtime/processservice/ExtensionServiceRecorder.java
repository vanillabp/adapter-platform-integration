package io.vanillabp.integration.runtime.processservice;

import java.lang.reflect.Type;
import java.util.function.Function;

import io.quarkus.arc.Arc;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vanillabp.integration.adapter.migration.processservice.ExtensionAggregateServiceContext;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;
import jakarta.enterprise.inject.Any;

/**
 * Builds an extension's service for one aggregate when the application first injects it.
 * The build step registered one synthetic bean per pair of service interface and workflow
 * aggregate. Such a bean is created with what this recorder returns.
 * <p>
 * Everything is looked up when the bean is created, not injected. The extension's factory
 * and the process service of the aggregate both exist by then. Looking them up here makes
 * injecting the service OPTIONAL. An application which never asks for it never runs any of
 * this.
 * <p>
 * Both lookups name the type ARGUMENT they mean. It tells two beans of one raw type apart.
 * And ArC finds a bean of a parameterized type only when it is asked for that
 * parameterized type.
 */
@Recorder
public class ExtensionServiceRecorder {

  /**
   * Quarkus creates the recorder while it builds the application. It hands the recorder to
   * the build step which found the extension's services. What that step calls here does not
   * run then. It is written into the bytecode of the boot and runs when the application
   * starts.
   */
  public ExtensionServiceRecorder() {
  }

  /**
   * Hands one synthetic bean the function which creates it. Both types arrive as NAMES. The
   * build step reads them from the index of the application's classes. The runtime loads
   * them. If a name has gone missing, {@code loadClass} below says so.
   *
   * @param serviceInterfaceName The service interface the extension offers
   * @param workflowAggregateClassName The workflow aggregate this bean serves
   * @return How to build the bean
   */
  public Function<SyntheticCreationalContext<Object>, Object> extensionService(
      final String serviceInterfaceName,
      final String workflowAggregateClassName) {

    return context -> {

      final var serviceInterface = loadClass(serviceInterfaceName);
      final var workflowAggregateClass = loadClass(workflowAggregateClassName);

      final var factory = Arc
          .container()
          .<AggregateServiceFactory<?>>listAll(
              parameterized(AggregateServiceFactory.class, serviceInterface),
              Any.Literal.INSTANCE)
          .stream()
          .map(io.quarkus.arc.InstanceHandle::get)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              """
                  No AggregateServiceFactory of this application builds a '%s'! It was one while the \
                  application was built, so its bean disappeared afterwards - check the conditions on \
                  the extension's producer."""
                  .formatted(serviceInterfaceName)));

      final var processService = Arc
          .container()
          .<ProcessServiceBaseCdiBean<?>>listAll(
              parameterized(ProcessServiceBaseCdiBean.class, workflowAggregateClass),
              Any.Literal.INSTANCE)
          .stream()
          .map(io.quarkus.arc.InstanceHandle::get)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException(
              """
                  No ProcessService of the workflow aggregate '%s' exists, so the '%s' of an extension \
                  cannot be built either!"""
                  .formatted(workflowAggregateClassName, serviceInterfaceName)));

      @SuppressWarnings({
          "unchecked", "rawtypes"
      })
      final var serviceContext = new ExtensionAggregateServiceContext(
          processService.getMigrationProcessService(), Arc
              .container()
              .instance(ExtensionHandlers.class)
              .get(), Arc
                  .container()
                  .instance(WorkflowElection.class)
                  .get());

      return factory.createService(serviceContext);

    };

  }

  /**
   * The type <code>raw&lt;argument&gt;</code>. A bean of a generic type is registered under
   * such a type.
   */
  private static Type parameterized(
      final Class<?> raw,
      final Class<?> argument) {

    return new java.lang.reflect.ParameterizedType() {

      @Override
      public Type[] getActualTypeArguments() {

        return new Type[]{
            argument
        };

      }

      @Override
      public Type getRawType() {

        return raw;

      }

      @Override
      public Type getOwnerType() {

        return null;

      }

    };

  }

  private static Class<?> loadClass(
      final String className) {

    try {
      return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(
          "The class '%s' was on the classpath while the application was built and is not any more!"
              .formatted(className), e);
    }

  }

}
