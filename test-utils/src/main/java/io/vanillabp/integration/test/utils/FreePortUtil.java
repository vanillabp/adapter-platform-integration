package io.vanillabp.integration.test.utils;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * One free TCP port per JVM, for a test which has to tell something it starts which port
 * to use - a Quarkus prod-mode application, for instance - and then has to talk to it.
 * <p>
 * The port is asked for once, when this class is loaded, and answered again and again
 * afterwards. Asking the operating system a second time would hand out a second port, and
 * a test which configures one port and connects to the other fails for a reason nobody
 * sees in its code.
 * <p>
 * The port was free when it was looked up and nothing holds it until the test binds it.
 * That is enough here, because each forked test JVM asks for its own.
 */
public class FreePortUtil {

  private final static int httpPort = findFreePort();

  /**
   * Nobody builds this class, it only answers a static question.
   */
  private FreePortUtil() {
  }

  /**
   * The port this JVM uses, the same one on every call.
   *
   * @return A port which was free when this class was loaded
   */
  public static int getFreePort() {
    return httpPort;
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
