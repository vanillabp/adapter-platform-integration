package io.vanillabp.integration.test.utils;

import java.util.Optional;

/**
 * A public String constant of a class which is not on the compile path of this module,
 * read by its name.
 * <p>
 * The readers of this module take the names of the tables VanillaBP writes from the
 * classes which declare them. So a test never writes down such a name. When the platform
 * renames one, only one file follows, not every repository which tests against a BPMS.
 * <p>
 * The price is reflection, and that is not a matter of taste. The modules which declare
 * those names use this module in their own tests. A dependency on them would be a cycle,
 * and Maven refuses cycles. With reflection, a rename shows up later: not at compile
 * time, but at the first lookup. The message then names the class, the constant and the
 * file which has to follow the rename.
 */
public final class ConstantOfAnotherModule {

  private ConstantOfAnotherModule() {
  }

  /**
   * Reads a constant of a class the test classpath has to bring.
   *
   * @param namesOfTheReader The class of this module which holds the name, so a failed
   *          lookup can point at the one file which follows a rename
   * @param className The class which declares the name
   * @param fieldName The constant holding it
   * @return What the constant holds
   * @throws IllegalStateException If the class cannot be loaded from the test classpath,
   *         or does not declare that constant any more
   */
  public static String of(
      final Class<?> namesOfTheReader,
      final String className,
      final String fieldName) {

    final Class<?> owner;
    try {
      owner = load(className);
    } catch (final ClassNotFoundException cannotBeLoaded) {
      throw new IllegalStateException(
          """
              The class '%s' cannot be loaded from the test classpath! %s takes the name '%s' from \
              there rather than writing it down again, so a test needs the VanillaBP module which \
              brings that class, and the libraries that class needs."""
              .formatted(className, namesOfTheReader.getSimpleName(), fieldName), cannotBeLoaded);
    }
    return valueOf(namesOfTheReader, owner, fieldName);

  }

  /**
   * Reads a constant of a class which does not have to be there at all. An example is a
   * VanillaBP class which configures an optional library. It belongs to a platform
   * integration and is on the test classpath of every repository which tests against a
   * BPMS. The library it needs is only there where the application under test uses it.
   *
   * @param namesOfTheReader The class of this module which holds the name, so a failed
   *          lookup can point at the one file which follows a rename
   * @param className The class which declares the name
   * @param fieldName The constant holding it
   * @return What the constant holds, or nothing where the class cannot be loaded
   * @throws IllegalStateException If the class is there but does not declare that
   *         constant any more
   */
  public static Optional<String> ofAClassWhichMayBeMissing(
      final Class<?> namesOfTheReader,
      final String className,
      final String fieldName) {

    try {
      return Optional.of(valueOf(namesOfTheReader, load(className), fieldName));
    } catch (final ClassNotFoundException cannotBeLoaded) {
      return Optional.empty();
    }

  }

  /**
   * Loads a class of another module.
   * <p>
   * A class which is there but cannot be linked is reported as missing. For a caller it
   * means the same thing. The class is on the test classpath, but a library it needs is
   * not. The class loader says so with a {@link NoClassDefFoundError}, not with a
   * {@link ClassNotFoundException}. Only that one family is caught. An error of another
   * kind says that something else went wrong, and the test has to see it.
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
   * @param namesOfTheReader The class of this module which holds the name
   * @param owner The class which declares the name
   * @param fieldName The constant holding it
   * @return What the constant holds
   * @throws IllegalStateException If the class does not declare that constant any more
   */
  private static String valueOf(
      final Class<?> namesOfTheReader,
      final Class<?> owner,
      final String fieldName) {

    try {
      return (String) owner
          .getField(fieldName)
          .get(null);
    } catch (final NoSuchFieldException | IllegalAccessException gone) {
      throw new IllegalStateException(
          """
              The class '%s' does not offer the constant '%s' any more! Follow the rename in %s - \
              that is the one file holding this name."""
              .formatted(owner.getName(), fieldName, namesOfTheReader.getSimpleName()), gone);
    }

  }

}
