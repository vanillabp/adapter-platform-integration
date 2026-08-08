package io.vanillabp.integration.adapter.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class AdapterPlatformVersionTest {

  @Test
  @DisplayName("The platform version is filled by the build")
  public void testPlatformVersionIsKnown() {

    final var version = AdapterPlatformVersion.platformVersion();

    assertFalse(version.isBlank());
    assertFalse("unknown".equals(version), "platform-version.properties was not filtered by the build");
    assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), "unexpected version format: "
        + version);

  }

  @Test
  @DisplayName("An adapter requiring an older platform passes")
  public void testCompatibleAdapter() {

    assertDoesNotThrow(
        () -> AdapterPlatformVersion.requireCompatiblePlatform("test-compatible", getClass()));

  }

  @Test
  @DisplayName("An adapter requiring a newer platform fails, naming the artifacts to raise")
  public void testAdapterRequiringNewerPlatform() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> AdapterPlatformVersion.requireCompatiblePlatform("test-too-new", getClass()));

    final var message = exception.getMessage();
    assertTrue(message.contains("'test-too-new' adapter 9.9.9"), message);
    assertTrue(message.contains("99.0.0"), message);
    assertTrue(message.contains(AdapterPlatformVersion.platformVersion()), message);
    assertTrue(message.contains("io.vanillabp:vanillabp-bom"), message);
    assertTrue(message.contains("io.vanillabp.adapter:migration-adapter-spi"), message);
    assertTrue(message.contains("io.vanillabp:vanillabp-spring-boot-integration"), message);
    assertTrue(message.contains("io.vanillabp:vanillabp-quarkus-integration"), message);

  }

  @Test
  @DisplayName("The failure is reported again on a second attempt")
  public void testFailureIsNotCached() {

    assertThrows(
        IllegalStateException.class,
        () -> AdapterPlatformVersion.requireCompatiblePlatform("test-too-new", getClass()));
    assertThrows(
        IllegalStateException.class,
        () -> AdapterPlatformVersion.requireCompatiblePlatform("test-too-new", getClass()));

  }

  @Test
  @DisplayName("An adapter without a version descriptor is accepted")
  public void testAdapterWithoutDescriptor() {

    assertDoesNotThrow(
        () -> AdapterPlatformVersion.requireCompatiblePlatform("test-does-not-exist", getClass()));
    assertDoesNotThrow(
        () -> AdapterPlatformVersion.requireCompatiblePlatform("test-without-platform", getClass()));

  }

  @Test
  @DisplayName("Versions are compared by their numeric parts")
  public void testVersionComparison() {

    assertTrue(AdapterPlatformVersion.isAtLeast("2.0.0", "2.0.0"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.0.1", "2.0.0"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.1.0", "2.0.7"));
    assertTrue(AdapterPlatformVersion.isAtLeast("10.0.0", "9.9.9"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.1", "2.0.9"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.0.0.1", "2.0.0"));

    assertFalse(AdapterPlatformVersion.isAtLeast("2.0.0", "2.0.1"));
    assertFalse(AdapterPlatformVersion.isAtLeast("2.0.7", "2.1.0"));
    assertFalse(AdapterPlatformVersion.isAtLeast("9.9.9", "10.0.0"));
    assertFalse(AdapterPlatformVersion.isAtLeast("2.0", "2.0.1"));

  }

  @Test
  @DisplayName("Qualifiers are ignored, so development builds satisfy their release version")
  public void testQualifiersAreIgnored() {

    assertTrue(AdapterPlatformVersion.isAtLeast("2.0.0-SNAPSHOT", "2.0.0"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.0.0", "2.0.0-SNAPSHOT"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.1.0-alpha1", "2.0.0"));
    assertFalse(AdapterPlatformVersion.isAtLeast("2.0.0-SNAPSHOT", "2.0.1"));

  }

  @Test
  @DisplayName("Versions which cannot be parsed are treated as compatible")
  public void testUnparseableVersions() {

    assertTrue(AdapterPlatformVersion.isAtLeast(null, "2.0.0"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.0.0", null));
    assertTrue(AdapterPlatformVersion.isAtLeast("", "2.0.0"));
    assertTrue(AdapterPlatformVersion.isAtLeast("custom-build", "2.0.0"));
    assertTrue(AdapterPlatformVersion.isAtLeast("2.0.0", "2.x"));

  }

}
