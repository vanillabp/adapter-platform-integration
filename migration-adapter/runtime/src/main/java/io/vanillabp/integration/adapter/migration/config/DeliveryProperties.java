package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

/**
 * The configuration of what VanillaBP does with the records of processed task deliveries
 * (properties section <code>vanillabp.delivery</code>, which can be overridden per workflow
 * module as <code>vanillabp.workflow-modules.&lt;id&gt;.delivery</code>). It does not
 * depend on the adapter. The records of every BPMS live in the store of the workflow
 * aggregate. So the question is about the application's data, not about a BPMS.
 * <p>
 * What lives here: whether a workflow which ended releases its records
 * ({@link #releaseOnWorkflowEnd}). How long a task may stay open before VanillaBP says so
 * ({@link #maxTaskAge}). Whether a delivery looks at the other tasks of its workflow
 * ({@link #checkOpenTasksOnDelivery}), and how many of them it probes
 * ({@link #maxOpenTasksChecked}). How long a record is kept ({@link #retention}). How long the
 * row about a started workflow is kept ({@link #workflowStartRetention}), and whether that
 * row outlives the period while its aggregate is still there
 * ({@link #keepWorkflowStartWhileAggregateExists}). The release can be overridden per
 * workflow module. The other three can also be overridden per workflow and per task. How
 * long a task may wait, and what one delivery may cost, are properties of that task, not of
 * the application.
 * <p>
 * The three settings about what is DELETED are the exception. They are only read globally.
 * For the retention that is decision 24 in the repository's DECISIONS.md. One cleanup per
 * store deletes the records. It is built with one period, and deletes by age across the
 * whole table or collection. So a value per workflow module would need a different deletion
 * in each of the four stores which VanillaBP ships, to mean anything at all. A property
 * which is bound per module and silently ignored there is worse than none.
 */
public class DeliveryProperties {

  /**
   * The empty section from which a configuration binder starts. Both platforms create the
   * object, and then write the keys which the application configured into it, one setter per
   * key. Every key of this section is <code>null</code> until somebody writes it, because
   * <code>null</code> means "this level says nothing".
   * <p>
   * It asks the builder for the values. That is how a field which gets a default one day
   * keeps it. The default is then on the builder as well, and this constructor reads it from
   * there.
   */
  public DeliveryProperties() {

    this(builder());

  }

  /**
   * The section below which an application writes these keys: <code>vanillabp.delivery</code>.
   * It is built from the prefix and not written out. So a message names the section the way
   * the application has to spell it.
   */
  public static final String SECTION = MigrationAdapterProperties.PREFIX
      + ".delivery";

  /**
   * Whether the records of a workflow are deleted at the moment it ends, instead of waiting
   * for {@link #retention} to pass (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}). After the end of
   * an instance nothing of it can be redelivered. So the deletion is safe. And it is tied to
   * the moment of the notification, which keeps the records of a SECOND workflow on the same
   * aggregate.
   * <p>
   * The default is <code>false</code>, for two reasons. The end of a workflow is only
   * reported where the application asked for it. So switching this on makes every deployed
   * model pay for a listener or a worker. And an application may want to keep the records
   * for support. <code>null</code> in the section of a workflow module means "whatever is
   * configured globally".
   */
  private Boolean releaseOnWorkflowEnd;

  /**
   * How long a task may stay open before VanillaBP reports it, measured from the moment the
   * handler ran (see {@link io.vanillabp.integration.spi.TaskDelivery#recordedAt()}). A task
   * which a <code>&#64;TaskId</code> handler left open is a task which the application
   * promised to complete later. Nothing else in VanillaBP ever asks whether that promise was
   * kept.
   * <p>
   * The default is {@value #DEFAULT_MAX_TASK_AGE_ISO}, and it only reports. An asynchronous
   * task which waits for a person or for a partner may run for weeks, and that is fine. A
   * default which failed such a task would be worse than the leak it looks for. And a default
   * which never fires would leave the leak invisible. Thirty days is long enough for the
   * valid cases we know of, and short enough to catch a task which nobody will ever
   * complete.
   * <p>
   * <code>null</code> at a level means "whatever the next less specific level says".
   * {@link Duration#ZERO} switches the check off. An application whose tasks have no upper
   * limit says so on purpose that way.
   */
  private Duration maxTaskAge;

