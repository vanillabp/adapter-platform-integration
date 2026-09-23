package io.vanillabp.integration.test.utils.outbox;

/**
 * The names of the phase-two outbox, taken from the classes which declare them.
 * <p>
 * A test which reads the outbox needs the name of a table and the values its state
 * column holds. Writing those names into the test is what this class is here to
 * prevent: each one is read from the class which owns it, so a rename in the platform
 * is followed here and nowhere else.
 * <p>
 * The names are read by reflection, and that is not a matter of taste. The modules
 * which declare them use this module in their own tests, so a dependency on them is a
 * cycle Maven refuses. What reflection costs is the moment the rename shows up: not at
 * compile time, but at the first call of {@link #constant(String, String)}, with a
 * message which names the class and the constant it looked for.
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
   * The Spring Boot configuration which builds the gruelbox outbox, and the name of the
   * table gruelbox writes.
   */
  private static final String GRUELBOX_CONFIGURATION = "io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration";

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
   * @return The table gruelbox writes, which is the outbox a Spring Boot application
   *         with JPA runs
   */
  static String gruelboxOutboxTable() {

    return constant(GRUELBOX_CONFIGURATION, "DEFAULT_OUTBOX_TABLE_NAME");

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
   * Reads a public String constant of a class which is not on this module's compile
   * path.
   *
   * @param className The class which declares the name
   * @param fieldName The constant holding it
   * @return What the constant holds
   * @throws IllegalStateException If the class is missing from the test classpath or
   *         does not declare that constant any more
   */
  static String constant(
      final String className,
      final String fieldName) {

    final Class<?> owner;
    try {
      owner = Class.forName(className);
    } catch (final ClassNotFoundException notOnTheClasspath) {
      throw new IllegalStateException(
          """
              The class '%s' is not on the test classpath! The phase-two outbox reader takes the \
              name '%s' from there rather than writing it down again, so a test which reads the \
              outbox needs the VanillaBP module which brings that class."""
              .formatted(className, fieldName), notOnTheClasspath);
    }
    try {
      return (String) owner
          .getField(fieldName)
          .get(null);
    } catch (final NoSuchFieldException | IllegalAccessException gone) {
      throw new IllegalStateException(
          """
              The class '%s' does not offer the constant '%s' any more! The phase-two outbox \
              reader reads the names of the outbox there, so follow the rename in \
              PhaseTwoOutboxNames - it is the one place which holds them."""
              .formatted(className, fieldName), gone);
    }

  }

}
