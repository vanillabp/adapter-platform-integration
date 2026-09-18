package io.vanillabp.integration.spi.parts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The version descriptors this test judges lie in
 * <code>src/test/resources/META-INF/vanillabp</code>. Each of them is one of the cases a
 * boot has to tell apart.
 */
@ExtendWith(SuppressOutputExtension.class)
public class VanillaBpPartsTest {

  @Test
  @DisplayName("The platform version and the oldest part served are filled by the build")
  public void testPlatformVersionIsKnown() {

    final var version = VanillaBpParts.platformVersion();

    assertFalse(version.isBlank());
    assertFalse("unknown".equals(version), "platform-version.properties was not filtered by the build");
    assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), "unexpected version format: "
        + version);
    assertTrue(VanillaBpParts.oldestPartVersionServed().matches("\\d+\\.\\d+\\.\\d+.*"),
        "the platform does not name the oldest part it serves");

  }

  @Test
  @DisplayName("An adapter built against an older platform starts")
  public void testAdapterBuiltAgainstAnOlderPlatform() {

    assertDoesNotThrow(
        () -> VanillaBpParts.requireAdapterFitsPlatform("test-compatible", getClass()));

  }

  @Test
  @DisplayName("An adapter built against a newer platform stops the boot, naming the line to change")
  public void testAdapterBuiltAgainstANewerPlatform() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> VanillaBpParts.requireAdapterFitsPlatform("test-too-new", getClass()));

    final var message = exception.getMessage();
    assertTrue(message.contains("adapter 'test-too-new' 9.9.9"), message);
    assertTrue(message.contains("99.0.0"), message);
    assertTrue(message.contains(VanillaBpParts.platformVersion()), message);
    assertTrue(message.contains("does not start"), message);
    assertTrue(message.contains("io.vanillabp:vanillabp-bom"), message);
    assertTrue(message.contains("io.vanillabp:vanillabp-spring-boot-integration"), message);
    assertTrue(message.contains("io.vanillabp:vanillabp-quarkus-integration"), message);
    assertTrue(message.contains("io.vanillabp.test:test-too-new-adapter"), message);

  }

  @Test
  @DisplayName("An adapter older than the oldest one served stops the boot, naming the artifact to raise")
  public void testAdapterOlderThanTheOldestOneServed() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> VanillaBpParts.requireAdapterFitsPlatform("test-too-old", getClass()));

    final var message = exception.getMessage();
    assertTrue(message.contains("adapter 'test-too-old' 1.9.0"), message);
    assertTrue(message.contains(VanillaBpParts.oldestPartVersionServed()), message);
    assertTrue(message.contains("does not start"), message);
    assertTrue(message.contains("io.vanillabp.test:test-too-old-adapter"), message);

  }

  @Test
  @DisplayName("A part which stops the boot is reported to every caller asking")
  public void testAPartWhichStopsTheBootIsNotRemembered() {

    assertThrows(
        IllegalStateException.class,
        () -> VanillaBpParts.requireAdapterFitsPlatform("test-too-new", getClass()));
    assertThrows(
        IllegalStateException.class,
        () -> VanillaBpParts.requireAdapterFitsPlatform("test-too-new", getClass()));

  }

  @Test
  @DisplayName("An adapter shipping no version descriptor boots and is worth a warning")
  public void testAdapterWithoutAVersionDescriptor() {

    final var verdict = VanillaBpParts.judge(PartKind.ADAPTER, "test-says-nothing", getClass());

    assertTrue(verdict.isPresent(), "an unknown pair has to be said out loud");
    assertFalse(verdict.get().stopsTheBoot());
    assertTrue(verdict.get().message().contains("cannot tell whether the two belong together"),
        verdict.get().message());
    assertTrue(verdict.get().message().contains("META-INF/vanillabp/adapter-test-says-nothing.properties"),
        verdict.get().message());

  }

  @Test
  @DisplayName("An incomplete version descriptor boots and names the keys which matter")
  public void testIncompleteVersionDescriptor() {

    final var verdict = VanillaBpParts.judge(PartKind.ADAPTER, "test-incomplete", getClass());

    assertTrue(verdict.isPresent());
    assertFalse(verdict.get().stopsTheBoot());
    assertTrue(verdict.get().message().contains("part.version"), verdict.get().message());
    assertTrue(verdict.get().message().contains("platform.version"), verdict.get().message());

  }

  @Test
  @DisplayName("Versions this check cannot compare boot and are worth a warning")
  public void testVersionsWhichCannotBeCompared() {

    final var verdict = VanillaBpParts.judge(PartKind.EXTENSION, "test-own-build", getClass());

    assertTrue(verdict.isPresent());
    assertFalse(verdict.get().stopsTheBoot());
    assertTrue(verdict.get().message().contains("extension 'test-own-build' my-own-build"),
        verdict.get().message());
    assertTrue(verdict.get().message().contains("A build of your own"), verdict.get().message());

  }

  @Test
  @DisplayName("A warning is said once, however often the part is judged")
  public void testAWarningIsSaidOnce() {

    assertTrue(VanillaBpParts.judge(PartKind.ADAPTER, "test-says-nothing-twice", getClass()).isPresent());
    assertTrue(VanillaBpParts.judge(PartKind.ADAPTER, "test-says-nothing-twice", getClass()).isEmpty());

  }

  @Test
  @DisplayName("Versions are compared by their numeric parts")
  public void testVersionComparison() {

    assertTrue(VanillaBpParts.isAtLeast("2.0.0", "2.0.0"));
    assertTrue(VanillaBpParts.isAtLeast("2.0.1", "2.0.0"));
    assertTrue(VanillaBpParts.isAtLeast("2.1.0", "2.0.7"));
    assertTrue(VanillaBpParts.isAtLeast("10.0.0", "9.9.9"));
    assertTrue(VanillaBpParts.isAtLeast("2.1", "2.0.9"));
    assertTrue(VanillaBpParts.isAtLeast("2.0.0.1", "2.0.0"));

    assertFalse(VanillaBpParts.isAtLeast("2.0.0", "2.0.1"));
    assertFalse(VanillaBpParts.isAtLeast("2.0.7", "2.1.0"));
    assertFalse(VanillaBpParts.isAtLeast("9.9.9", "10.0.0"));
    assertFalse(VanillaBpParts.isAtLeast("2.0", "2.0.1"));

  }

  @Test
  @DisplayName("Qualifiers are ignored, so development builds satisfy their release version")
  public void testQualifiersAreIgnored() {

    assertTrue(VanillaBpParts.isAtLeast("2.0.0-SNAPSHOT", "2.0.0"));
    assertTrue(VanillaBpParts.isAtLeast("2.0.0", "2.0.0-SNAPSHOT"));
    assertTrue(VanillaBpParts.isAtLeast("2.1.0-alpha1", "2.0.0"));
    assertFalse(VanillaBpParts.isAtLeast("2.0.0-SNAPSHOT", "2.0.1"));

  }

  @Test
  @DisplayName("Versions which cannot be read are not compared")
  public void testUnparseableVersions() {

    assertFalse(VanillaBpParts.comparable(null, "2.0.0"));
    assertFalse(VanillaBpParts.comparable("2.0.0", null));
    assertFalse(VanillaBpParts.comparable("", "2.0.0"));
    assertFalse(VanillaBpParts.comparable("custom-build", "2.0.0"));
    assertFalse(VanillaBpParts.comparable("2.0.0", "2.x"));
    assertTrue(VanillaBpParts.comparable("2.0.0", "2.1"));

  }

}