  /**
   * The key of {@link #maxTaskAge}: <code>vanillabp.delivery.max-task-age</code>. It is a
   * constant, because the message about too many open tasks points to it.
   */
  public static final String MAX_TASK_AGE_PROPERTY = SECTION
      + ".max-task-age";

  /**
   * Whether a delivery looks at the other tasks which VanillaBP believes are open in the same
   * workflow of the BPMS. It asks that BPMS whether they still exist, and reports the ones
   * which are gone as canceled (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas}).
   * <p>
   * The default is <code>true</code>. Only a method which asked for the event with a
   * <code>&#64;TaskEvent</code> parameter is affected. On a BPMS which cannot say per element
   * what it took away, this is the only way such a method is ever called. An application
   * which does not want the new calls switches them off here.
   * <p>
   * An adapter which supplies no probe never asks anything, whatever this says.
   * <code>null</code> at a level means "whatever the next less specific level says".
   */
  private Boolean checkOpenTasksOnDelivery;

  /**
   * How many other open tasks one delivery probes at most (see
   * {@link #checkOpenTasksOnDelivery}). The oldest records come first. What is not reached
   * this time is reached at the next wake-up of that workflow.
   * <p>
   * The default is {@value #DEFAULT_MAX_OPEN_TASKS_CHECKED}. Each probe is one round trip to
   * the BPMS, on the thread of the delivery. Otherwise a workflow with very many open tasks
   * would hold an execution slot of the adapter for all of them at once. The number is a
   * limit, not a measurement. Zero switches the probing off, the same way as
   * {@link #checkOpenTasksOnDelivery}.
   * <p>
   * <code>null</code> at a level means "whatever the next less specific level says".
   */
  private Integer maxOpenTasksChecked;

  /**
   * How long the record of a processed task delivery is kept, counted from the last
   * redelivery it answered (see {@link io.vanillabp.integration.spi.TaskDeliveryLog#stillOpen}).
   * This setting is about CORRECTNESS. A delivery which arrives later than this finds no
   * record, and runs the <code>&#64;WorkflowTask</code> method a second time. So the period
   * has to cover the longest gap between a handler run and the last redelivery of that work.
   * That includes however long the application is stopped. A stopped application refreshes
   * nothing, and the first cleanup run after it starts deletes what expired in the
   * meantime.
   * <p>
   * Nothing here can limit that gap for you, and no adapter can either. An adapter knows the
   * interval at which it hands out unacknowledged work again: for Camunda 8 that is
   * <code>async-task-lock-renewal</code>, an hour by default. But that interval is not the
   * horizon. A check against it would pass in exactly the installations which are about to
   * run business code twice.
   * <p>
   * <code>null</code> means "whatever <code>vanillabp.outbox.retention</code> says". This
   * number lived there until it was split off. Decision 24 in the repository's DECISIONS.md
   * says why the two are two properties, and why the new one follows the old one where it is
   * not set.
   */
  private Duration retention;

  /**
   * The key of {@link #retention}: <code>vanillabp.delivery.retention</code>. It is a
   * constant, because several messages name it. And a test reads the key from here, instead
   * of writing it a second time.
   */
  public static final String RETENTION_PROPERTY = SECTION
      + ".retention";

  /**
   * How long the row about a started workflow is kept, counted from the start.
   * <p>
   * That row says to which workflow of the BPMS a workflow aggregate belongs. It is read for
   * as long as somebody may ask, and that is LONGER than the workflow runs. Changes to an
   * aggregate keep arriving after its workflow ended. They come from the application's own
   * code, and from whoever maintains the business data of a finished case. Each of them may
   * want to report the change to a cockpit. So this is not the retention of a delivery, which
   * may go as soon as nobody can repeat it. It is a period of its own.
   * <p>
   * The default is {@value #DEFAULT_WORKFLOW_START_RETENTION_ISO}. Nothing about correctness
   * depends on the number. When it has passed, the id of that workflow is not known any
   * more. So a report finds no id, and it is dropped without a word. The default is generous
   * and not tight, because nobody knows what an application does with a case it finished.
   * {@link #keepWorkflowStartWhileAggregateExists} is the exact answer for an installation
   * which wants one. {@link Duration#ZERO} keeps the rows for good. An application whose
   * aggregates live forever says so that way.
   * <p>
   * It is only read for the whole application, like {@link #retention}, and for the same
   * reason.
   */
  private Duration workflowStartRetention;

