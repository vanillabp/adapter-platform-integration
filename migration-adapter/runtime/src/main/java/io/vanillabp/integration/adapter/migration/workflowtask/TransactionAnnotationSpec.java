package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;
import java.util.Set;

import io.vanillabp.spi.service.TaskException;

/**
 * One transaction annotation as the PLATFORM sees it. Whether an annotation creates a
 * transaction boundary at all, and which of its members carry the propagation and the
 * rollback rules, is knowledge of the platform integration: Spring honors its own
 * annotation, the JTA one and the EJB one, Quarkus honors the JTA one plus Spring's if
 * the extension {@code quarkus-spring-tx} is present, and both platforms stopped
 * honoring the pre-Jakarta annotation. The core owns the algorithm judging them (see
 * {@link ApplicationTransactionCheck}) and nothing else, which keeps it free of both
 * platforms and lets a platform correct itself when its support changes.
 * <p>
 * Annotations are matched by type NAME and their members are read reflectively, so a
 * spec may name an annotation that is not on the classpath at all.
 *
 * @param annotationType Fully qualified name of the annotation
 * @param propagationMember Name of the member carrying the propagation (Spring:
 *          {@code propagation}, JTA and EJB: {@code value})
 * @param joiningPropagations Names of the propagation values joining a transaction
 *          already active, i.e. VanillaBP's
 * @param noRollbackForMembers Members listing exception CLASSES excluded from the
 *          rollback
 * @param noRollbackForClassNameMembers Members listing exception class NAME patterns
 *          excluded from the rollback
 * @param rollbackForMembers Members listing exception CLASSES causing a rollback
 * @param rollbackForClassNameMembers Members listing exception class NAME patterns
 *          causing a rollback
 * @param remedy The annotation as it has to be written to exclude a
 *          {@link TaskException}, or <code>null</code> if the annotation has no
 *          rollback rules at all
 * @param notHonoredHint Why this platform does NOT honor the annotation, which makes it
 *          harmless but usually not what the developer intended; <code>null</code> for
 *          an annotation the platform honors
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
   * Whether this platform honors the annotation, i.e. whether it creates a transaction
   * boundary at all.
   *
   * @return <code>true</code> if the annotation has an effect on this platform
   */
  public boolean honored() {

    return notHonoredHint == null;

  }

}
