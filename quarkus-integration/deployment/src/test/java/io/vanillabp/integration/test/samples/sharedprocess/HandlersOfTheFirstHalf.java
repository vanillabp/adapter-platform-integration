package io.vanillabp.integration.test.samples.sharedprocess;

import jakarta.inject.Singleton;

/**
 * One of two classes which split the handlers of one BPMN process. Both are workflow
 * services by inheritance.
 */
@Singleton
public class HandlersOfTheFirstHalf extends SharedProcessBase {

}
