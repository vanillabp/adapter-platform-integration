package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

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
 * Two settings live here: whether a workflow which ended releases its records
 * ({@link #releaseOnWorkflowEnd}), and how long a task may stay open before VanillaBP
 * says so ({@link #maxTaskAge}). Both are overridable per workflow module, and the age
 * additionally per workflow and per task, since how long a task may legitimately wait
 * is a property of that task rather than of the application.
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

  /**
   * How long a task may stay open before VanillaBP reports it, measured from the moment
   * the handler ran (see {@link io.vanillabp.integration.spi.TaskDelivery#recordedAt()}).
   * A task left open by a <code>&#64;TaskId</code> handler is a task the application
   * promised to complete later, and nothing else in VanillaBP ever asks whether that
   * promise was kept.
   * <p>
   * Defaults to {@value #DEFAULT_MAX_TASK_AGE_ISO} and to reporting only. An
   * asynchronous task waiting for a person or for a partner may legitimately run for
   * weeks, so a default which failed such a task would be worse than the leak it looks
   * for, while a default which never fires would leave the leak invisible. Thirty days
   * is long enough for the legitimate cases we know of and short enough to catch a task
   * nobody will ever complete.
   * <p>
   * <code>null</code> at a level means "whatever the next less specific level says";
   * {@link Duration#ZERO} switches the check off, which is how an application whose
   * tasks have no upper bound says so deliberately.
   */
  private Duration maxTaskAge;

  /**
   * The default of {@link #maxTaskAge} in ISO-8601 notation, for javadoc and messages.
   */
  public static final String DEFAULT_MAX_TASK_AGE_ISO = "P30D";

  /**
   * The default of {@link #maxTaskAge}: thirty days, report only.
   */
  public static final Duration DEFAULT_MAX_TASK_AGE = Duration.parse(DEFAULT_MAX_TASK_AGE_ISO);

}
