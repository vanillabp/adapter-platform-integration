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

}
