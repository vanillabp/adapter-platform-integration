package io.vanillabp.integration.test.workflowlevel;

/**
 * The aggregate of the workflow overriding the module's prioritized adapters (see
 * {@code WorkflowLevelOverrideTest}).
 * <p>
 * <i>Hint:</i> {@code @WorkflowService} classes are found by a classpath scan, so
 * they leak into every test of this Maven module. This package sorts AFTER the
 * {@code sample}/{@code sample2} packages - tests relying on the sample aggregate
 * being processed first (e.g. the outbox startup validation) stay unaffected.
 */
public class OverriddenAggregate {
}
