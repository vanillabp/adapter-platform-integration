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
   * @param honored Whether the extension {@code quarkus-spring-tx} was found at build
   *          time
   * @return The recorded answer
   */
  public RuntimeValue<SpringTransactionSupport> recordSpringTransactionSupport(
      final boolean honored) {

    return new RuntimeValue<>(new SpringTransactionSupport(honored));

  }

}
