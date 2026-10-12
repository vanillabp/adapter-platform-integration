package org.springframework.transaction.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for the transaction annotation of Spring. It carries the members which the check
 * of the core reads. The core is plain Java and must not depend on Spring. It matches
 * transaction annotations by their type NAME. So the stand-in proves the matching exactly
 * like the real annotation would, without dragging Spring onto the test classpath of the
 * core. The acceptance tests of the Spring Boot integration exercise the real annotation.
 * <p>
 * Members and defaults mirror {@code spring-tx}. {@code value} is the qualifier of the
 * transaction manager and NOT the propagation. That is why the check reads
 * {@code propagation} for this annotation.
 */
@Target({
    ElementType.METHOD, ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {

  String value() default "";

  Propagation propagation() default Propagation.REQUIRED;

  Class<? extends Throwable>[] rollbackFor() default {};

  String[] rollbackForClassName() default {};

  Class<? extends Throwable>[] noRollbackFor() default {};

  String[] noRollbackForClassName() default {};

}
