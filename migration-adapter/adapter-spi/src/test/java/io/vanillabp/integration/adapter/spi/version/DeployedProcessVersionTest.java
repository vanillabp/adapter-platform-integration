package io.vanillabp.integration.adapter.spi.version;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How a deployed version is written down for a person to read. One spelling for every
 * caller, so a cockpit, a log line and a support tool name the same deployment the same
 * way.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeployedProcessVersionTest {

  @Test
  @DisplayName("A tagged version reads as the tag, a colon and the version")
  public void aTaggedVersionCarriesBoth() {

    assertEquals("release-7:4", DeployedProcessVersion.of("4", "release-7").displayVersion());

  }

  @Test
  @DisplayName("Without a tag the version stands alone, with no separator")
  public void anUntaggedVersionStandsAlone() {

    assertEquals("4", DeployedProcessVersion.of("4").displayVersion());
    assertEquals("4", DeployedProcessVersion.of("4", null).displayVersion());

  }

  @Test
  @DisplayName("A blank tag reads like no tag rather than like an empty one")
  public void aBlankTagIsNoTag() {

    assertEquals("4", DeployedProcessVersion.of("4", "").displayVersion());
    assertEquals("4", DeployedProcessVersion.of("4", "   ").displayVersion());

  }

  @Test
  @DisplayName("The moment of the deployment does not show up in what an operator reads")
  public void theDeploymentMomentIsNotPartOfIt() {

    assertEquals(
        "v2:17",
        new DeployedProcessVersion("17", "v2", Instant.parse("2026-09-13T10:15:30Z")).displayVersion());

  }

}
