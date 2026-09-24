package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * What the guard around a guiding message sees, and what it leaves alone.
 * <p>
 * The shapes below are the ones the repository really holds. The first is the defect the
 * guard exists for, a text block pulled onto one line with its indentation still in it;
 * the others are the spaces somebody put there on purpose.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MessageConventionsTest {

  private static final Path SOURCE = Path.of("Sample.java");

  @Test
  @DisplayName("A text block line pulled onto the line before is found")
  public void aSentencePulledApartIsFound() {

    final var found = MessageConventions.messagesPulledApartIn(
        SOURCE,
        """
            throw new IllegalStateException(
                ""\"
                    This store was built without a table! Pass the name of             the table \\
                    to the constructor.""\");
            """);

    assertEquals(1, found.size(), found.toString());
    assertTrue(found.getFirst().text().contains("the name of             the table"), found.toString());
    assertEquals(2, found.getFirst().line(), "the line the text block starts at");

  }

  @Test
  @DisplayName("A text block whose indentation is only indentation passes")
  public void anOrdinaryTextBlockPasses() {

    final var found = MessageConventions.messagesPulledApartIn(
        SOURCE,
        """
            throw new IllegalStateException(
                ""\"
                    Several VanillaBP adapters were found in classpath:
                      %s
                    Name the order in which they are used.""\");
            """);

    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("A configuration sample whose comments line up passes")
  public void alignedCommentsOfASamplePass() {

    final var found = MessageConventions.messagesPulledApartIn(
        SOURCE,
        """
            final var sample = ""\"
                  vanillabp.adapters.%s.name-clash-avoidance: use-prefix   # VanillaBP prefixes them
                  vanillabp.adapters.%s.name-clash-avoidance: none         # yours are unique already""\";
            """);

    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("A plain literal is read as well")
  public void aPlainLiteralIsReadAsWell() {

    final var found = MessageConventions.messagesPulledApartIn(
        SOURCE, "  private static final String MESSAGE = \"the outbox  table is missing\";\n");

    assertEquals(1, found.size(), found.toString());
    assertEquals(1, found.getFirst().line());

  }

  @Test
  @DisplayName("A line break written as an escape starts a line, so the spaces behind it are an indentation")
  public void anEscapedLineBreakStartsALine() {

    final var found = MessageConventions.messagesPulledApartIn(
        SOURCE, "  private static final String LIST = \"these are missing:\\n  the table\";\n");

    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("A continuation which glues two words together is found")
  public void twoWordsGluedTogetherAreFound() {

    final var found = MessageConventions.messagesGluedTogetherIn(
        SOURCE,
        """
            throw new IllegalStateException(
                ""\"
                    This store was built without the name of the table\\
                    it reads.""\");
            """);

    assertEquals(1, found.size(), found.toString());
    assertEquals(3, found.getFirst().line(), "the line which is continued");
    assertTrue(
        MessageConventions.describeMessagesGluedTogether(found).contains("ONE space"),
        "and the way out");

  }

  @Test
  @DisplayName("A statement broken after a bracket is left alone")
  public void aLineBrokenAfterABracketPasses() {

    final var found = MessageConventions.messagesGluedTogetherIn(
        SOURCE,
        """
            private static final String CREATE = ""\"
                CREATE TABLE %s (\\
                ID VARCHAR(36) PRIMARY KEY)""\";
            """);

    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("The message of a failing check names the file, the line and the sentence")
  public void theFailingCheckSaysWhereToLook() {

    final var found = MessageConventions.messagesPulledApartIn(
        SOURCE, "  private static final String MESSAGE = \"the outbox  table is missing\";\n");

    final var description = MessageConventions.describeMessagesPulledApart(found);

    assertTrue(description.contains("Sample.java:1"), description);
    assertTrue(description.contains("the outbox  table is missing"), description);
    assertTrue(description.contains("backslash and ONE space"), description);

  }

}
