package io.vanillabp.integration.runtime.workflowtask;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.workflowtask.TransactionAnnotationSpec;

/**
 * The transaction annotations which Quarkus honors. They go to the core's startup check of
 * <code>&#64;WorkflowTask</code> methods.
 * <p>
 * Quarkus honors {@code jakarta.transaction.Transactional} through Narayana. Spring's
 * annotation works ONLY with the extension {@code quarkus-spring-tx}. Its
 * {@code SpringTransactionalAnnotationsTransformer} maps Spring's annotation onto the JTA
 * annotation at build time. This includes the rollback rules: {@code noRollbackFor} and
 * {@code noRollbackForClassName} become {@code dontRollbackOn}, and {@code rollbackFor} and
 * {@code rollbackForClassName} become {@code rollbackOn}. The extension fails the build for
 * {@code propagation = NESTED} by itself. Without the extension, the annotation does
 * nothing, just like the old {@code javax} one. So it is reported as not honored, not as a
 * defect. Whether the extension is there is decided at build time. See the deployment
 * module's {@code TransactionAnnotationsBuildStepProcessor}.
 * <p>
 * {@code jakarta.ejb.TransactionAttribute} means nothing on Quarkus, because there is no EJB
 * container.
 */
public final class QuarkusTransactionAnnotations {

  /**
   * The propagations which join a transaction that is already active, that is VanillaBP's.
   */
  private static final Set<String> JOINING = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

  private QuarkusTransactionAnnotations() {
  }

  /**
   * The specs which the core checks a <code>&#64;WorkflowTask</code> method against. All four
   * annotations are listed, also the ones without an effect on Quarkus. A method which carries
   * one of those is told what it gets instead of being skipped. Only this way does its author
   * learn about it.
   *
   * @param springTransactionsHonored Whether the extension {@code quarkus-spring-tx} is
   *          part of the application. It makes Spring's annotation work
   * @return The specs of all transaction annotations known on Quarkus
   */
  public static List<TransactionAnnotationSpec> specs(
      final boolean springTransactionsHonored) {

    final var specs = new LinkedList<TransactionAnnotationSpec>();
    specs.add(new TransactionAnnotationSpec(
        "jakarta.transaction.Transactional", "value", JOINING, List.of("dontRollbackOn"), List.of(), List
            .of("rollbackOn"), List.of(), "@Transactional(dontRollbackOn = TaskException.class)", null));
    specs.add(new TransactionAnnotationSpec(
        "org.springframework.transaction.annotation.Transactional", "propagation", JOINING, List
            .of("noRollbackFor"), List.of("noRollbackForClassName"), List.of("rollbackFor"), List.of(
                "rollbackForClassName"), "@Transactional(noRollbackFor = TaskException.class)", springTransactionsHonored
                    ? null
                    : """
                        Spring's '@Transactional' needs the extension 'quarkus-spring-tx' to have any \
                        effect on Quarkus; without it Quarkus starts no transaction for it."""));
    specs.add(new TransactionAnnotationSpec(
        "jakarta.ejb.TransactionAttribute", "value", JOINING, List.of(), List.of(), List.of(), List
            .of(), null, "Quarkus has no EJB container, so 'jakarta.ejb.TransactionAttribute' has no effect."));
    specs.add(new TransactionAnnotationSpec(
        "javax.transaction.Transactional", "value", JOINING, List.of("dontRollbackOn"), List.of(), List
            .of("rollbackOn"), List.of(), "@Transactional(dontRollbackOn = TaskException.class)", """
                'javax.transaction.Transactional' is not honored since Quarkus 3 moved to the Jakarta \
                namespace; use 'jakarta.transaction.Transactional' where you really want a \
                transaction."""));
    return specs;

  }

}
