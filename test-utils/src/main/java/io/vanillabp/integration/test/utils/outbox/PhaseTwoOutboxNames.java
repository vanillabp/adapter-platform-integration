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
   * The same name, asked for by a test which does not know whether the application
   * under test can run that outbox at all. The gruelbox library is optional, while the
   * VanillaBP class which configures it belongs to the Spring Boot integration and is
   * therefore on the test classpath of every repository testing against a BPMS. An
   * application without the library cannot load that class, and it writes no gruelbox
   * table either, so there is no name to ask the database about.
   *
   * @return The table gruelbox writes, or nothing where this application cannot run the
   *         gruelbox outbox
   */
  static Optional<String> gruelboxOutboxTableIfThisApplicationCanRunIt() {

    return ConstantOfAnotherModule
        .ofAClassWhichMayBeMissing(PhaseTwoOutboxNames.class, GRUELBOX_CONFIGURATION, "DEFAULT_OUTBOX_TABLE_NAME");

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