  /**
   * The key of {@link #workflowStartRetention}:
   * <code>vanillabp.delivery.workflow-start-retention</code>. It is a constant, because the
   * startup names it, and a test reads it from here.
   */
  public static final String WORKFLOW_START_RETENTION_PROPERTY = SECTION
      + ".workflow-start-retention";

  /**
   * Whether the row about a started workflow is kept past {@link #workflowStartRetention},
   * while the workflow aggregate which it names still exists.
   * <p>
   * A period is a guess, and this is the exact question. The row is read to say to which
   * workflow an aggregate belongs. So it is needed exactly as long as the application keeps
   * the aggregate. When this is switched on, the cleanup loads the aggregate by its id before
   * it deletes such a row. A persistence which answers with an aggregate keeps the row. A
   * persistence which cannot answer (a custom one which does not implement
   * <code>loadById</code>) keeps it as well, and the startup says so once.
   * <p>
   * The default is <code>false</code>. It costs one read of the application's own database
   * per expired row. A cleanup which does that without being asked would surprise an
   * installation with many short-lived workflows. The period alone is the rule on which an
   * installation can rely either way.
   * <p>
   * It is only read for the whole application, like the two periods above.
   */
  private Boolean keepWorkflowStartWhileAggregateExists;

  /**
   * The key of {@link #keepWorkflowStartWhileAggregateExists}:
   * <code>vanillabp.delivery.keep-workflow-start-while-aggregate-exists</code>.
   */
  public static final String KEEP_WORKFLOW_START_WHILE_AGGREGATE_EXISTS_PROPERTY = SECTION
      + ".keep-workflow-start-while-aggregate-exists";

  /**
   * Resolves the retention of delivery records: what this section says, or else the outbox
   * retention from which it was split off.
   *
   * @param delivery The <code>vanillabp.delivery</code> section or <code>null</code>
   * @param outboxRetention What <code>vanillabp.outbox.retention</code> resolves to
   * @return The period for which delivery records are kept
   */
  public static Duration resolveRetention(
      final DeliveryProperties delivery,
      final Duration outboxRetention) {

    return (delivery != null) && (delivery.getRetention() != null)
        ? delivery.getRetention()
        : outboxRetention;

  }

  /**
   * Resolves how long the row about a started workflow is kept: what this section says, or
   * thirty days where it says nothing.
   *
   * @param delivery The <code>vanillabp.delivery</code> section or <code>null</code>
   * @return The period a workflow-start row is kept
   */
  public static Duration resolveWorkflowStartRetention(
      final DeliveryProperties delivery) {

    return ((delivery == null) || (delivery.getWorkflowStartRetention() == null))
        ? DEFAULT_WORKFLOW_START_RETENTION
        : delivery.getWorkflowStartRetention();

  }

  /**
   * Resolves whether the row about a started workflow is kept past its period, while the
   * workflow aggregate which it names still exists.
   *
   * @param delivery The <code>vanillabp.delivery</code> section or <code>null</code>
   * @return Whether the cleanup asks the aggregate before it deletes such a row
   */
  public static boolean resolveKeepWorkflowStartWhileAggregateExists(
      final DeliveryProperties delivery) {

    return (delivery != null) && Boolean.TRUE.equals(delivery.getKeepWorkflowStartWhileAggregateExists());

  }

  /**
   * The default of {@link #maxTaskAge} in ISO-8601 notation, for javadoc and messages.
   */
  public static final String DEFAULT_MAX_TASK_AGE_ISO = "P30D";

  /**
   * The default of {@link #maxTaskAge}: thirty days, and it only reports.
   */
  public static final Duration DEFAULT_MAX_TASK_AGE = Duration.parse(DEFAULT_MAX_TASK_AGE_ISO);

  /**
   * The default of {@link #workflowStartRetention} in ISO-8601 notation, for javadoc and
   * messages.
   */
  public static final String DEFAULT_WORKFLOW_START_RETENTION_ISO = "P30D";

  /**
   * The default of {@link #workflowStartRetention}: thirty days after the workflow started.
   */
  public static final Duration DEFAULT_WORKFLOW_START_RETENTION = Duration
      .parse(DEFAULT_WORKFLOW_START_RETENTION_ISO);

  /**
   * The default of {@link #maxOpenTasksChecked}: ten probes per wake-up.
   */
  public static final int DEFAULT_MAX_OPEN_TASKS_CHECKED = 10;

