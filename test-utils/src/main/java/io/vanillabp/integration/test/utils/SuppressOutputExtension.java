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

/**
 * Keeps the output of a green build empty and replays the output of a red one. It hands a
 * test what was captured as a {@link CapturedOutput} parameter.
 * <p>
 * The output of every finished test moves into the buffers of the class. So the two views
 * of {@link CapturedOutput} answer two different questions: what this class has printed
 * so far, and what the running test printed. Which one an assertion needs depends on
 * whether it looks for a sentence or for its absence. {@link CapturedOutput} says which
 * is which.
 */
public class SuppressOutputExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

  /**
   * Hides the output of background threads after all tests have finished. With this
   * annotation, the output streams are not restored to the original streams. They are
   * redirected to a stream which drops everything. So background threads, for example of
   * Testcontainers or of database drivers, print nothing after the test class finished.
   * Use it on test classes together with {@code @ExtendWith(SuppressOutputExtension.class)}.
   * <p>
   * That stream keeps the console it hides. So a class which runs after this one in the
   * same fork can still replay the output of a failing test where somebody reads it. See
   * {@code SuppressOutputExtensionTest#aFailingClassIsReadableBehindASilencingClass}.
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
  private boolean theFirstTestHasBegun;
  private String outputBeforeTheFirstTest;

  /**
   * JUnit builds the extension for the test class it is registered on. A test neither
   * creates it nor calls it. It writes {@code @ExtendWith(SuppressOutputExtension.class)}
   * and takes a {@link CapturedOutput} parameter where it wants to read what was printed.
   */
  public SuppressOutputExtension() {
  }

  @Override
  public void beforeAll(
      final ExtensionContext context) {

    backupOriginalOutputStreams();
    classLevelAllBuffer = new ByteArrayOutputStream();
    classLevelOutBuffer = new ByteArrayOutputStream();
    classLevelErrBuffer = new ByteArrayOutputStream();
    theFirstTestHasBegun = false;
    outputBeforeTheFirstTest = null;
    readAnnotations(context);
    startCapture(context);

  }

  /**
   * Makes the extension hide the output of background threads after afterAll. The original
   * output streams are not restored. Instead, they are set to a stream which drops
   * everything.
   * <p>
   * Use this method when you register the extension in code with
   * {@code @RegisterExtension}. With {@code @ExtendWith}, use the
   * {@link SuppressBackgroundOutput} annotation instead.
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

    keepWhatWasPrintedBeforeTheFirstTest();
    startCapture(context);

  }

  /**
   * Saves the output printed between beforeAll and the first test. Without this, the next
   * test would replace the buffer which holds it.
   * <p>
   * A {@code @SpringBootTest} starts its application context in that window. The lines it
   * writes there often say why a test of the class went red. They used to be dropped,
   * because each test starts with fresh buffers. Now they go into the buffers of the
   * class. So {@link CapturedOutput} shows them, and they are replayed in front of every
   * failing test of the class.
   * <p>
   * If the extension is registered per test method, the class buffers are not used. Then
   * there is no such window and nothing to keep.
   */
  private void keepWhatWasPrintedBeforeTheFirstTest() {

    if (theFirstTestHasBegun) {
      return;
    }
    theFirstTestHasBegun = true;
    if (allBuffer == null) {
      return;
    }
    outputBeforeTheFirstTest = allBuffer.toString();
    carryOverToClassLevelBuffers();
    resetTestLevelBuffers();

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

    getStore(context).put(CAPTURED_OUTPUT_KEY, capturedOutput);

  }

  private void backupOriginalOutputStreams() {

    originalOut = System.out;
    originalErr = System.err;

  }

  private void stopCapture(
      final ExtensionContext context,
      final boolean classLevel) {

    carryOverToClassLevelBuffers();

    if (context.getExecutionException().isPresent()) {
      final var console = consoleBehind(originalOut);
      if (classLevel) {
        // beforeAll or afterAll went wrong. That belongs to no single test, so everything
        // the class printed may hold the reason.
        console.println("---- Captured Output (class level) ----");
        console.println(classLevelAllBuffer.toString());
      } else {
        console.println("----------- Captured Output -----------");
        replayWhatWasPrintedBeforeTheFirstTest(console);
        console.println(allBuffer.toString());
      }
      console.println("---------------------------------------");
    }

    if (!classLevel) {
      // The class buffers now keep what a test printed. Whatever is printed after the
      // last test must not repeat the last test.
      resetTestLevelBuffers();
    }

  }

  private void replayWhatWasPrintedBeforeTheFirstTest(
      final PrintStream console) {

    if ((outputBeforeTheFirstTest == null) || outputBeforeTheFirstTest.isEmpty()) {
      return;
    }
    console.println("--- printed before the first test of this class ---");
    console.println(outputBeforeTheFirstTest);
    console.println("--- printed while this test ran ---");

  }

  private void carryOverToClassLevelBuffers() {

    if ((classLevelAllBuffer == null) || (allBuffer == null)) {
      return;
    }
    try {
      classLevelAllBuffer.write(allBuffer.toByteArray());
      classLevelOutBuffer.write(outBuffer.toByteArray());
      classLevelErrBuffer.write(errBuffer.toByteArray());
    } catch (Exception e) {
      // Ignore
    }

  }

  private void resetTestLevelBuffers() {

    if (allBuffer == null) {
      return;
    }
    allBuffer.reset();
    outBuffer.reset();
    errBuffer.reset();

  }

  /**
   * Puts back exactly what was installed before this class started capturing. This
   * includes a stream silenced by an earlier class. That class asked to keep its
   * background threads out of a green build, and its threads still print between the
   * classes.
   */
  private void restoreOriginalOutputStreams() {

    System.setOut(originalOut);
    System.setErr(originalErr);

  }

  private void silenceOutputStreams() {

    System.setOut(new SilencedConsole(consoleBehind(originalOut)));
    System.setErr(new SilencedConsole(consoleBehind(originalErr)));

  }

  /**
   * The console a failure is replayed to. It is not always the stream this class found
   * installed.
   * <p>
   * All test classes of a module share one fork. A class annotated with
   * {@link SuppressBackgroundOutput} leaves a stream behind on purpose, and this stream
   * drops everything written to it. The next class finds that stream where the console
   * used to be. It would replay the reason for its red build into it, so a failing test
   * would say nothing at all. That is why the silenced stream keeps a reference to what it
   * hid, and this method asks for it.
   *
   * @param stream What this class found installed when it started capturing
   * @return The stream somebody reads
   */
  private static PrintStream consoleBehind(
      final PrintStream stream) {

    return stream instanceof SilencedConsole silenced
        ? silenced.console
        : stream;

  }

  /**
   * What a class which silences its background output leaves behind. Everything written
   * to it is dropped. The console it replaced stays reachable for whoever comes next.
   */
  private static class SilencedConsole extends PrintStream {

    private final PrintStream console;

    SilencedConsole(
        final PrintStream console) {

      super(OutputStream.nullOutputStream());
      this.console = console;

    }

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
   * Redirecting {@code System.out} is not enough for a test which runs under JBoss
   * LogManager. The Quarkus test modules install it through the Surefire property
   * {@code java.util.logging.manager}. A handler of that log manager keeps the
   * {@code System.out} it found when it was created. So everything logged through it goes
   * to the real console, whatever this extension does later. The augmentation line of a
   * {@code QuarkusProdModeTest} and the Testcontainers lines came from there.
   * <p>
   * So every stream handler reachable from the root logger gets a stream which looks up
   * {@code System.out} at WRITE time. It follows this extension into the capture buffer,
   * and back out when the streams are restored. Nothing has to be undone. A failing class
   * still replays what the application logged. A console handler which was simply
   * switched off would have broken that.
   * <p>
   * The walk goes through nested handlers, because Quarkus puts its console handler
   * inside {@code QuarkusDelayedHandler} while the application starts. It runs on every
   * capture, because handlers appear and are replaced during that start. Running it twice
   * does no harm. Without JBoss LogManager, nothing happens at all.
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
      // A handler nested deeper than this is a cycle. Solving a cycle is not the job of
      // this extension.
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
      // A handler without getHandlers(), or one which refuses the call. It wraps nothing
      // this extension could redirect.
      return new java.util.logging.Handler[0];
    }

  }

  private static void redirectIfStreamHandler(
      final java.util.logging.Handler handler) {

    final java.lang.reflect.Method setOutputStream;
    try {
      setOutputStream = handler.getClass().getMethod("setOutputStream", OutputStream.class);
    } catch (NoSuchMethodException e) {
      // Not a stream handler. A file or socket handler prints nowhere near the build log.
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

    // 'getOutputStream' is not public API in every version. Not knowing what is installed
    // only costs one redirect too many.
    try {
      final var getOutputStream = handler.getClass().getMethod("getOutputStream");
      return getOutputStream.invoke(handler);
    } catch (Exception e) {
      return null;
    }

  }

  /**
   * Writes to whatever {@code System.out} is at the time of the write. It refuses to be
   * closed, because the handler which owns it must not close a stream this extension
   * restores.
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
