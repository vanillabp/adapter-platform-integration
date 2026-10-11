package io.vanillabp.integration.support;

import java.io.IOException;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.ClassUtils;

/**
 * Ends the boot of an application which has a workflow module but no BPMS adapter. The
 * message comes from VanillaBP itself.
 * <p>
 * On Spring Boot, a BPMS adapter brings VanillaBP's Spring Boot integration with it. So an
 * application which forgot the adapter has no VanillaBP runtime at all, and nothing could
 * speak up. But it does have this module. Every workflow module compiles against
 * <code>vanillabp-spring-boot-support</code>, so this check is on the classpath of exactly
 * the applications the message is meant for. Without it, the bean container answers
 * instead ("No qualifying bean of type ProcessService&lt;...&gt;"). That message mentions
 * neither VanillaBP nor an adapter.
 * <p>
 * A {@link BeanFactoryPostProcessor} runs before the first singleton is created. So it runs
 * before any <code>ProcessService</code> injection point is resolved. The neighbouring
 * cases keep their own messages. The integration itself reports an application which has
 * the integration but no adapter. An application without a workflow module is not
 * reported at all. It has nothing to run and boots as it always did.
 */
public class NoBpmsAdapterCheck implements BeanFactoryPostProcessor {

  /**
   * The autoconfiguration of VanillaBP's Spring Boot integration. It is there exactly when
   * the integration is on the classpath. On Spring Boot, that means a BPMS adapter brought it.
   */
  static final String INTEGRATION_AUTOCONFIGURATION = "io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration";

  /**
   * The marker file declaring a workflow module.
   */
  static final String METAINF_WORKFLOWMODULE = "META-INF/workflow-module";

  /**
   * Built by the bean method of {@link VanillaBpSupportAutoConfiguration}. There is
   * nothing to hand in. The check reads both facts it needs from the classpath when it
   * runs.
   */
  public NoBpmsAdapterCheck() {

  }

  @Override
  public void postProcessBeanFactory(
      final ConfigurableListableBeanFactory beanFactory) {

    final var classLoader = beanFactory.getBeanClassLoader() == null
        ? NoBpmsAdapterCheck.class.getClassLoader()
        : beanFactory.getBeanClassLoader();
    verify(
        ClassUtils.isPresent(INTEGRATION_AUTOCONFIGURATION, classLoader),
        anyWorkflowModuleInClasspath(classLoader));

  }

  /**
   * Whether the classpath declares at least one workflow module.
   *
   * @param classLoader The classloader of the application
   * @return Whether a workflow module was found
   */
  static boolean anyWorkflowModuleInClasspath(
      final ClassLoader classLoader) {

    try {
      return new PathMatchingResourcePatternResolver(classLoader)
          .getResources("classpath*:%s".formatted(METAINF_WORKFLOWMODULE)).length > 0;
    } catch (final IOException e) {
      // reporting a classpath which cannot be read is not the job of this check
      return false;
    }

  }

  /**
   * The rule itself, with both facts handed in. The tests use it that way, because
   * neither fact can change inside a running JVM.
   *
   * @param integrationPresent Whether VanillaBP's Spring Boot integration is in classpath
   * @param workflowModulePresent Whether a workflow module is in classpath
   * @throws IllegalStateException If a workflow module has no BPMS adapter to run it
   */
  static void verify(
      final boolean integrationPresent,
      final boolean workflowModulePresent) {

    if (integrationPresent) {
      // the integration reports a missing adapter itself, and it knows which adapters
      // were really loaded
      return;
    }
    if (!workflowModulePresent) {
      // an application without a workflow module has nothing to run. Then a missing
      // adapter is not a defect
      return;
    }
    throw new IllegalStateException(
        """
            No VanillaBP BPMS adapter found in classpath! A workflow module was found (a \
            '%s' marker file), but no adapter which could run its workflows - and on Spring Boot \
            an adapter is also what brings VanillaBP's Spring Boot integration, which is missing \
            as well.%s"""
            .formatted(METAINF_WORKFLOWMODULE, BpmsAdapters.artifactsToAdd()));

  }

}
