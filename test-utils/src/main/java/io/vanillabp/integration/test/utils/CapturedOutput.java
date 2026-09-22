package io.vanillabp.integration.test.utils;

import java.io.ByteArrayOutputStream;

/**
 * Provides access to output captured by {@link SuppressOutputExtension}.
 * Can be injected as a test method parameter when using
 * {@code @ExtendWith(SuppressOutputExtension.class)}.
 * <p>
 * There are two views on that output, and they answer two different questions.
 * {@link #getAll()} and its two halves show everything the test class has printed so far,
 * the tests which already ran included. {@link #getAllOfThisTest()} and its two halves
 * show only what the running test printed.
 * <p>
 * An assertion that a sentence IS there may use either view, because a sentence printed
 * anywhere in the class is still evidence that the code under test writes it. An
 * assertion that a sentence is NOT there has to use the view of the running test.
 * Over the whole class such an assertion also speaks for every other test of that class,
 * so it passes as long as the test which prints the sentence happens to run later.
 * Nothing guarantees that order and nothing fails when it changes.
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
   * Returns all output of this test class so far (stdout and stderr combined), which
   * includes what the tests before this one printed.
   */
  public String getAll() {

    return combine(classLevelAllBuffer, allBuffer);

  }

  /**
   * Returns the stdout of this test class so far, which includes what the tests before
   * this one printed.
   */
  public String getOut() {

    return combine(classLevelOutBuffer, outBuffer);

  }

  /**
   * Returns the stderr of this test class so far, which includes what the tests before
   * this one printed.
   */
  public String getErr() {

    return combine(classLevelErrBuffer, errBuffer);

  }

  /**
   * Returns what the running test printed (stdout and stderr combined), and nothing of
   * what the tests before it printed. This is the view an assertion about an absent
   * sentence needs.
   */
  public String getAllOfThisTest() {

    return combine(null, allBuffer);

  }

  /**
   * Returns the stdout of the running test, and nothing of what the tests before it
   * printed. This is the view an assertion about an absent sentence needs.
   */
  public String getOutOfThisTest() {

    return combine(null, outBuffer);

  }

  /**
   * Returns the stderr of the running test, and nothing of what the tests before it
   * printed. This is the view an assertion about an absent sentence needs.
   */
  public String getErrOfThisTest() {

    return combine(null, errBuffer);

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
