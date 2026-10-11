package io.vanillabp.integration.test.workflowlevel;

/**
 * The aggregate of the workflow overriding the module's prioritized adapters (see
 * {@code WorkflowLevelOverrideTest}).
 * <p>
 * <i>Hint:</i> a classpath scan finds the {@code @WorkflowService} classes, so they leak
 * into every test of this Maven module. This package sorts AFTER the {@code sample} and
 * {@code sample2} packages. So tests which rely on the sample aggregate being processed
 * first (for example the outbox startup validation) are not affected.
 */
public class OverriddenAggregate {
}
