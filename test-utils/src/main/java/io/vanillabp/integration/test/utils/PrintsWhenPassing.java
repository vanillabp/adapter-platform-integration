package io.vanillabp.integration.test.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The exemption from the rule that a passing test says nothing: a class carrying this
 * annotation prints on a green build on purpose, and
 * {@link TestClassConventions#testClassesWithoutOutputSuppression} leaves it alone.
 * <p>
 * The rule exists because output nobody reads hides the output somebody has to, so the
 * exemption is for the case where the printed line IS the result of the test rather than
 * a by-product of getting there. VanillaBP has one such class, the coverage gate of each
 * repository: it measures both platforms and the number it measured is worth having in
 * the log of every build, not only of a red one.
 * <p>
 * The reason is part of the annotation because that is what a reviewer needs in order to
 * refuse the next one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PrintsWhenPassing {

  /** Why this class prints on a green build, in the author's own words. */
  String value();

}
