package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The guard of one line of build configuration: the Surefire and Failsafe setup in
 * <code>quarkus-integration/pom.xml</code> hands every test JVM
 * <code>quarkus.http.test-port=0</code>.
 * <p>
 * Every test which boots a Quarkus application binds that port, and the default of the key
 * is 8081. So two builds on one machine fight over one port, and the loser dies with
 * "Port already bound: 8081". On 2026-09-17 this cost two agents a fifteen-minute build. The
 * class which died was whichever one started while the other build held the port. A CI
 * runner builds alone, so nothing shows it there.
 * <p>
 * Zero means that the operating system picks a free port. A test which has to know the port
 * reads the key back. Once the application listens, Quarkus writes the port it really bound
 * into that key. So no test needs a number of its own, and a number written anywhere brings
 * the clash back. See "Ports during tests" in <code>quarkus-integration/README.md</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
public class HttpTestPortIsFreeTest {

  @Test
  @DisplayName("The build gives every test JVM a port the operating system picks")
  public void theBuildGivesEveryTestJvmAFreePort() {

    assertEquals(
        "0",
        System.getProperty("quarkus.http.test-port"),
        """
            The build no longer hands this test JVM a free HTTP port, so every test booting \
            an application falls back to Quarkus' default of 8081. A second build on the \
            same machine then dies with "Port already bound: 8081". Put \
            '<quarkus.http.test-port>0</quarkus.http.test-port>' back into the Surefire and \
            Failsafe systemPropertyVariables of quarkus-integration/pom.xml.""");

  }

}
