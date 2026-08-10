package jakarta.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for the JTA transaction annotation honored by both platforms, carrying the
 * members the core's check reads. See
 * {@link org.springframework.transaction.annotation.Transactional} for why the core's
 * tests use stand-ins; the real annotation is exercised by the acceptance tests of
 * both platform integrations.
 */
@Target({
    ElementType.METHOD, ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {

  TxType value() default TxType.REQUIRED;

  Class[] rollbackOn() default {};

  Class[] dontRollbackOn() default {};

  enum TxType {

    REQUIRED,
    REQUIRES_NEW,
    MANDATORY,
    SUPPORTS,
    NOT_SUPPORTED,
    NEVER

  }

}
