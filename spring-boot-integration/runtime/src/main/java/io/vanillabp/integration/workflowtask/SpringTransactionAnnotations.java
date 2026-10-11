package io.vanillabp.integration.workflowtask;

import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.workflowtask.TransactionAnnotationSpec;

/**
 * The transaction annotations Spring Boot honors. They are handed to the core's startup
 * check of <code>&#64;WorkflowTask</code> methods. The list was taken from
 * {@code AnnotationTransactionAttributeSource} of {@code spring-tx}. It registers a
 * parser for Spring's own annotation, {@code JtaTransactionAnnotationParser} for
 * {@code jakarta.transaction.Transactional} and {@code Ejb3TransactionAnnotationParser}
 * for {@code jakarta.ejb.TransactionAttribute}.
 * <p>
 * Spring Framework 6 dropped the annotation from before Jakarta. So it is listed as NOT
 * honored. An application which carries it over from Spring Boot 2 has no transaction
 * boundary where it believes it has one. That is worth a warning of its own.
 */
public final class SpringTransactionAnnotations {

  /**
   * Propagations which join a transaction that is already active, i.e. VanillaBP's. The
   * others keep the application's rollback inside the application's own transaction,
   * which is what it asked for. Spring's {@code NESTED} works on a savepoint and does not
   * mark the outer transaction either.
   */
  private static final Set<String> JOINING = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

  private SpringTransactionAnnotations() {
  }

  /**
   * The four annotations the startup check looks for on a <code>&#64;WorkflowTask</code>
   * method, built fresh on every call. The list also holds the annotations which this
   * platform does NOT honor. The check has to be able to say something about an
   * annotation without effect.
   * <p>
   * An annotation is named by its type name, and its members are read by reflection. So
   * an annotation missing from the classpath costs nothing here.
   *
   * @return The specs, in no particular order. The check matches by annotation type
   */
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
            // the annotation has no rollback rules at all. So the only way out is a
            // propagation of its own, or removing it
            null, null),
        new TransactionAnnotationSpec(
            "javax.transaction.Transactional", "value", JOINING, List.of("dontRollbackOn"), List.of(), List
                .of("rollbackOn"), List.of(), "@Transactional(dontRollbackOn = TaskException.class)", """
                    'javax.transaction.Transactional' is not honored since Spring Framework 6 moved to \
                    the Jakarta namespace; use 'jakarta.transaction.Transactional' where you really \
                    want a transaction."""));

  }

}
