package io.vanillabp.integration.runtime.workflowtask;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.migration.workflowtask.TransactionAnnotationSpec;

/**
 * The transaction annotations Quarkus honors, handed to the core's startup check of
 * <code>&#64;WorkflowTask</code> methods (story 40b).
 * <p>
 * Quarkus honors {@code jakarta.transaction.Transactional} through Narayana. Spring's
 * annotation works ONLY with the extension {@code quarkus-spring-tx}, whose
 * {@code SpringTransactionalAnnotationsTransformer} maps it onto the JTA annotation at
 * build time, including the rollback rules ({@code noRollbackFor} and
 * {@code noRollbackForClassName} become {@code dontRollbackOn}, {@code rollbackFor} and
 * {@code rollbackForClassName} become {@code rollbackOn}); that extension rejects
 * {@code propagation = NESTED} with a build failure of its own. Without the extension the
 * annotation is inert, exactly like the pre-Jakarta one, so it is reported as not honored
 * rather than as a defect. Whether the extension is present is decided at build time, see
 * the deployment module's {@code TransactionAnnotationsBuildStepProcessor}.
 * <p>
 * {@code jakarta.ejb.TransactionAttribute} has no meaning on Quarkus at all, there is no
 * EJB container.
 */
public final class QuarkusTransactionAnnotations {

  /**
   * Propagations joining a transaction already active, i.e. VanillaBP's.
   */
  private static final Set<String> JOINING = Set.of("REQUIRED", "SUPPORTS", "MANDATORY");

  private QuarkusTransactionAnnotations() {
  }

  /**
   * @param springTransactionsHonored Whether the extension {@code quarkus-spring-tx} is
   *          part of the application, which makes Spring's annotation effective
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
