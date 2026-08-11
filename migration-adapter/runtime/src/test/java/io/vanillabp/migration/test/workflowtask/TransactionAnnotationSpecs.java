package io.vanillabp.migration.test.workflowtask;

import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.workflowtask.TransactionAnnotationSpec;

/**
 * The specs the core's tests judge against. They mirror what the two platform
 * integrations supply, and they belong to the tests rather than to the core: which
 * annotations exist and whether a platform honors them is the platform's knowledge (see
 * {@code SpringTransactionAnnotations} and {@code QuarkusTransactionAnnotations}).
 * <p>
 * The annotation classes themselves are the stand-ins of this module's test sources (see
 * {@link org.springframework.transaction.annotation.Transactional}), since the core must
 * not depend on either platform and the check matches by type name anyway.
 */
final class TransactionAnnotationSpecs {

  private static final Set<String> JOINING = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

  private TransactionAnnotationSpecs() {
  }

  /**
   * A platform honoring all three transaction annotations, like Spring Boot does, with
   * the pre-Jakarta one reported as ineffective.
   */
  static List<TransactionAnnotationSpec> ofATypicalPlatform() {

    return List.of(
        springTransactional(null),
        jakartaTransactional(),
        ejbTransactionAttribute(null),
        obsoleteJavaxTransactional());

  }

  /**
   * A platform NOT honoring Spring's annotation and the EJB one, like Quarkus without the
   * extension {@code quarkus-spring-tx}.
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
