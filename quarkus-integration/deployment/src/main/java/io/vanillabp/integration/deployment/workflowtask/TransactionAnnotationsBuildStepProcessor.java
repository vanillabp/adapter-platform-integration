package io.vanillabp.integration.deployment.workflowtask;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.vanillabp.integration.runtime.workflowtask.SpringTransactionSupport;
import io.vanillabp.integration.runtime.workflowtask.TransactionAnnotationsRecorder;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides at build time whether Spring's {@code @Transactional} has an effect on this
 * application, which the core's startup check of <code>&#64;WorkflowTask</code> methods
 * needs to know (story 40b): only the extension {@code quarkus-spring-tx} maps that
 * annotation onto JTA. Without it the annotation is inert, and reporting it as a defect
 * would fail a boot over an annotation that does nothing - a realistic case for an
 * application sharing modules with a Spring code base, which is VanillaBP's migration
 * audience.
 * <p>
 * The extension registers no Quarkus capability, so its build-step class is the signal.
 * It is looked up on the DEPLOYMENT classpath, where an extension's deployment artifact
 * is present exactly when the application depends on the extension. The presence of
 * Spring's annotation class alone would not do: {@code quarkus-spring-tx} ships its own
 * copy of it, but so does a plain {@code spring-tx} dependency dragged in by shared
 * application code.
 */
@Slf4j
public class TransactionAnnotationsBuildStepProcessor {

  private static final String SPRING_TX_EXTENSION_PROCESSOR = "io.quarkus.spring.tx.deployment.SpringTransactionalProcessor";

  /**
   * @param recorder The recorder building the runtime object
   * @param syntheticBeans Producer used to register the answer as a bean
   */
  @Record(ExecutionTime.RUNTIME_INIT)
  @BuildStep
  void detectSpringTransactionSupport(
      final TransactionAnnotationsRecorder recorder,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {

    final var honored = isExtensionPresent();
    if (honored) {
      log.debug(
          "Spring transaction support (quarkus-spring-tx) found: Spring's @Transactional is checked "
              + "like the JTA one on @WorkflowTask methods");
    }

    syntheticBeans
        .produce(SyntheticBeanBuildItem
            .configure(SpringTransactionSupport.class)
            // a record is final, so a normal-scoped bean (which needs a client proxy)
            // is out; the recorded value is immutable anyway
            .scope(Singleton.class)
            .runtimeValue(recorder.recordSpringTransactionSupport(honored))
            .setRuntimeInit()
            .unremovable()
            .done());

  }

  private static boolean isExtensionPresent() {

    try {
      Class.forName(
          SPRING_TX_EXTENSION_PROCESSOR,
          false,
          Thread
              .currentThread()
              .getContextClassLoader());
      return true;
    } catch (final ClassNotFoundException | LinkageError e) {
      return false;
    }

  }

}
