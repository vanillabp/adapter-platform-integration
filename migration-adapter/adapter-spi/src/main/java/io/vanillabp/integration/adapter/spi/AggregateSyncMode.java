package io.vanillabp.integration.adapter.spi;

/**
 * The default of an adapter for sharing workflow-aggregate attributes with its
 * BPMS. It is the outermost level of the inheritance chain of
 * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS}. An annotation on the aggregate class
 * overrides it, an annotation on an attribute overrides that, and so on.
 * <p>
 * <b>The default is about what a MODEL may read, not about how an engine reads it.</b>
 * Every BPMS evaluates the expressions of its models itself, against the variables
 * VanillaBP pushed. This includes an embedded engine, although it could reach into the
 * application. So {@link #FULL} is the default of every adapter. If an application
 * annotates nothing, its models can read every attribute of their workflow aggregate, on
 * every BPMS. An application which shares less ({@code @NoSyncWithBPMS} on the class,
 * {@code @SyncWithBPMS} on what the models need) also gets the same behaviour everywhere.
 * <p>
 * {@link #NONE} is for an adapter whose BPMS gets its values in a completely different
 * way. Camunda 7 used it while an EL resolver read the aggregate live. Because of that,
 * models which worked on Camunda 7 failed on every remote BPMS. So the values are pushed
 * now like everywhere else. The live read is left only as a fallback for workflows which
 * were started before the upgrade and carry no such variables yet.
 * <p>
 * Why the mode an adapter defaults to is the adapter's answer and not the core's is decision 10 in
 * the repository's DECISIONS.md.
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
