package io.vanillabp.bpmsdouble;

/**
 * The names the BPMS double answers to in an application's configuration. They are the
 * same on both platforms, and this artifact promises them. A test which writes
 * <code>type: dummy</code> or switches on {@link #PROPERTY_AT_LEAST_ONCE_DELIVERY} keeps
 * working in later releases (see the module's readme).
 */
public final class DummyAdapter {

  /**
   * The adapter type an application configures to get this double, at
   * <code>vanillabp.adapters.&lt;id&gt;.type</code>. Any number of adapter ids may have
   * it. This is how a test plays the migration from one BPMS to another.
   */
  public static final String ADAPTER_TYPE = "dummy";

  /**
   * Makes the double report the task delivery of a BPMS which repeats a task when it did
   * not learn its outcome. Then the tests of the delivery record and of a
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} see the behaviour they are
   * written for.
   */
  public static final String PROPERTY_AT_LEAST_ONCE_DELIVERY = "dummy-adapter.at-least-once-delivery";

  /**
   * Makes the double READ the workflow aggregate in phase two. An adapter of a remote BPMS
   * does this, because it builds the variables it sends from the aggregate. Off by
   * default, because most test doubles of
   * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} implement only save.
   */
  public static final String PROPERTY_READ_AGGREGATE_IN_PHASE_TWO = "dummy-adapter.read-aggregate-in-phase-two";

  private DummyAdapter() {
  }

}
