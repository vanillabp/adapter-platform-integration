package io.vanillabp.integration.test.samples.nobeansubclass;

/**
 * Inherits the declaration, but carries no bean-defining annotation. So VanillaBP has a
 * workflow service which it can never get an instance of.
 */
public class SubclassWhichIsNoBean extends DeclarationOfANoBeanSubclass {

}
