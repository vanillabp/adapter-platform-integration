package io.vanillabp.migration.test.workflowtask;

import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.workflowtask.TransactionAnnotationSpec;

/**
 * The specs against which the tests of the core judge. They mirror what the two platform
 * integrations supply. They belong to the tests and not to the core. Which annotations
 * exist, and whether a platform honors them, is knowledge of the platform (see
 * {@code SpringTransactionAnnotations} and {@code QuarkusTransactionAnnotations}).
 * <p>
 * The annotation classes themselves are the stand-ins of the test sources of this module
 * (see {@link org.springframework.transaction.annotation.Transactional}). The core must not
 * depend on either platform, and the check matches by type name anyway.
 */
final class TransactionAnnotationSpecs {

  private static final Set<String> JOINING = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

  private TransactionAnnotationSpecs() {
  }

  /**
   * A platform which honors all three transaction annotations, like Spring Boot does. It
   * reports the one from before Jakarta as ineffective.
   */
  static List<TransactionAnnotationSpec> ofATypicalPlatform() {

    return List.of(
        springTransactional(null),
        jakartaTransactional(),
        ejbTransactionAttribute(null),
        obsoleteJavaxTransactional());

  }

  /**
   * A platform which does NOT honor the annotation of Spring and the EJB one, like Quarkus
   * without the extension {@code quarkus-spring-tx}.
   */
  static List<TransactionAnnotationSpec> ofAPlatformWithoutSpringSupport() {

    return List.of(
        springTransactional("this platform does not map Spring's annotation onto its transactions."),
        jakartaTransactional(),
        ejbTransactionAttribute("this platform has no EJB container."),
        obsoleteJavaxTransactional());

  }

  private static TransactionAnnotationSpec springTransactional(
      final String notHonoredHint) {

    return new TransactionAnnotationSpec(
        "org.springframework.transaction.annotation.Transactional", "propagation", JOINING, List
            .of("noRollbackFor"), List.of("noRollbackForClassName"), List.of("rollbackFor"), List
                .of("rollbackForClassName"), "@Transactional(noRollbackFor = TaskException.class)", notHonoredHint);

  }

  private static TransactionAnnotationSpec jakartaTransactional() {

    return new TransactionAnnotationSpec(
        "jakarta.transaction.Transactional", "value", JOINING, List.of("dontRollbackOn"), List.of(), List
            .of("rollbackOn"), List.of(), "@Transactional(dontRollbackOn = TaskException.class)", null);

  }

  private static TransactionAnnotationSpec ejbTransactionAttribute(
      final String notHonoredHint) {

    return new TransactionAnnotationSpec(
        "jakarta.ejb.TransactionAttribute", "value", JOINING, List.of(), List.of(), List.of(), List
            .of(), null, notHonoredHint);

  }

  private static TransactionAnnotationSpec obsoleteJavaxTransactional() {

    return new TransactionAnnotationSpec(
        "javax.transaction.Transactional", "value", JOINING, List.of("dontRollbackOn"), List.of(), List
            .of("rollbackOn"), List
                .of(), "@Transactional(dontRollbackOn = TaskException.class)", "the annotation is not honored since the move to the Jakarta namespace.");

  }

}
