package io.vanillabp.integration.adapter.migration.workflowtask;

/**
 * One version specification of <code>&#64;WorkflowTask(version = ...)</code>:
 * <code>*</code> (all versions), <code>1</code> (exactly), <code>1-3</code>
 * (inclusive range), <code>&gt;3</code>, <code>&lt;3</code>. A process version
 * which is not numeric only matches <code>*</code> or an exactly equal
 * specification.
 */
public class VersionRange {

  private final String spec;

  private VersionRange(
      final String spec) {

    this.spec = spec;

  }

  public static VersionRange parse(
      final String spec,
      final String describedLocation) {

    final var trimmed = spec == null
        ? "*"
        : spec.trim();
    if (trimmed.isEmpty()) {
      return new VersionRange("*");
    }
    if (!trimmed.equals("*") && !trimmed.matches("\\d+") && !trimmed.matches("\\d+-\\d+") && !trimmed
        .matches("[<>]\\d+")) {
      throw new IllegalStateException(
          """
              Unsupported version specification '%s' of @WorkflowTask at %s! Supported formats: \
              '*' (all versions), '1' (exactly), '1-3' (inclusive range), '>3', '<3'."""
              .formatted(spec, describedLocation));
    }
    return new VersionRange(trimmed);

  }

  public boolean matches(
      final String processVersion) {

    if ((processVersion == null) || spec.equals("*")) {
      return true;
    }
    if (spec.equals(processVersion)) {
      return true;
    }
    final int version;
    try {
      version = Integer.parseInt(processVersion.trim());
    } catch (final NumberFormatException e) {
      return false;
    }
    if (spec.matches("\\d+")) {
      return Integer.parseInt(spec) == version;
    }
    if (spec.matches("\\d+-\\d+")) {
      final var boundaries = spec.split("-");
      return (version >= Integer.parseInt(boundaries[0])) && (version <= Integer.parseInt(boundaries[1]));
    }
    if (spec.startsWith(">")) {
      return version > Integer.parseInt(spec.substring(1));
    }
    return version < Integer.parseInt(spec.substring(1));

  }

  @Override
  public String toString() {

    return spec;

  }

}
