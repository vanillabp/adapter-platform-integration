package io.vanillabp.intergration.test.utils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.extension.*;

public class SuppressOutputExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

  private PrintStream originalOut;
  private PrintStream originalErr;
  private ByteArrayOutputStream buffer;

  @Override
  public void beforeAll(
      final ExtensionContext context) {

    backupOriginalOutputStreams();
    startCapture();

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

    startCapture();

  }

  @Override
  public void afterEach(
      final ExtensionContext context) {

    stopCapture(context, false);

  }

  private void startCapture() {

    buffer = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(buffer);

    System.setOut(ps);
    System.setErr(ps);

  }

  private void backupOriginalOutputStreams() {

    originalOut = System.out;
    originalErr = System.err;

  }

  private void stopCapture(
      final ExtensionContext context,
      final boolean classLevel) {

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

}
