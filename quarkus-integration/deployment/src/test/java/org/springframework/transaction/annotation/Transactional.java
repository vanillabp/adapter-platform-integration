package org.springframework.transaction.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for Spring's transaction annotation. It proves what Quarkus does with it when the
 * extension {@code quarkus-spring-tx} is NOT part of the application: nothing. An application
 * which shares modules with a Spring code base can carry the annotation without that
 * extension, through a plain {@code spring-tx} dependency. VanillaBP must not fail its boot
 * because of an annotation which has no effect.
 * <p>
 * The members copy those of {@code spring-tx}, as far as the core's check reads them.
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