  /**
   * The builder of {@link DeliveryProperties}. Its two type parameters are the class being
   * built and the builder itself. So a call inherited from a base class comes back as the
   * builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class DeliveryPropertiesBuilder<C extends DeliveryProperties, B extends DeliveryProperties.DeliveryPropertiesBuilder<C, B>> {

    /**
     * Whether the records of a workflow are deleted at the moment it ends, instead of waiting
     * for {@link #retention} to pass (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}).
     */
    private Boolean releaseOnWorkflowEnd;

    /**
     * How long a task may stay open before VanillaBP reports it, measured from the
     * moment the handler ran (see
     * {@link io.vanillabp.integration.spi.TaskDelivery#recordedAt()}).
     */
    private Duration maxTaskAge;

    /**
     * Whether a delivery looks at the other tasks which VanillaBP believes are open in the
     * same workflow of the BPMS. It asks that BPMS whether they still exist, and reports the
     * ones which are gone as canceled (see
     * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas}).
     */
    private Boolean checkOpenTasksOnDelivery;

    /**
     * How many other open tasks one delivery probes at most (see
     * {@link #checkOpenTasksOnDelivery}).
     */
    private Integer maxOpenTasksChecked;

    /**
     * How long the record of a processed task delivery is kept, counted from the last
     * redelivery it answered (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog#stillOpen}).
     */
    private Duration retention;

    /**
     * How long the row about a started workflow is kept, counted from the start (see
     * {@link DeliveryProperties#workflowStartRetention}).
     */
    private Duration workflowStartRetention;

    /**
     * Whether that row is kept past the period while its workflow aggregate still exists (see
     * {@link DeliveryProperties#keepWorkflowStartWhileAggregateExists}).
     */
    private Boolean keepWorkflowStartWhileAggregateExists;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one.
     * {@link DeliveryProperties#builder()} hands out the builder of this class.
     */
    public DeliveryPropertiesBuilder() {
    }

    /**
     * Whether the records of a workflow are deleted at the moment it ends, instead of waiting
     * for {@link #retention} to pass (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}).
     *
     * @param releaseOnWorkflowEnd The value of {@link #releaseOnWorkflowEnd}
     * @return This builder, so the calls chain
     */
    public B releaseOnWorkflowEnd(
        final Boolean releaseOnWorkflowEnd) {

      this.releaseOnWorkflowEnd = releaseOnWorkflowEnd;
      return self();

    }

    /**
     * How long a task may stay open before VanillaBP reports it, measured from the
     * moment the handler ran (see
     * {@link io.vanillabp.integration.spi.TaskDelivery#recordedAt()}).
     *
     * @param maxTaskAge The value of {@link #maxTaskAge}
     * @return This builder, so the calls chain
     */
    public B maxTaskAge(
        final Duration maxTaskAge) {

      this.maxTaskAge = maxTaskAge;
      return self();

    }

    /**
     * Whether a delivery looks at the other tasks which VanillaBP believes are open in the
     * same workflow of the BPMS. It asks that BPMS whether they still exist, and reports the
     * ones which are gone as canceled (see
     * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas}).
     *
     * @param checkOpenTasksOnDelivery The value of {@link #checkOpenTasksOnDelivery}
     * @return This builder, so the calls chain
     */
    public B checkOpenTasksOnDelivery(
        final Boolean checkOpenTasksOnDelivery) {

      this.checkOpenTasksOnDelivery = checkOpenTasksOnDelivery;
      return self();

    }

    /**
     * How many other open tasks one delivery probes at most (see
     * {@link #checkOpenTasksOnDelivery}).
     *
     * @param maxOpenTasksChecked The value of {@link #maxOpenTasksChecked}
     * @return This builder, so the calls chain
     */
    public B maxOpenTasksChecked(
        final Integer maxOpenTasksChecked) {

      this.maxOpenTasksChecked = maxOpenTasksChecked;
      return self();

    }

    /**
     * How long the record of a processed task delivery is kept, counted from the last
     * redelivery it answered (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog#stillOpen}).
     *
     * @param retention The value of {@link #retention}
     * @return This builder, so the calls chain
     */
    public B retention(
        final Duration retention) {

      this.retention = retention;
      return self();

    }

    /**
     * How long the row about a started workflow is kept, counted from the start (see
     * {@link DeliveryProperties#workflowStartRetention}).
     *
     * @param workflowStartRetention The value of
     *          {@link DeliveryProperties#workflowStartRetention}
     * @return This builder, so the calls chain
     */
    public B workflowStartRetention(
        final Duration workflowStartRetention) {

      this.workflowStartRetention = workflowStartRetention;
      return self();

    }

    /**
     * Whether that row is kept past the period while its workflow aggregate still exists (see
     * {@link DeliveryProperties#keepWorkflowStartWhileAggregateExists}).
     *
     * @param keepWorkflowStartWhileAggregateExists The value of
     *          {@link DeliveryProperties#keepWorkflowStartWhileAggregateExists}
     * @return This builder, so the calls chain
     */
    public B keepWorkflowStartWhileAggregateExists(
        final Boolean keepWorkflowStartWhileAggregateExists) {

      this.keepWorkflowStartWhileAggregateExists = keepWorkflowStartWhileAggregateExists;
      return self();

    }

    /**
     * The builder itself, typed as the builder of the subclass. Every method of the chain
     * returns it. That keeps a chain which started on a subclass builder at that subclass.
     *
     * @return This builder
     */
    protected abstract B self();

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    public abstract C build();

    /**
     * What this builder holds, for a message and for a debugger.
     *
     * @return The name of this builder and every value written into it
     */
    @Override
    public String toString() {

      return "DeliveryProperties.DeliveryPropertiesBuilder("
          + "releaseOnWorkflowEnd="
          + releaseOnWorkflowEnd
          + ", "
          + "maxTaskAge="
          + maxTaskAge
          + ", "
          + "checkOpenTasksOnDelivery="
          + checkOpenTasksOnDelivery
          + ", "
          + "maxOpenTasksChecked="
          + maxOpenTasksChecked
          + ", "
          + "retention="
          + retention
          + ", "
          + "workflowStartRetention="
          + workflowStartRetention
          + ", "
          + "keepWorkflowStartWhileAggregateExists="
          + keepWorkflowStartWhileAggregateExists
          + ")";

    }

  }

