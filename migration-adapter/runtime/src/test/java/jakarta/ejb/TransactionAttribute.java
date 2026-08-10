package jakarta.ejb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stand-in for the EJB transaction annotation, which Spring honors through its
 * {@code Ejb3TransactionAnnotationParser}. It has no rollback rules at all, so a
 * joining value is always a defect. See
 * {@link org.springframework.transaction.annotation.Transactional} for why the core's
 * tests use stand-ins.
 */
@Target({
    ElementType.METHOD, ElementType.TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface TransactionAttribute {

  TransactionAttributeType value() default TransactionAttributeType.REQUIRED;

}
