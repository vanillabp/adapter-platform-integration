package io.vanillabp.intergration.test.utils;

import java.io.ByteArrayOutputStream;

/**
 * Provides access to output captured by {@link SuppressOutputExtension}.
 * Can be injected as a test method parameter when using
 * {@code @ExtendWith(SuppressOutputExtension.class)}.
 */
public class CapturedOutput implements CharSequence {

  private final ByteArrayOutputStream classLevelAllBuffer;
  private final ByteArrayOutputStream allBuffer;
  private final ByteArrayOutputStream classLevelOutBuffer;
  private final ByteArrayOutputStream outBuffer;
  private final ByteArrayOutputStream classLevelErrBuffer;
  private final ByteArrayOutputStream errBuffer;

  CapturedOutput(
      final ByteArrayOutputStream classLevelAllBuffer,
      final ByteArrayOutputStream allBuffer,
      final ByteArrayOutputStream classLevelOutBuffer,
      final ByteArrayOutputStream outBuffer,
      final ByteArrayOutputStream classLevelErrBuffer,
      final ByteArrayOutputStream errBuffer) {

    this.classLevelAllBuffer = classLevelAllBuffer;
    this.allBuffer = allBuffer;
    this.classLevelOutBuffer = classLevelOutBuffer;
    this.outBuffer = outBuffer;
    this.classLevelErrBuffer = classLevelErrBuffer;
    this.errBuffer = errBuffer;

  }

  /**
   * Returns all captured output (stdout and stderr combined).
   */
  public String getAll() {

    return combine(classLevelAllBuffer, allBuffer);

  }

  /**
   * Returns captured stdout output only.
   */
  public String getOut() {

    return combine(classLevelOutBuffer, outBuffer);

  }

  /**
   * Returns captured stderr output only.
   */
  public String getErr() {

    return combine(classLevelErrBuffer, errBuffer);

  }

  @Override
  public int length() {

    return toString().length();

  }

  @Override
  public char charAt(
      final int index) {

    return toString().charAt(index);

  }

  @Override
  public CharSequence subSequence(
      final int start,
      final int end) {

    return toString().subSequence(start, end);

  }

  @Override
  public String toString() {

    return getAll();

  }

  private String combine(
      final ByteArrayOutputStream classLevelBuffer,
      final ByteArrayOutputStream methodBuffer) {

    final var result = new StringBuilder();
    if (classLevelBuffer != null) {
      result.append(classLevelBuffer.toString());
    }
    if (methodBuffer != null) {
      result.append(methodBuffer.toString());
    }
    return result.toString();

  }

}
