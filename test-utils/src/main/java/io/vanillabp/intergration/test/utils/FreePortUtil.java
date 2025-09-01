package io.vanillabp.intergration.test.utils;

import java.io.IOException;
import java.net.ServerSocket;

public class FreePortUtil {

  private final static int httpPort = findFreePort();

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
