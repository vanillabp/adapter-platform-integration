package io.vanillabp.integration.test.nativeimage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import io.vanillabp.integration.spi.parts.PartKind;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Asks whether the version descriptors of the VanillaBP parts reached this application.
 * <p>
 * The platform integration and the dummy adapter ship their descriptors in ordinary JARs,
 * which Quarkus does not index. A native image carries only the resources it was told about.
 * Without them, the boot cannot tell whether adapter and platform belong together, and it
 * warns about the adapter. This check repeats what the boot judges and reports each finding
 * the boot would warn about.
 */
@ApplicationScoped
public class VersionDescriptorCheck {

  private static final String UNKNOWN_VERSION = "unknown";

  private static final String TYPE_OF_THE_DUMMY_ADAPTER = "dummy";

  /**
   * @return One line per part whose descriptor is missing or not judged as fitting. Empty if
   *         every descriptor was read and the parts belong together
   */
  public List<String> whatTheDescriptorsGotWrong() {

    final var platformComplaint = UNKNOWN_VERSION.equals(VanillaBpParts.platformVersion())
        ? Stream.of("The descriptor '%s' of the platform integration was not found"
            .formatted(VanillaBpParts.PLATFORM_DESCRIPTOR))
        : Stream.<String>empty();

    final var adapterDescriptor = read(VanillaBpParts.descriptorOf(PartKind.ADAPTER, TYPE_OF_THE_DUMMY_ADAPTER));
    final var adapterComplaint = VanillaBpParts
        .judge(PartKind.ADAPTER, TYPE_OF_THE_DUMMY_ADAPTER, adapterDescriptor.orElse(null))
        .map(VanillaBpParts.PartVerdict::message)
        .stream();

    return Stream
        .concat(platformComplaint, adapterComplaint)
        .toList();

  }

  /**
   * @param path The path of a descriptor
   * @return The descriptor, or empty if the application does not carry it
   */
  private static Optional<Properties> read(
      final String path) {

    try (var in = VersionDescriptorCheck.class.getResourceAsStream('/' + path)) {
      if (in == null) {
        return Optional.empty();
      }
      final var result = new Properties();
      result.load(in);
      return Optional.of(result);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

  }

}
