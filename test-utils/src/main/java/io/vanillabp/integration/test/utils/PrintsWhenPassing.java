package io.vanillabp.integration.test.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The exception to the rule that a passing test says nothing. A class with this
 * annotation prints in a green build on purpose, and
 * {@link TestClassConventions#testClassesWithoutOutputSuppression} leaves it alone.
 * <p>
 * The rule exists because output nobody reads hides the output somebody has to read. So
 * the exception is for the case where the printed line IS the result of the test, not a
 * side effect. VanillaBP has one such class, the coverage gate of each repository. It
 * measures both platforms, and its number is worth having in the log of every build, not
 * only of a red one.
 * <p>
 * The reason is part of the annotation, because a reviewer needs it to refuse the next
 * one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PrintsWhenPassing {

  /**
   * Why this class prints in a green build, in the author's own words.
   *
   * @return The reason a reviewer reads before allowing the next exemption
   */
  String value();

}
