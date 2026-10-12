package io.vanillabp.integration.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A key as a store can hold it: readable while it is short enough, and a hash of itself
 * once it is not.
 * <p>
 * Both keys VanillaBP persists are limited here: the identity of a processed task
 * delivery (see {@code TaskDeliveryKey}) and the idempotency key of a phase-two call
 * (see {@link PhaseTwoCall#of}). They are limited for the same reason and with the same
 * digest. So a reader who finds a hashed key in one table and a hashed key in the other
 * does not have to ask whether the two mean the same thing.
 * <p>
 * The store forces the limit, not the key. A unique index has a maximum key length
 * (MySQL allows 3072 bytes, which is 768 characters with utf8mb4). A column has a width.
 * And gruelbox refuses a unique request ID longer than 250 characters before any database
 * sees it. An identifier a domain model may rightly use, like a composite business key or
 * a URN, grows past all of that. Hashing only costs the readability of a key which
 * nobody can read at that length anyway.
 * <p>
 * <strong>The limit counts bytes of UTF-8, not characters.</strong> Oracle and DB2 count
 * a <code>VARCHAR</code> in bytes. So a key of 512 characters with an umlaut in it does
 * not fit a column of 512 there, and the insert would fail instead of the key being
 * hashed. UTF-8 is the encoding of a database which can hold any character, and it never
 * needs fewer bytes than there are characters. So a key which fits in bytes also fits a
 * database which counts characters, and a store which counts characters (like gruelbox)
 * is still served. The key is never cut. It is kept whole or hashed whole, so two keys
 * which differ anywhere stay different. Decision 128 in the repository's {@code DECISIONS.md}
 * has the reasoning.
 * <p>
 * <strong>The output is a persisted contract.</strong> Records of a running installation
 * are matched by these strings. So a changed digest, prefix or limit would silently stop
 * matching after an upgrade. Both callers fix the format with fixed inputs and expected
 * outputs, one of them past the limit.
 */
public final class StoredKey {

  /**
   * What a hashed key starts with, so a reader can tell it apart from a key which reads
   * as it was built.
   */
  public static final String HASH_PREFIX = "sha256:";

  private StoredKey() {

  }

  /**
   * Limits the given key. It is returned unchanged while its UTF-8 form fits, and hashed
   * otherwise.
   *
   * @param key The key as it was built
   * @param maxBytes The number of bytes of UTF-8 the store can hold
   * @return The key or a hash of it. The hash is
   *         {@value #HASH_PREFIX}-plus-64 characters of ASCII, so it has as many bytes as
   *         characters
   */
  public static String of(
      final String key,
      final int maxBytes) {

    return fits(key, maxBytes)
        ? key
        : hashed(key);

  }

  /**
   * Whether the UTF-8 form of the key has at most the given number of bytes. A character
   * takes at least one byte and at most three, and a pair of surrogates (one character
   * outside the Basic Multilingual Plane) takes four. So most keys are decided by their
   * length alone, and only the others are encoded.
   */
  private static boolean fits(
      final String key,
      final int maxBytes) {

    if (key.length() > maxBytes) {
      return false;
    }
    if (key.length() <= maxBytes / 3) {
      return true;
    }
    return key.getBytes(StandardCharsets.UTF_8).length <= maxBytes;

  }

  /**
   * Hashes a key which does not fit. SHA-256 is available on every JVM. The exception
   * which cannot happen is wrapped and not declared, because a caller could not do
   * anything about it either.
   */
  private static String hashed(
      final String key) {

    try {
      final var digest = MessageDigest
          .getInstance("SHA-256")
          .digest(key.getBytes(StandardCharsets.UTF_8));
      return HASH_PREFIX + HexFormat.of().formatHex(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available to hash a stored key!", e);
    }

  }

}
