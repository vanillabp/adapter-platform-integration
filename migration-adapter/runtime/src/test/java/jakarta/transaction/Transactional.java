package jakarta.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for the JTA transaction annotation which both platforms honor. It carries the
 * members which the check of the core reads. See
 * {@link org.springframework.transaction.annotation.Transactional} for why the tests of the
 * core use stand-ins. The acceptance tests of both platform integrations exercise the real
 * annotation.
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