  /**
   * The builder which {@link #builder()} hands out: the one which builds
   * {@link DeliveryProperties} itself, and not a subclass of it.
   */
  private static final class DeliveryPropertiesBuilderImpl extends DeliveryProperties.DeliveryPropertiesBuilder<DeliveryProperties, DeliveryProperties.DeliveryPropertiesBuilderImpl> {

    /**
     * Nobody but {@link DeliveryProperties#builder()} builds one.
     */
    private DeliveryPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected DeliveryProperties.DeliveryPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public DeliveryProperties build() {

      return new DeliveryProperties(this);

    }

  }

  /**
   * Every builder of this class and of its subclasses builds through this. It is the one
   * place where the values of this class move from the builder into the object. So a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected DeliveryProperties(
      final DeliveryProperties.DeliveryPropertiesBuilder<?, ?> b) {

    this.releaseOnWorkflowEnd = b.releaseOnWorkflowEnd;
    this.maxTaskAge = b.maxTaskAge;
    this.checkOpenTasksOnDelivery = b.checkOpenTasksOnDelivery;
    this.maxOpenTasksChecked = b.maxOpenTasksChecked;
    this.retention = b.retention;
    this.workflowStartRetention = b.workflowStartRetention;
    this.keepWorkflowStartWhileAggregateExists = b.keepWorkflowStartWhileAggregateExists;

  }

  /**
   * A builder of {@link DeliveryProperties}. It is empty, except for the values which have a
   * default.
   *
   * @return The builder
   */
  public static DeliveryProperties.DeliveryPropertiesBuilder<?, ?> builder() {

    return new DeliveryProperties.DeliveryPropertiesBuilderImpl();

  }

  /**
   * Whether the records of a workflow are deleted at the moment it ends, instead of waiting
   * for {@link #retention} to pass (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}).
   *
   * @return The value of {@link #releaseOnWorkflowEnd}
   */
  public Boolean getReleaseOnWorkflowEnd() {

    return releaseOnWorkflowEnd;

  }

  /**
   * How long a task may stay open before VanillaBP reports it, measured from the moment
   * the handler ran (see
   * {@link io.vanillabp.integration.spi.TaskDelivery#recordedAt()}).
   *
   * @return The value of {@link #maxTaskAge}
   */
  public Duration getMaxTaskAge() {

    return maxTaskAge;

  }

