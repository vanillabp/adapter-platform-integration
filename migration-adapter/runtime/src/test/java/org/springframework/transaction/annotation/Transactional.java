package org.springframework.transaction.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for Spring's transaction annotation, carrying the members the core's check
 * reads. The core is plain Java and must not depend on Spring, and it matches
 * transaction annotations by their type NAME - so the stand-in proves the matching
 * exactly like the real annotation would, without dragging Spring onto the core's test
 * classpath. The real annotation is exercised by the acceptance tests of the Spring
 * Boot integration.
 * <p>
 * Members and defaults mirror {@code spring-tx}: {@code value} is the transaction
 * manager qualifier and NOT the propagation, which is why the check reads
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
