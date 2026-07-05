package io.vanillabp.integration.test.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

public class SuppressOutputExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

  /**
   * Annotation to suppress output from background threads after all tests have completed.
   * When present, output streams are redirected to a null output stream instead of being
   * restored to the original streams. This prevents background threads (e.g., from
   * Testcontainers or database drivers) from printing output after the test class finishes.
   * Use on test classes together with {@code @ExtendWith(SuppressOutputExtension.class)}.
   */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface SuppressBackgroundOutput {

  }

  private static final Namespace NAMESPACE = Namespace.create(SuppressOutputExtension.class);
  private static final String CAPTURED_OUTPUT_KEY = "capturedOutput";

  private boolean suppressBackgroundOutput = false;

  private PrintStream originalOut;
  private PrintStream originalErr;
  private ByteArrayOutputStream allBuffer;
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private ByteArrayOutputStream classLevelAllBuffer;
  private ByteArrayOutputStream classLevelOutBuffer;
  private ByteArrayOutputStream classLevelErrBuffer;
  private CapturedOutput capturedOutput;

  @Override
  public void beforeAll(
      final ExtensionContext context) {

    backupOriginalOutputStreams();
    classLevelAllBuffer = new ByteArrayOutputStream();
    classLevelOutBuffer = new ByteArrayOutputStream();
    classLevelErrBuffer = new ByteArrayOutputStream();
    readAnnotations(context);
    startCapture(context);

  }

  /**
   * Configures the extension to suppress output from background threads after afterAll.
   * Instead of restoring the original output streams, they are set to a null output stream.
   * <p>
   * Use this method when registering the extension programmatically via
   * {@code @RegisterExtension}. For declarative usage with {@code @ExtendWith},
   * use the {@link SuppressBackgroundOutput} annotation instead.
   *
   * @return this extension instance for fluent configuration
   */
  public SuppressOutputExtension withSuppressBackgroundOutput() {

    this.suppressBackgroundOutput = true;
    return this;

  }

  @Override
  public void afterAll(
      final ExtensionContext context) {

    stopCapture(context, true);
    if (suppressBackgroundOutput) {
      silenceOutputStreams();
    } else {
      restoreOriginalOutputStreams();
    }

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

  @Override
  public boolean supportsParameter(
      final ParameterContext parameterContext,
      final ExtensionContext extensionContext) {

    return CapturedOutput.class.isAssignableFrom(
        parameterContext.getParameter().getType());

  }

  @Override
  public Object resolveParameter(
      final ParameterContext parameterContext,
      final ExtensionContext extensionContext) {

    return capturedOutput;

  }

  private void readAnnotations(
      final ExtensionContext context) {

    context.getTestClass()
        .map(cls -> cls.getAnnotation(SuppressBackgroundOutput.class))
        .ifPresent(annotation -> suppressBackgroundOutput = true);

  }

  private void startCapture(
      final ExtensionContext context) {

    allBuffer = new ByteArrayOutputStream();
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();

    System.setOut(new PrintStream(
        new TeeOutputStream(allBuffer, outBuffer)));
    System.setErr(new PrintStream(
        new TeeOutputStream(allBuffer, errBuffer)));

    capturedOutput = new CapturedOutput(
        classLevelAllBuffer, allBuffer, classLevelOutBuffer, outBuffer, classLevelErrBuffer, errBuffer);

    // Store CapturedOutput in context for test access
    getStore(context).put(CAPTURED_OUTPUT_KEY, capturedOutput);

  }

  private void backupOriginalOutputStreams() {

    originalOut = System.out;
    originalErr = System.err;

  }

  private void stopCapture(
      final ExtensionContext context,
      final boolean classLevel) {

    // Append to class level buffers before potentially resetting
    if (classLevelAllBuffer != null && allBuffer != null) {
      try {
        classLevelAllBuffer.write(allBuffer.toByteArray());
        classLevelOutBuffer.write(outBuffer.toByteArray());
        classLevelErrBuffer.write(errBuffer.toByteArray());
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
      originalOut.println(allBuffer.toString());
      originalOut.println("---------------------------------------");
    }

  }

  private void restoreOriginalOutputStreams() {

    System.setOut(originalOut);
    System.setErr(originalErr);

  }

  private void silenceOutputStreams() {

    final var nullOut = new PrintStream(OutputStream.nullOutputStream());
    System.setOut(nullOut);
    System.setErr(nullOut);

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
    final var output = store.get(CAPTURED_OUTPUT_KEY, CapturedOutput.class);
    return output != null ? output.getAll() : "";

  }

  /**
   * Returns all captured output including class-level logs (e.g., from Spring Boot startup).
   * Can be used by tests to verify logged messages when using @RegisterExtension.
   *
   * @return the captured output as a string, or empty string if no output was captured
   */
  public String getCapturedOutput() {

    return capturedOutput != null ? capturedOutput.getAll() : "";

  }

  private static class TeeOutputStream extends OutputStream {

    private final OutputStream first;
    private final OutputStream second;

    TeeOutputStream(
        final OutputStream first,
        final OutputStream second) {

      this.first = first;
      this.second = second;

    }

    @Override
    public void write(
        final int b) throws IOException {

      first.write(b);
      second.write(b);

    }

    @Override
    public void write(
        final byte[] b) throws IOException {

      first.write(b);
      second.write(b);

    }

    @Override
    public void write(
        final byte[] b,
        final int off,
        final int len) throws IOException {

      first.write(b, off, len);
      second.write(b, off, len);

    }

    @Override
    public void flush() throws IOException {

      first.flush();
      second.flush();

    }

    @Override
    public void close() throws IOException {

      first.close();
      second.close();

    }

  }

}
