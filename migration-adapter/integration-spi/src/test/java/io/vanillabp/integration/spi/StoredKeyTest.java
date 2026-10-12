package io.vanillabp.integration.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Oracle and DB2 count a <code>VARCHAR</code> in bytes, and the databases this build tests
 * against count characters. So no store test here would see a key which is short in
 * characters and too long in bytes. These tests measure the keys directly instead.
 */
@ExtendWith(SuppressOutputExtension.class)
public class StoredKeyTest {

  private static final int LIMIT = 512;

  @Test
  @DisplayName("A key of ASCII characters up to the limit stays readable")
  public void anAsciiKeyUpToTheLimitStaysReadable() {

    final var key = "a".repeat(LIMIT);

    assertEquals(key, StoredKey.of(key, LIMIT));

  }

  @Test
  @DisplayName("A key short in characters but too long in bytes is hashed")
  public void aKeyWithUmlautsIsMeasuredInBytes() {

    // 300 characters, 600 bytes of UTF-8
    final var key = "ä".repeat(300);

    final var stored = StoredKey.of(key, LIMIT);

    assertTrue(stored.startsWith(StoredKey.HASH_PREFIX), "the key was hashed");
    assertEquals(
        "sha256:172956790b1445929dd7cf0328ce202ae9aef36dd0bf31930c3dbaa67b0e7aba",
        stored,
        "the hash is the one of the whole key, as for a key which is too long in characters");

  }

  @Test
  @DisplayName("A key of two-byte characters is kept up to the last byte and hashed one byte later")
  public void twoByteCharactersAtTheLimit() {

    final var fits = "ü".repeat(LIMIT / 2);
    final var oneByteTooLong = fits
        + "a";
    final var mixed = "x"
        + "ü".repeat((LIMIT / 2) - 1)
        + "y";

    assertEquals(LIMIT, fits.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(fits, StoredKey.of(fits, LIMIT));
    assertTrue(StoredKey.of(oneByteTooLong, LIMIT).startsWith(StoredKey.HASH_PREFIX));
    assertEquals(LIMIT, mixed.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(mixed, StoredKey.of(mixed, LIMIT));

  }

  @Test
  @DisplayName("A key of three-byte characters is kept up to the last byte and hashed one byte later")
  public void threeByteCharactersAtTheLimit() {

    // 170 euro signs are 510 bytes. Two more ASCII characters make 512
    final var fits = "€".repeat(LIMIT / 3)
        + "ab";
    final var oneByteTooLong = fits
        + "c";

    assertEquals(LIMIT, fits.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(fits, StoredKey.of(fits, LIMIT));
    assertTrue(StoredKey.of(oneByteTooLong, LIMIT).startsWith(StoredKey.HASH_PREFIX));
    // 171 euro signs are 513 bytes, although they are only 171 characters
    assertTrue(StoredKey.of("€".repeat((LIMIT / 3) + 1), LIMIT).startsWith(StoredKey.HASH_PREFIX));

  }

  @Test
  @DisplayName("An emoji counts four bytes, and a key with emoji is never split, only hashed")
  public void emojiAtTheLimit() {

    // each emoji is two Java characters and four bytes of UTF-8
    final var fits = "😀".repeat(LIMIT / 4);
    final var oneByteTooLong = "😀".repeat(LIMIT / 4)
        + "a";

    assertEquals(LIMIT / 2, fits.length());
    assertEquals(LIMIT, fits.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(fits, StoredKey.of(fits, LIMIT));

    final var hashed = StoredKey.of(oneByteTooLong, LIMIT);
    assertTrue(hashed.startsWith(StoredKey.HASH_PREFIX));
    assertEquals(hashed.length(), hashed.getBytes(StandardCharsets.UTF_8).length, "the hash is ASCII");

  }

  @Test
  @DisplayName("Two keys which differ only in their last character get different hashes")
  public void hashedKeysStayDistinct() {

    final var prefix = "ö".repeat(LIMIT);

    assertNotEquals(
        StoredKey.of(prefix
            + "ä", LIMIT),
        StoredKey.of(prefix
            + "ü", LIMIT));

  }

  @Test
  @DisplayName("An idempotency key is limited in bytes, so an aggregate id with umlauts is hashed")
  public void theIdempotencyKeyIsLimitedInBytes() {

    // 200 characters fit the 250 of gruelbox, but they are 400 bytes of UTF-8
    final var aggregateId = "ß".repeat(200);

    final var key = PhaseTwoCall
        .of(
            PhaseOperation.CORRELATE_MESSAGE, "test-module", "TestProcess", aggregateId, null, Map
                .of(
                    PhaseTwoCall.ARG_MESSAGE_NAME, "OrderReceived",
                    PhaseTwoCall.ARG_CORRELATION_ID, "correlation-1"))
        .idempotencyKey()
        .orElseThrow();

    assertTrue(key.startsWith(StoredKey.HASH_PREFIX), "the key was hashed");
    assertTrue(key.getBytes(StandardCharsets.UTF_8).length <= PhaseTwoCall.MAX_IDEMPOTENCY_KEY_LENGTH);

  }

  @Test
  @DisplayName("The key of a workflow start is limited in bytes")
  public void theWorkflowStartKeyIsLimitedInBytes() {

    final var key = WorkflowStartKey.of("test-module", "TestProcess", "é".repeat(300));

    assertTrue(key.startsWith(StoredKey.HASH_PREFIX), "the key was hashed");

  }

}
