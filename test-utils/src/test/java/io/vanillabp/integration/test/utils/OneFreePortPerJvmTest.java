package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The promise the class name makes: one port, and the same one every time it is asked.
 * <p>
 * A test which configures a port and then connects to it reads both values from here. If
 * the second call ever answered a second port, that test would configure one application
 * and talk to nothing, and its own code would not show why.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OneFreePortPerJvmTest {

  @Test
  @DisplayName("Every call answers the port this JVM got")
  public void everyCallAnswersTheSamePort() {

    assertEquals(
        OneFreePortPerJvm.getPort(),
        OneFreePortPerJvm.getPort(),
        "a second port would let a test configure one and connect to the other");

  }

  @Test
  @DisplayName("The port is one an application can bind")
  public void thePortIsOneAnApplicationCanBind() {

    final var port = OneFreePortPerJvm.getPort();

    assertTrue(
        (port > 0) && (port < 65536),
        () -> "the operating system was asked for a port to hand to an application, but it answered "
            + port);

  }

}
