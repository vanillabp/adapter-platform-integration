package io.vanillabp.integration.test.utils.outbox;

import java.util.Optional;

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

    return constantIfTheClassCanBeLoaded(GRUELBOX_CONFIGURATION, "DEFAULT_OUTBOX_TABLE_NAME");

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
   * @throws IllegalStateException If the class cannot be loaded from the test classpath
   *         or does not declare that constant any more
   */
  static String constant(
      final String className,
      final String fieldName) {

    final Class<?> owner;
    try {
      owner = load(className);
    } catch (final ClassNotFoundException cannotBeLoaded) {
      throw new IllegalStateException(
          """
              The class '%s' cannot be loaded from the test classpath! The phase-two outbox reader \
              takes the name '%s' from there rather than writing it down again, so a test which \
              reads the outbox needs the VanillaBP module which brings that class, and the \
              libraries that class needs."""
              .formatted(className, fieldName), cannotBeLoaded);
    }
    return valueOf(owner, fieldName);

  }

  /**
   * Reads the same constant of a class which does not have to be there.
   *
   * @param className The class which declares the name
   * @param fieldName The constant holding it
   * @return What the constant holds, or nothing where the class cannot be loaded
   * @throws IllegalStateException If the class is there but does not declare that
   *         constant any more
   */
  static Optional<String> constantIfTheClassCanBeLoaded(
      final String className,
      final String fieldName) {

    try {
      return Optional.of(valueOf(load(className), fieldName));
    } catch (final ClassNotFoundException cannotBeLoaded) {
      return Optional.empty();
    }

  }

  /**
   * Loads a class of the platform.
   * <p>
   * A class which is there but cannot be linked is reported as a missing one, because it
   * means the same thing to a caller: the class lies on the test classpath while a
   * library it needs does not, and the class loader says so with a
   * {@link NoClassDefFoundError} rather than with a {@link ClassNotFoundException}. Only
   * that one family is caught. An error of another kind says that something else went
   * wrong, and the test has to see it.
   *
   * @param className The class to load
   * @return The class
   * @throws ClassNotFoundException If the class is not on the test classpath, or a
   *           library it needs is not
   */
  private static Class<?> load(
      final String className) throws ClassNotFoundException {

    try {
      return Class.forName(className);
    } catch (final LinkageError aLibraryOfThatClassIsMissing) {
      throw new ClassNotFoundException(className, aLibraryOfThatClassIsMissing);
    }

  }

  /**
   * @param owner The class which declares the name
   * @param fieldName The constant holding it
   * @return What the constant holds
   * @throws IllegalStateException If the class does not declare that constant any more
   */
  private static String valueOf(
      final Class<?> owner,
      final String fieldName) {

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
              .formatted(owner.getName(), fieldName), gone);
    }

  }

}
