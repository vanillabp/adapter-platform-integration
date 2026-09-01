package io.vanillabp.integration.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A key as a store can hold it: readable while it is short enough, a hash of itself
 * once it is not.
 * <p>
 * Both keys VanillaBP persists are bounded here - the identity of a processed task
 * delivery (see {@code TaskDeliveryKey}) and the idempotency key of a phase-two call
 * (see {@link PhaseTwoCall#of}). They are bounded for the same reason and by the same
 * digest, so a reader who finds a hashed key in one table and a hashed key in the other
 * does not have to ask whether the two mean the same thing.
 * <p>
 * What forces the bound is the store, not the key: a unique index has a maximum key
 * length (MySQL allows 3072 bytes, which is 768 characters with utf8mb4), a column has
 * a width, and gruelbox refuses a unique request ID longer than 250 characters before
 * any database sees it. An identifier a domain model legitimately uses - a composite
 * business key, a URN - grows past all of that, and hashing costs only the readability
 * of a key nobody can read at that length anyway.
 * <p>
 * <strong>The output is a persisted contract.</strong> Records of a running
 * installation are matched by these strings, so a changed digest, prefix or boundary
 * would silently stop matching after an upgrade. Both callers pin the format with fixed
 * inputs and expected outputs, one of them past the boundary.
 */
public final class StoredKey {

  /**
   * What a hashed key starts with, so a reader can tell it apart from a key which
   * reads as it was built.
   */
  public static final String HASH_PREFIX = "sha256:";

  private StoredKey() {

  }

  /**
   * Bounds the given key: returned unchanged while it fits, hashed otherwise.
   *
   * @param key The key as it was built
   * @param maxLength The number of characters the store can hold
   * @return The key or a hash of it, at most
   *         {@value #HASH_PREFIX}-plus-64 characters long
   */
  public static String of(
      final String key,
      final int maxLength) {

    return key.length() <= maxLength
        ? key
        : hashed(key);

  }

  /**
   * Hashes a key which does not fit. SHA-256 is available on every JVM; the
   * unreachable exception is wrapped rather than declared - a caller could not do
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
