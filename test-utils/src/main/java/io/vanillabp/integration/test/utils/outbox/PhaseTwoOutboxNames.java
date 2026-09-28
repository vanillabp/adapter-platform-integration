package io.vanillabp.integration.test.utils.outbox;

import java.util.Optional;

import io.vanillabp.integration.test.utils.ConstantOfAnotherModule;

/**
 * The names of the phase-two outbox, taken from the classes which declare them.
 * <p>
 * A test which reads the outbox needs the name of a table and the values its state
 * column holds. Writing those names into the test is what this class is here to
 * prevent: each one is read from the class which owns it, so a rename in the platform
 * is followed here and nowhere else. {@link ConstantOfAnotherModule} does the reading
 * and says why it happens by reflection.
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
   * test can run that store at all, which is a question about the library and not about
   * VanillaBP: the store left this repository and lives in an artifact of its own.
   */
  private static final String GRUELBOX_LIBRARY = "com.gruelbox.transactionoutbox.TransactionOutbox";

  /**
   * The table a gruelbox-based store writes. The name belongs to the library: it is what
   * gruelbox defaults to and the only table its schema migration ever creates, so it is
   * written out here rather than read from a class this repository does not carry.
   */
  private static final String GRUELBOX_OUTBOX_TABLE = "TXNO_OUTBOX";

  private PhaseTwoOutboxNames() {
  }

  /**
   * @return The table VanillaBP's own JDBC outbox writes, as long as the application
   *         did not configure a name of its own
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
   * The same name, asked for by a test which does not know whether the application under
   * test can run that outbox at all. An application without the library writes no gruelbox
   * table, so there is no name to ask the database about, and a leftover table of that name
   * would then belong to somebody else.
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
   * @return The table the payload of a call lies in, which is the same table for both
   *         outboxes
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
