package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of what VanillaBP does with the records of processed task deliveries
 * (properties section <code>vanillabp.delivery</code>, overridable per workflow module as
 * <code>vanillabp.workflow-modules.&lt;id&gt;.delivery</code>). Adapter-INDEPENDENT: the
 * records of every BPMS live in the store of the workflow aggregate, so the question is
 * one of the application's data, not one of a BPMS.
 * <p>
 * The only setting is whether a workflow which ended releases its records - see
 * {@link #releaseOnWorkflowEnd}.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class DeliveryProperties {

  /**
   * Whether the records of a workflow are deleted the moment it ends, instead of waiting
   * for <code>vanillabp.outbox.retention</code> to pass (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}). The end of an
   * instance is the one statement after which nothing of it can be redelivered, so the
   * deletion is safe - and it is bound to the moment of the notification, which keeps the
   * records of a SECOND workflow on the same aggregate.
   * <p>
   * Defaults to <code>false</code>, and for two reasons: the end of a workflow is
   * reported only where the application asked for it, so switching this on makes every
   * deployed model pay for a listener respectively a worker; and an application may want
   * to keep the records for support. <code>null</code> in a workflow module's section
   * means "whatever is configured globally".
   */
  private Boolean releaseOnWorkflowEnd;

}
