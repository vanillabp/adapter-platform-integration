package javax.transaction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for the pre-Jakarta transaction annotation. Neither Spring Framework 7 nor
 * Quarkus 3 honors it, so it declares no transaction boundary at all - the check warns
 * about it instead of failing the startup. An application coming from Spring Boot 2
 * carries it without noticing that it stopped working.
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
