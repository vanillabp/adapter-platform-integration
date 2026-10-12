package io.vanillabp.integration.test.utils.outbox;

import java.util.Optional;

import io.vanillabp.integration.test.utils.ConstantOfAnotherModule;

/**
 * The names of the phase-two outbox, taken from the classes which declare them.
 * <p>
 * A test which reads the outbox needs the name of a table and the values of its state
 * column. This class keeps those names out of the tests. Each one is read from the
 * class which owns it. So when the platform renames one, only this class follows.
 * {@link ConstantOfAnotherModule} does the reading and says why it uses reflection.
 */
final class PhaseTwoOutboxNames {

  /**
   * The store which writes VanillaBP's own outbox table, and the name of that table.
   */
  private static final String OUTBOX_STORE = "io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore";

  /**
   * The store which writes the payloads of both outbox tables, and the name of the
   * table they lie in.
   */
  private static final String PAYLOAD_STORE = "io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore";

  /**
   * The dispatcher of VanillaBP's own outbox table, and the three values it writes into
   * the state column.
   */
  private static final String OUTBOX_DISPATCHER = "io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher";

  /**
   * The class every gruelbox-based store needs. It answers whether the application under
   * test can run that store at all. This is a question about the library, not about
   * VanillaBP, because the store left this repository and lives in an artifact of its own.
   */
  private static final String GRUELBOX_LIBRARY = "com.gruelbox.transactionoutbox.TransactionOutbox";

  /**
   * The table a gruelbox-based store writes. The name belongs to the library. It is
   * gruelbox's default and the only table its schema migration ever creates. So it is
   * written out here, and not read from a class this repository does not have.
   */
  private static final String GRUELBOX_OUTBOX_TABLE = "TXNO_OUTBOX";

  private PhaseTwoOutboxNames() {
  }

  /**
   * @return The table VanillaBP's own JDBC outbox writes when the application did not
   *         configure a name of its own
   */
  static String vanillaBpOutboxTable() {

    return constant(OUTBOX_STORE, "DEFAULT_TABLE_NAME");

  }

  /**
   * @return The table a gruelbox-based store writes
   */
  static String gruelboxOutboxTable() {

    return GRUELBOX_OUTBOX_TABLE;

  }

  /**
   * The same name, for a test which does not know whether the application under test can
   * run that outbox at all. An application without the library writes no gruelbox table.
   * So there is no name to ask the database about. A leftover table of that name would
   * belong to somebody else.
   *
   * @return The table a gruelbox-based store writes, or nothing where this application
   *         cannot run one
   */
  static Optional<String> gruelboxOutboxTableIfThisApplicationCanRunIt() {

    try {
      Class.forName(GRUELBOX_LIBRARY, false, PhaseTwoOutboxNames.class.getClassLoader());
      return Optional.of(GRUELBOX_OUTBOX_TABLE);
    } catch (final ClassNotFoundException libraryIsNotThere) {
      return Optional.empty();
    }

  }

  /**
   * @return The table the payload of a call lies in. Both outboxes use the same table
   */
  static String payloadTable() {

    return constant(PAYLOAD_STORE, "DEFAULT_TABLE_NAME");

  }

  /**
   * @return What the state column of a waiting entry holds
   */
  static String waitingState() {

    return constant(OUTBOX_DISPATCHER, "STATUS_OPEN");

  }

  /**
   * @return What the state column of a dispatched entry holds
   */
  static String dispatchedState() {

    return constant(OUTBOX_DISPATCHER, "STATUS_DONE");

  }

  /**
   * @return What the state column of a blocked entry holds
   */
  static String blockedState() {

    return constant(OUTBOX_DISPATCHER, "STATUS_BLOCKED");

  }

  /**
   * @param className The class which declares the name
   * @param fieldName The constant holding it
   * @return What the constant holds
   */
  private static String constant(
      final String className,
      final String fieldName) {

    return ConstantOfAnotherModule.of(PhaseTwoOutboxNames.class, className, fieldName);

  }

}
