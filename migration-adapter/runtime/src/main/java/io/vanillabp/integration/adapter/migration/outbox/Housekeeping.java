package io.vanillabp.integration.adapter.migration.outbox;

/**
 * What every outbox store of VanillaBP agrees on while it removes what it does not need
 * any more: the dispatched entries whose retention passed, and the payloads no entry
 * names.
 * <p>
 * The one thing kept here is how much of that a single run takes on. A run without a
 * ceiling holds a connection and a transaction for as long as the work lasts, and how
 * long that is depends on a table nobody measured; with a ceiling a run is short, and
 * a run which came back full says there is more, so the next one follows.
 */
public final class Housekeeping {

  private Housekeeping() {

  }

  /**
   * The most rows one housekeeping run removes from one store.
   * <p>
   * A thousand, because that is small enough to stay a short statement on every database
   * this runs on and large enough that a store which really has work does not need
   * hundreds of runs to get through it. A store with nothing to do deletes nothing and
   * pays one command for finding that out.
   */
  public static final int ROWS_PER_RUN = 1000;

}
