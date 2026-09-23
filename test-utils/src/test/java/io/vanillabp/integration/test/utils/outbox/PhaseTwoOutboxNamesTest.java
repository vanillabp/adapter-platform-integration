package io.vanillabp.integration.test.utils.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The lookup which fetches a name from the class declaring it.
 * <p>
 * The classes of the platform are not on the classpath of this module, so this test
 * uses one of its own. What the lookup does with a name which is gone is the case that
 * matters: the message is what tells the next person where to follow a rename.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoOutboxNamesTest {

  /**
   * A class in the shape the lookup expects: a public constant holding a name.
   */
  public static final class AStoreOfItsOwn {

    public static final String DEFAULT_TABLE_NAME = "A_TABLE";

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
  @DisplayName("A constant which is gone points at the one file which has to follow the rename")
  public void aConstantWhichIsGonePointsAtTheFileToFollow() {

    final var renamed = assertThrows(
        IllegalStateException.class,
        () -> PhaseTwoOutboxNames.constant(AStoreOfItsOwn.class.getName(), "TABLE_NAME_OF_YESTERDAY"));

    assertTrue(renamed.getMessage().contains("TABLE_NAME_OF_YESTERDAY"), renamed.getMessage());
    assertTrue(renamed.getMessage().contains("PhaseTwoOutboxNames"), renamed.getMessage());

  }

}
