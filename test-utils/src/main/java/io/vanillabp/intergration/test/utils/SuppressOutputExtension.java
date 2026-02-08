package io.vanillabp.intergration.test.utils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

public class SuppressOutputExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

  private static final Namespace NAMESPACE = Namespace.create(SuppressOutputExtension.class);
  private static final String CAPTURED_OUTPUT_KEY = "capturedOutput";

  private PrintStream originalOut;
  private PrintStream originalErr;
  private ByteArrayOutputStream buffer;
  private ByteArrayOutputStream classLevelBuffer;

  @Override
  public void beforeAll(
      final ExtensionContext context) {

    backupOriginalOutputStreams();
    classLevelBuffer = new ByteArrayOutputStream();
    startCapture(context);

  }

  @Override
  public void afterAll(
      final ExtensionContext context) {

    stopCapture(context, true);
    restoreOriginalOutputStreams();

  }

  @Override
  public void beforeEach(
      final ExtensionContext context) {

    startCapture(context);

  }

  @Override
  public void afterEach(
      final ExtensionContext context) {

    stopCapture(context, false);

  }

  private void startCapture(
      final ExtensionContext context) {

    buffer = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(buffer);

    System.setOut(ps);
    System.setErr(ps);

    // Store buffer reference in context for test access
    getStore(context).put(CAPTURED_OUTPUT_KEY, buffer);

  }

  private void backupOriginalOutputStreams() {

    originalOut = System.out;
    originalErr = System.err;

  }

  private void stopCapture(
      final ExtensionContext context,
      final boolean classLevel) {

    // Append to class level buffer before potentially resetting
    if (classLevelBuffer != null && buffer != null) {
      try {
        classLevelBuffer.write(buffer.toByteArray());
      } catch (Exception e) {
        // Ignore
      }
    }

    if (context.getExecutionException().isPresent()) {
      if (classLevel) {
        originalOut.println("---- Captured Output (class level) ----");
      } else {
        originalOut.println("----------- Captured Output -----------");
      }
      originalOut.println(buffer.toString());
      originalOut.println("---------------------------------------");
    }

  }

  private void restoreOriginalOutputStreams() {

    System.setOut(originalOut);
    System.setErr(originalErr);

  }

  private Store getStore(
      final ExtensionContext context) {

    return context.getStore(NAMESPACE);

  }

  /**
   * Returns the captured output from the extension context.
   * Can be used by tests to verify logged messages.
   *
   * @param context the JUnit extension context
   * @return the captured output as a string, or empty string if no output was captured
   */
  public static String getCapturedOutput(
      final ExtensionContext context) {

    final var store = context.getStore(NAMESPACE);
    final var buffer = store.get(CAPTURED_OUTPUT_KEY, ByteArrayOutputStream.class);
    return buffer != null ? buffer.toString() : "";

  }

  /**
   * Returns all captured output including class-level logs (e.g., from Spring Boot startup).
   * Can be used by tests to verify logged messages when using @RegisterExtension.
   *
   * @return the captured output as a string, or empty string if no output was captured
   */
  public String getCapturedOutput() {

    final var result = new StringBuilder();
    if (classLevelBuffer != null) {
      result.append(classLevelBuffer.toString());
    }
    if (buffer != null) {
      result.append(buffer.toString());
    }
    return result.toString();

  }

}
