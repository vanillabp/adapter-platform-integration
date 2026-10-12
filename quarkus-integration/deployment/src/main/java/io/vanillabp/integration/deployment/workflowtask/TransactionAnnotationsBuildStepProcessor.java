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
 * application. The core's startup check of <code>&#64;WorkflowTask</code> methods needs to
 * know this. Only the extension {@code quarkus-spring-tx} maps that annotation onto JTA.
 * Without it, the annotation does nothing. Reporting it as a defect would then fail a boot
 * because of an annotation without effect. This case is realistic: an application may share
 * modules with a Spring code base, and such applications are VanillaBP's migration
 * audience.
 * <p>
 * The extension registers no Quarkus capability. So its build-step class is the signal. It
 * is looked up on the DEPLOYMENT classpath. An extension's deployment artifact is there
 * exactly when the application depends on the extension. The presence of Spring's
 * annotation class alone is not enough. {@code quarkus-spring-tx} ships its own copy of it,
 * but so does a plain {@code spring-tx} dependency which shared application code pulls in.
 */
@Slf4j
public class TransactionAnnotationsBuildStepProcessor {

  private static final String SPRING_TX_EXTENSION_PROCESSOR = "io.quarkus.spring.tx.deployment.SpringTransactionalProcessor";

  /**
   * Quarkus builds this processor while it augments the application and calls the build step
   * below on it. Nothing else builds it, and the step keeps no state in it.
   */
  public TransactionAnnotationsBuildStepProcessor() {
  }

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
            // a record is final, so it cannot be a normal-scoped bean, which needs a
            // client proxy. The recorded value is immutable anyway
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
