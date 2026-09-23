package io.vanillabp.integration.test.utils.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The lookup which fetches a name from the class declaring it.
 * <p>
 * The classes of the platform are not on the classpath of this module, so this test
 * uses classes of its own. Two cases matter. A name which is gone has to be reported
 * with a message telling the next person where to follow the rename. And a class which
 * needs a library nobody brought counts as a class which is not there, because the
 * outbox it configures is not there either.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoOutboxNamesTest {

  /**
   * A class in the shape the lookup expects: a public constant holding a name.
   */
  public static final class AStoreOfItsOwn {

    public static final String DEFAULT_TABLE_NAME = "A_TABLE";

  }

  /**
   * A class in the shape of the one which configures the gruelbox outbox: it names a
   * table, and it needs a library which an application does not have to bring. Loading
   * it fails with a {@link NoClassDefFoundError}, the way the class loader fails on the
   * real one in a repository whose test classpath carries the Spring Boot integration
   * without the gruelbox library.
   * <p>
   * The error is thrown here instead of arriving from a class loader because this test
   * cannot take a library off its own classpath. What it stands in for is the whole
   * point of the case: the error is not a {@link ClassNotFoundException}, so a lookup
   * which only expects that one lets it through.
   */
  public static final class AConfigurationWithoutItsLibrary {

    public static final String DEFAULT_OUTBOX_TABLE_NAME = nameOfTheTableTheLibraryWrites();

    private static String nameOfTheTableTheLibraryWrites() {

      throw new NoClassDefFoundError("com/gruelbox/transactionoutbox/TransactionOutbox");

    }

  }

  @Test
  @DisplayName("A name is read from the class which declares it")
  public void aNameIsReadFromTheClassWhichDeclaresIt() {

    assertEquals("A_TABLE", PhaseTwoOutboxNames.constant(AStoreOfItsOwn.class.getName(), "DEFAULT_TABLE_NAME"));

  }

  @Test
  @DisplayName("A class which is not on the test classpath is reported with the name it was asked for")
  public void aClassWhichIsMissingIsReported() {

    final var missing = assertThrows(
        IllegalStateException.class,
        () -> PhaseTwoOutboxNames.constant("io.vanillabp.NoSuchStore", "DEFAULT_TABLE_NAME"));

    assertTrue(missing.getMessage().contains("io.vanillabp.NoSuchStore"), missing.getMessage());
    assertTrue(missing.getMessage().contains("DEFAULT_TABLE_NAME"), missing.getMessage());

  }

  @Test
  @DisplayName("A class whose library is missing is reported like a class which is not there at all")
  public void aClassWhoseLibraryIsMissingIsReported() {

    final var cannotBeLoaded = assertThrows(
        IllegalStateException.class,
        () -> PhaseTwoOutboxNames
            .constant(AConfigurationWithoutItsLibrary.class.getName(), "DEFAULT_OUTBOX_TABLE_NAME"));

    assertTrue(cannotBeLoaded.getMessage().contains("AConfigurationWithoutItsLibrary"), cannotBeLoaded.getMessage());
    assertInstanceOf(NoClassDefFoundError.class, cannotBeLoaded.getCause().getCause());

  }

  @Test
  @DisplayName("A name of a class whose library is missing is no name, and asking for it throws nothing")
  public void aNameOfAClassWhoseLibraryIsMissingIsNoName() {

    assertEquals(
        Optional.empty(),
        PhaseTwoOutboxNames
            .constantIfTheClassCanBeLoaded(
                AConfigurationWithoutItsLibrary.class.getName(), "DEFAULT_OUTBOX_TABLE_NAME"));

  }

  @Test
  @DisplayName("A name of a class which is there is read even where the class did not have to be")
  public void aNameOfAClassWhichIsThereIsRead() {

    assertEquals(
        Optional.of("A_TABLE"),
        PhaseTwoOutboxNames.constantIfTheClassCanBeLoaded(AStoreOfItsOwn.class.getName(), "DEFAULT_TABLE_NAME"));

  }

  @Test
  @DisplayName("A constant which is gone points at the one file which has to follow the rename")
  public void aConstantWhichIsGonePointsAtTheFileToFollow() {

    final var renamed = assertThrows(
        IllegalStateException.class,
        () -> PhaseTwoOutboxNames.constant(AStoreOfItsOwn.class.getName(), "TABLE_NAME_OF_YESTERDAY"));

    assertTrue(renamed.getMessage().contains("TABLE_NAME_OF_YESTERDAY"), renamed.getMessage());
    assertTrue(renamed.getMessage().contains("PhaseTwoOutboxNames"), renamed.getMessage());

  }

}
