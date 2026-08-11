package io.vanillabp.integration.workflowtask;

import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.workflowtask.TransactionAnnotationSpec;

/**
 * The transaction annotations Spring Boot honors, handed to the core's startup check of
 * <code>&#64;WorkflowTask</code> methods (story 40b). Which annotations these are was
 * read off {@code AnnotationTransactionAttributeSource} of {@code spring-tx}: it
 * registers a parser for Spring's own annotation, {@code JtaTransactionAnnotationParser}
 * for {@code jakarta.transaction.Transactional} and {@code Ejb3TransactionAnnotationParser}
 * for {@code jakarta.ejb.TransactionAttribute}.
 * <p>
 * The pre-Jakarta annotation fell out with Spring Framework 6, so it is listed as NOT
 * honored: an application carrying it over from Spring Boot 2 has no transaction
 * boundary where it believes it has one, which is worth a warning of its own.
 */
public final class SpringTransactionAnnotations {

  /**
   * Propagations joining a transaction already active, i.e. VanillaBP's. The others keep
   * the application's rollback inside the application's own transaction, which is what
   * it asked for; Spring's {@code NESTED} works on a savepoint and does not mark the
   * outer transaction either.
   */
  private static final Set<String> JOINING = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

  private SpringTransactionAnnotations() {
  }

  public static List<TransactionAnnotationSpec> specs() {

    return List.of(
        new TransactionAnnotationSpec(
            "org.springframework.transaction.annotation.Transactional",
            // 'value' is the transaction manager qualifier on this annotation
            "propagation", JOINING, List.of("noRollbackFor"), List.of("noRollbackForClassName"), List
                .of("rollbackFor"), List
                    .of("rollbackForClassName"), "@Transactional(noRollbackFor = TaskException.class)", null),
        new TransactionAnnotationSpec(
            "jakarta.transaction.Transactional", "value", JOINING, List.of("dontRollbackOn"), List.of(), List
                .of("rollbackOn"), List.of(), "@Transactional(dontRollbackOn = TaskException.class)", null),
        new TransactionAnnotationSpec(
            "jakarta.ejb.TransactionAttribute", "value", JOINING, List.of(), List.of(), List.of(), List.of(),
            // the annotation has no rollback rules at all, so the only way out is a
            // propagation of its own or removing it
            null, null),
        new TransactionAnnotationSpec(
            "javax.transaction.Transactional", "value", JOINING, List.of("dontRollbackOn"), List.of(), List
                .of("rollbackOn"), List.of(), "@Transactional(dontRollbackOn = TaskException.class)", """
                    'javax.transaction.Transactional' is not honored since Spring Framework 6 moved to \
                    the Jakarta namespace; use 'jakarta.transaction.Transactional' where you really \
                    want a transaction."""));

  }

}
