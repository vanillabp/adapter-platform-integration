package org.springframework.transaction.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for Spring's transaction annotation, used to prove what Quarkus does with it
 * when the extension {@code quarkus-spring-tx} is NOT part of the application: nothing.
 * An application sharing modules with a Spring code base can carry the annotation
 * (through a plain {@code spring-tx} dependency) without that extension, and VanillaBP
 * must not fail its boot over an annotation that has no effect.
 * <p>
 * Members mirror {@code spring-tx} as far as the core's check reads them.
 */
@Target({
    ElementType.METHOD, ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {

  Propagation propagation() default Propagation.REQUIRED;

  Class<? extends Throwable>[] noRollbackFor() default {};

  Class<? extends Throwable>[] rollbackFor() default {};

}
