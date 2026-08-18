package io.vanillabp.integration.test.utils;

import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;

public class AssertException {

  public static Consumer<Throwable> exceptionHavingMessage(
      final Class<?> expected,
      final String message) {

    return t -> {
      assert (t != null);
      Throwable i = t;

      boolean found;
      for (found = false; i != null; i = i.getCause()) {
        if (i.getClass().equals(expected)) {
          found = true;
          break;
        }
      }

      Assertions.assertTrue(
          found,
          "Build failed with a wrong exception, expected '%s' but got '%s'"
              .formatted(expected.getName(), t.getClass().getName()));
      Assertions.assertEquals(message, i.getMessage());
    };

  }

  /**
   * Asserts an exception of the given type whose message CONTAINS every fragment given -
   * for messages which guide a developer and therefore grow a sentence now and then, while
   * the parts a test cares about (the defect named, the remedy named) have to stay.
   *
   * @param expected The exception type expected somewhere in the chain of causes
   * @param fragments The fragments the message has to contain
   * @return The assertion
   */
  public static Consumer<Throwable> exceptionHavingMessageContaining(
      final Class<?> expected,
      final String... fragments) {

    return t -> {
      assert (t != null);
      Throwable i = t;

      boolean found;
      for (found = false; i != null; i = i.getCause()) {
        if (i.getClass().equals(expected)) {
          found = true;
          break;
        }
      }

      Assertions.assertTrue(
          found,
          "Build failed with a wrong exception, expected '%s' but got '%s'"
              .formatted(expected.getName(), t.getClass().getName()));
      final var message = i.getMessage();
      for (final var fragment : fragments) {
        Assertions.assertTrue(
            (message != null) && message.contains(fragment),
            "Expected the message to contain '%s' but it was: %s".formatted(fragment, message));
      }
    };

  }

}