  /**
   * Whether a delivery looks at the other tasks which VanillaBP believes are open in the same
   * workflow of the BPMS. It asks that BPMS whether they still exist, and reports the ones
   * which are gone as canceled (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas}).
   *
   * @return The value of {@link #checkOpenTasksOnDelivery}
   */
  public Boolean getCheckOpenTasksOnDelivery() {

    return checkOpenTasksOnDelivery;

  }

  /**
   * How many other open tasks one delivery probes at most (see
   * {@link #checkOpenTasksOnDelivery}).
   *
   * @return The value of {@link #maxOpenTasksChecked}
   */
  public Integer getMaxOpenTasksChecked() {

    return maxOpenTasksChecked;

  }

  /**
   * How long the record of a processed task delivery is kept, counted from the last
   * redelivery it answered (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#stillOpen}).
   *
   * @return The value of {@link #retention}
   */
  public Duration getRetention() {

    return retention;

  }

  /**
   * Whether the records of a workflow are deleted at the moment it ends, instead of waiting
   * for {@link #retention} to pass (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf}).
   *
   * @param releaseOnWorkflowEnd The value of {@link #releaseOnWorkflowEnd}
   */
  public void setReleaseOnWorkflowEnd(
      final Boolean releaseOnWorkflowEnd) {

    this.releaseOnWorkflowEnd = releaseOnWorkflowEnd;

  }

  /**
   * How long a task may stay open before VanillaBP reports it, measured from the moment
   * the handler ran (see
   * {@link io.vanillabp.integration.spi.TaskDelivery#recordedAt()}).
   *
   * @param maxTaskAge The value of {@link #maxTaskAge}
   */
  public void setMaxTaskAge(
      final Duration maxTaskAge) {

    this.maxTaskAge = maxTaskAge;

  }

  /**
   * Whether a delivery looks at the other tasks which VanillaBP believes are open in the same
   * workflow of the BPMS. It asks that BPMS whether they still exist, and reports the ones
   * which are gone as canceled (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas}).
   *
   * @param checkOpenTasksOnDelivery The value of {@link #checkOpenTasksOnDelivery}
   */
  public void setCheckOpenTasksOnDelivery(
      final Boolean checkOpenTasksOnDelivery) {

    this.checkOpenTasksOnDelivery = checkOpenTasksOnDelivery;

  }

  /**
   * How many other open tasks one delivery probes at most (see
   * {@link #checkOpenTasksOnDelivery}).
   *
   * @param maxOpenTasksChecked The value of {@link #maxOpenTasksChecked}
   */
  public void setMaxOpenTasksChecked(
      final Integer maxOpenTasksChecked) {

    this.maxOpenTasksChecked = maxOpenTasksChecked;

  }

  /**
   * How long the record of a processed task delivery is kept, counted from the last
   * redelivery it answered (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#stillOpen}).
   *
   * @param retention The value of {@link #retention}
   */
  public void setRetention(
      final Duration retention) {

    this.retention = retention;

  }

  /**
   * How long the row about a started workflow is kept, counted from the start (see
   * {@link #workflowStartRetention}).
   *
   * @return The value of {@link #workflowStartRetention}
   */
  public Duration getWorkflowStartRetention() {

    return workflowStartRetention;

  }

  /**
   * How long the row about a started workflow is kept, counted from the start (see
   * {@link #workflowStartRetention}).
   *
   * @param workflowStartRetention The value of {@link #workflowStartRetention}
   */
  public void setWorkflowStartRetention(
      final Duration workflowStartRetention) {

    this.workflowStartRetention = workflowStartRetention;

  }

  /**
   * Whether that row is kept past the period while its workflow aggregate still exists (see
   * {@link #keepWorkflowStartWhileAggregateExists}).
   *
   * @return The value of {@link #keepWorkflowStartWhileAggregateExists}
   */
  public Boolean getKeepWorkflowStartWhileAggregateExists() {

    return keepWorkflowStartWhileAggregateExists;

  }

  /**
   * Whether that row is kept past the period while its workflow aggregate still exists (see
   * {@link #keepWorkflowStartWhileAggregateExists}).
   *
   * @param keepWorkflowStartWhileAggregateExists The value of
   *          {@link #keepWorkflowStartWhileAggregateExists}
   */
  public void setKeepWorkflowStartWhileAggregateExists(
      final Boolean keepWorkflowStartWhileAggregateExists) {

    this.keepWorkflowStartWhileAggregateExists = keepWorkflowStartWhileAggregateExists;

  }

}
