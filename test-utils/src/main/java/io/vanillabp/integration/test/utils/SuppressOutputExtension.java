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

    redirectJulConsoleHandlers();

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

  /**
   * Redirecting {@code System.out} is not enough for a test running under JBoss
   * LogManager, which the Quarkus test modules install through the Surefire property
   * {@code java.util.logging.manager}. A handler of that log manager holds the
   * {@code System.out} it found when it was created, so everything logged through it goes
   * to the real console no matter what this extension does afterwards. The augmentation
   * line of a {@code QuarkusProdModeTest} and the Testcontainers lines came
   * from there.
   * <p>
   * Every stream handler reachable from the root logger is given a stream which resolves
   * {@code System.out} at WRITE time, so it follows this extension into the capture buffer
   * and back out when the streams are restored. Nothing has to be undone, and a failing
   * class still replays what the application logged - which is the half a switched off
   * console handler would have broken.
   * <p>
   * The walk goes through nested handlers, because Quarkus puts its console handler
   * inside {@code QuarkusDelayedHandler} while the application boots. It runs on every
   * capture, since handlers appear and are replaced during that boot, and it is
   * idempotent. Where JBoss LogManager is absent, nothing happens at all.
   */
  private static void redirectJulConsoleHandlers() {

    final var logManager = java.util.logging.LogManager.getLogManager();
    if (!logManager.getClass().getName().startsWith("org.jboss.logmanager.")) {
      return;
    }
    final var rootLogger = logManager.getLogger("");
    if (rootLogger == null) {
      return;
    }
    for (final var handler : rootLogger.getHandlers()) {
      redirectHandlerTree(handler, 0);
    }

  }

  private static void redirectHandlerTree(
      final java.util.logging.Handler handler,
      final int depth) {

    if ((handler == null) || (depth > 8)) {
      // a handler nesting deeper than this is a cycle, and a cycle is not this
      // extension's problem to solve
      return;
    }
    redirectIfStreamHandler(handler);
    for (final var nested : nestedHandlersOf(handler)) {
      redirectHandlerTree(nested, depth + 1);
    }

  }

  private static java.util.logging.Handler[] nestedHandlersOf(
      final java.util.logging.Handler handler) {

    try {
      final var getHandlers = handler.getClass().getMethod("getHandlers");
      final var nested = getHandlers.invoke(handler);
      return nested instanceof java.util.logging.Handler[] handlers
          ? handlers
          : new java.util.logging.Handler[0];
    } catch (Exception e) {
      // a handler without getHandlers(), or one refusing the call: it wraps nothing
      // this extension could redirect
      return new java.util.logging.Handler[0];
    }

  }

  private static void redirectIfStreamHandler(
      final java.util.logging.Handler handler) {

    final java.lang.reflect.Method setOutputStream;
    try {
      setOutputStream = handler.getClass().getMethod("setOutputStream", OutputStream.class);
    } catch (NoSuchMethodException e) {
      // not a stream handler: a file or a socket handler prints nowhere near the build log
      return;
    }
    try {
      final var installed = currentOutputStreamOf(handler);
      if (installed instanceof CurrentSystemOut) {
        return;
      }
      setOutputStream.invoke(handler, new CurrentSystemOut());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not redirect the handler '%s' of JBoss LogManager - without it a test prints into the log of a green build"
              .formatted(handler.getClass().getName()), e);
    }

  }

  private static Object currentOutputStreamOf(
      final java.util.logging.Handler handler) {

    // 'getOutputStream' is not public API of every version, and not knowing what is
    // installed only costs one redundant redirect
    try {
      final var getOutputStream = handler.getClass().getMethod("getOutputStream");
      return getOutputStream.invoke(handler);
    } catch (Exception e) {
      return null;
    }

  }

  /**
   * Writes to whatever {@code System.out} is at the time of the write, and refuses to be
   * closed: the handler owning it must not close a stream this extension restores.
   */
  private static class CurrentSystemOut extends OutputStream {

    @Override
    public void write(
        final int b) {

      System.out.write(b);

    }

    @Override
    public void write(
        final byte[] b,
        final int off,
        final int len) {

      System.out.write(b, off, len);

    }

    @Override
    public void flush() {

      System.out.flush();

    }

    @Override
    public void close() {

      System.out.flush();

    }

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
