package io.vanillabp.integration.runtime.workflowtask;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Records the build-time answer whether Spring transaction support is part of this
 * application (see {@link SpringTransactionSupport}).
 */
@Recorder
public class TransactionAnnotationsRecorder {

  /**
   * Quarkus builds the recorder while it builds the application and hands it to the build step
   * which looked for the extension. What that step calls here is not executed but written into
   * the bytecode of the boot, and it runs when the application starts.
   */
  public TransactionAnnotationsRecorder() {
  }

  /**
   * Carries a build-time answer into the runtime. Whether Spring's annotation has an effect
   * cannot be asked at runtime: what decides it is an extension which rewrites the annotations
   * while the application is built.
   *
   * @param honored Whether the extension {@code quarkus-spring-tx} was found at build
   *          time
   * @return The recorded answer
   */
  public RuntimeValue<SpringTransactionSupport> recordSpringTransactionSupport(
      final boolean honored) {

    return new RuntimeValue<>(new SpringTransactionSupport(honored));

  }

}
