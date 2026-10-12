package io.vanillabp.extension.sample;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a parameter of a {@link SampleNote} method which receives what happened to the
 * element. It has the shape of the Business Cockpit's <code>&#64;DetailsEvent</code>.
 * <p>
 * This extension adds binders for two kinds of parameters. The first one is recognized by
 * its TYPE ({@link SampleNoteDetails}), this one by its annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface SampleNoteEvent {
}
