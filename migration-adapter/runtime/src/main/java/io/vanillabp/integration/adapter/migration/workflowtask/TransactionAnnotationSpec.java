package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;
import java.util.Set;

import io.vanillabp.spi.service.TaskException;

/**
 * One transaction annotation as the PLATFORM sees it. The platform integration knows
 * whether an annotation creates a transaction boundary at all, and which of its members hold
 * the propagation and the rollback rules. Spring honors its own annotation, the JTA one and
 * the EJB one. Quarkus honors the JTA one, plus Spring's if the extension
 * {@code quarkus-spring-tx} is present. Both platforms stopped honoring the annotation from
 * before Jakarta. The core owns the algorithm which judges them (see
 * {@link ApplicationTransactionCheck}) and nothing else. That keeps the core free of both
 * platforms, and lets a platform correct itself when its support changes.
 * <p>
 * Annotations are matched by type NAME, and their members are read by reflection. So a spec
 * may name an annotation which is not on the classpath at all.
 *
 * @param annotationType Fully qualified name of the annotation
 * @param propagationMember Name of the member which holds the propagation (Spring:
 *          {@code propagation}, JTA and EJB: {@code value})
 * @param joiningPropagations Names of the propagation values which join a transaction that
 *          is already active, which here is VanillaBP's
 * @param noRollbackForMembers Members which list exception CLASSES excluded from the
 *          rollback
 * @param noRollbackForClassNameMembers Members which list patterns of exception class NAMES
 *          excluded from the rollback
 * @param rollbackForMembers Members which list exception CLASSES that cause a rollback
 * @param rollbackForClassNameMembers Members which list patterns of exception class NAMES
 *          that cause a rollback
 * @param remedy How the annotation has to be written to exclude a {@link TaskException}, or
 *          <code>null</code> if the annotation has no rollback rules at all
 * @param notHonoredHint Why this platform does NOT honor the annotation. That makes it
 *          harmless, but usually not what the developer intended. <code>null</code> for an
 *          annotation which the platform honors
 */
public record TransactionAnnotationSpec(
                                        String annotationType,
                                        String propagationMember,
                                        Set<String> joiningPropagations,
                                        List<String> noRollbackForMembers,
                                        List<String> noRollbackForClassNameMembers,
                                        List<String> rollbackForMembers,
                                        List<String> rollbackForClassNameMembers,
                                        String remedy,
                                        String notHonoredHint) {

  /**
   * Whether this platform honors the annotation, which means whether it creates a
   * transaction boundary at all.
   *
   * @return <code>true</code> if the annotation has an effect on this platform
   */
  public boolean honored() {

    return notHonoredHint == null;

  }

}
