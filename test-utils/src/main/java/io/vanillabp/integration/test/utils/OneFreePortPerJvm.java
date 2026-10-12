package io.vanillabp.integration.test.utils;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * One free TCP port per JVM. It is for a test which starts something, for example a
 * Quarkus prod-mode application, tells it which port to use, and then talks to it.
 * <p>
 * The port is looked up once, when this class is loaded. After that, every call returns
 * the same port. Asking the operating system a second time would hand out a second port.
 * A test which configures one port and connects to the other fails, and nobody sees the
 * reason in its code. The name says so. A test which needs two different ports has to
 * look elsewhere.
 * <p>
 * The port was free when it was looked up, and nothing holds it until the test binds it.
 * That is enough here, because each forked test JVM asks for its own.
 */
public class OneFreePortPerJvm {

  private final static int port = findFreePort();

  /**
   * Nobody builds this class. It only answers a static question.
   */
  private OneFreePortPerJvm() {
  }

  /**
   * The port this JVM uses, the same one on every call.
   *
   * @return A port which was free when this class was loaded
   */
  public static int getPort() {
    return port;
  }

  private static int findFreePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException("Could not find a free port", e);
    }
  }

}
