package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.LastFailure;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The text an outbox entry keeps about its last failed attempt: what goes into it, what is
 * left out, and how it is cut. The stores write it as it comes from here, so these cases hold
 * for the JDBC table and both MongoDB collections alike.
 */
@ExtendWith(SuppressOutputExtension.class)
public class LastFailureTest {

  @Test
  @DisplayName("A failure without a cause is its class and its message")
  public void aFailureIsItsClassAndMessage() {

    assertEquals(
        "java.lang.IllegalStateException: the BPMS refused",
        LastFailure.of(new IllegalStateException("the BPMS refused")));

  }

  @Test
  @DisplayName("A failure without a message is its class")
  public void aFailureWithoutAMessageIsItsClass() {

    assertEquals("java.lang.NullPointerException", LastFailure.of(new NullPointerException()));

  }

  @Test
  @DisplayName("Each cause is added with its class and message")
  public void theCausesFollow() {

    final var failure = new IllegalStateException(
        "dispatch failed", new IllegalArgumentException("unknown message 'Order paid'"));

    assertEquals(
        "java.lang.IllegalStateException: dispatch failed; caused by "
            + "java.lang.IllegalArgumentException: unknown message 'Order paid'",
        LastFailure.of(failure));

  }

  @Test
  @DisplayName("A cause which the wrapping message repeats is not written twice")
  public void aRepeatedCauseIsLeftOut() {

    // new RuntimeException(cause) takes the cause's toString() as its message
    final var failure = new RuntimeException(new IllegalStateException("the BPMS refused"));

    assertEquals(
        "java.lang.RuntimeException: java.lang.IllegalStateException: the BPMS refused",
        LastFailure.of(failure));

  }

  @Test
  @DisplayName("A message of several lines is written as one")
  public void lineBreaksBecomeBlanks() {

    assertEquals(
        "java.lang.IllegalStateException: first line second line",
        LastFailure.of(new IllegalStateException("first line\r\n   second line")));

  }

  @Test
  @DisplayName("A long text is cut to the maximum number of bytes, at a whole character, and the cut is marked")
  public void aLongTextIsCutInBytes() {

    // two bytes each in UTF-8, so a cut by characters would leave a text twice as long as
    // the column of a database which counts bytes
    final var text = LastFailure.of(new IllegalStateException("ä".repeat(2 * LastFailure.MAX_BYTES)));

    final var bytes = text.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(bytes <= LastFailure.MAX_BYTES, "the text has "
        + bytes
        + " bytes");
    assertTrue(bytes >= LastFailure.MAX_BYTES - 4, "the text was cut shorter than needed: "
        + bytes
        + " bytes");
    assertTrue(text.endsWith("ä..."), "the cut is not marked or split a character: "
        + text);

  }

  @Test
  @DisplayName("A text which fits is not cut")
  public void aTextWhichFitsIsNotCut() {

    final var message = "x".repeat(LastFailure.MAX_BYTES - "java.lang.IllegalStateException: ".length());

    assertEquals(
        "java.lang.IllegalStateException: "
            + message,
        LastFailure.of(new IllegalStateException(message)));

  }

}
