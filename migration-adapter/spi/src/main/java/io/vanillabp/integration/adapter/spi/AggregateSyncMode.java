package io.vanillabp.integration.adapter.spi;

/**
 * The default of an adapter for sharing workflow-aggregate attributes with its
 * BPMS - the outermost level of the inheritance chain of
 * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} (an aggregate class annotation
 * overrides it, an attribute annotation overrides that, and so on).
 * <p>
 * <b>The default belongs to the adapter because the mechanics do:</b>
 * <ul>
 * <li>an EMBEDDED engine reads the aggregate LIVE while evaluating BPMN
 * expressions - it needs nothing pushed, so its default is {@link #NONE} and
 * whatever IS shared is written as pure context information for operators (e.g.
 * Camunda 7's Cockpit);</li>
 * <li>a REMOTE engine can only see what VanillaBP pushes as process variables -
 * its default is {@link #FULL}, otherwise no BPMN expression could work.</li>
 * </ul>
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
