package io.vanillabp.integration.adapter.spi;

/**
 * The default of an adapter for sharing workflow-aggregate attributes with its
 * BPMS - the outermost level of the inheritance chain of
 * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} (an aggregate class annotation
 * overrides it, an attribute annotation overrides that, and so on).
 * <p>
 * <b>The default is about what a MODEL may read, not about how an engine reads it.</b>
 * Every BPMS evaluates the expressions of its models itself,
 * against what VanillaBP pushed as variables - an embedded engine included, even though
 * it could reach into the application. So {@link #FULL} is the default of every adapter:
 * an application which annotates nothing gets models which can read every attribute of
 * their workflow aggregate, on every BPMS, and an application which minimizes
 * ({@code @NoSyncWithBPMS} on the class, {@code @SyncWithBPMS} on what the models need)
 * gets the same behaviour everywhere as well.
 * <p>
 * {@link #NONE} exists for an adapter whose BPMS is fed by a different mechanism
 * entirely. Camunda 7 used it while an EL resolver read the aggregate live: that made
 * models which work on Camunda 7 fail on every remote BPMS, so the resolver is gone and
 * the values are pushed like everywhere else.
 */
public enum AggregateSyncMode {

  /**
   * Nothing is shared unless the application asks for it explicitly
   * ({@code @SyncWithBPMS}).
   */
  NONE,

  /**
   * Everything is shared unless the application excludes it explicitly
   * ({@code @NoSyncWithBPMS}).
   */
  FULL

}
