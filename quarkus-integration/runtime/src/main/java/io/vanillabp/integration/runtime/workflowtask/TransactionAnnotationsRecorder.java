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
   * Quarkus creates the recorder while it builds the application. It hands the recorder to the
   * build step which looked for the extension. What that step calls here does not run then.
   * It is written into the bytecode of the boot and runs when the application starts.
   */
  public TransactionAnnotationsRecorder() {
  }

  /**
   * Carries a build-time answer into the runtime. Whether Spring's annotation has an effect
   * cannot be found out at runtime. An extension decides it, and that extension rewrites the
   * annotations while the application is built.
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
