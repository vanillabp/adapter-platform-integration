package io.vanillabp.integration.adapter.migration.workflowtask;

import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;

/**
 * One version specification of the <code>version</code> attribute of
 * <code>&#64;WorkflowTask</code>, <code>&#64;WorkflowStartedByBpms</code> or
 * <code>&#64;WorkflowEnded</code>.
 * <p>
 * A boundary is either a version as the BPMS counts it (a number for Camunda 7 and
 * Camunda 8) or a version TAG the modeller gave a version
 * (<code>camunda:versionTag</code>, <code>zeebe:versionTag</code>):
 * <ul>
 * <li><code>*</code> - every version (the default);</li>
 * <li><code>3</code> or <code>release-2024</code> - exactly that version
 * respectively every version carrying that tag;</li>
 * <li><code>1-3</code> or <code>v1.0..v2.0</code> - a range, both boundaries
 * included;</li>
 * <li><code>&gt;3</code>, <code>&gt;=3</code>, <code>&lt;v2.0</code>,
 * <code>&lt;=v2.0</code> - open ended.</li>
 * </ul>
 * Ranges accept <code>..</code> as well as <code>-</code> as their separator, and a
 * boundary naming a tag which contains a <code>-</code> HAS to use <code>..</code> -
 * otherwise there is no telling a range from a tag.
 * <p>
 * <b>What "greater" and "less" mean:</b> the deployment order. For a BPMS counting
 * versions upwards that IS the numeric order, which is why a specification made of
 * numbers is compared to the reported version straight away. As soon as a tag is
 * involved, both sides are resolved through the
 * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog} of the
 * BPMS (see {@link ProcessVersionResolver}) - a tag says nothing about its position
 * on its own. Without a resolver, or if the BPMS does not know a tag, such a
 * specification matches nothing.
 */
public class VersionRange {

  private enum Kind {
    /**
     * <code>*</code> - every version.
     */
    ALL,
    /**
     * A single version or version tag.
     */
    EXACT,
    /**
     * Both boundaries included.
     */
    RANGE,
    /**
     * <code>&gt;x</code> respectively <code>&gt;=x</code>.
     */
    GREATER,
    /**
     * <code>&lt;x</code> respectively <code>&lt;=x</code>.
     */
    LESS
  }

  /**
   * Resolves a version identifier or a version tag to the deployed version of ONE
   * BPMN process - see
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog}.
   */
  public interface ProcessVersionResolver {

    /**
     * @param versionOrVersionTag A version identifier or a version tag
     * @return The deployed version or <code>null</code> if the BPMS does not know it
     */
    DeployedProcessVersion resolve(
        String versionOrVersionTag);

  }

  /**
   * Used wherever no BPMS can be asked (a version specification made of numbers
   * never needs one).
   */
  public static final ProcessVersionResolver NO_RESOLVER = versionOrVersionTag -> null;

  private static final String RANGE_SEPARATOR = "..";

  private final String spec;

  private final Kind kind;

  /**
   * The lower boundary of a {@link Kind#RANGE}, the single value of
   * {@link Kind#EXACT} and the excluded boundary of {@link Kind#GREATER}.
   */
  private final String lower;

  /**
   * The upper boundary of a {@link Kind#RANGE} and the excluded boundary of
   * {@link Kind#LESS}.
   */
  private final String upper;

  /**
   * Whether the boundary of a {@link Kind#GREATER} or {@link Kind#LESS} belongs to the
   * range - <code>&gt;=3</code> and <code>&lt;=3</code> say it does.
   */
  private final boolean boundaryIncluded;

  private VersionRange(
      final String spec,
      final Kind kind,
      final String lower,
      final String upper) {

    this(spec, kind, lower, upper, false);

  }

  private VersionRange(
      final String spec,
      final Kind kind,
      final String lower,
      final String upper,
      final boolean boundaryIncluded) {

    this.spec = spec;
    this.kind = kind;
    this.lower = lower;
    this.upper = upper;
    this.boundaryIncluded = boundaryIncluded;

  }

  public static VersionRange parse(
      final String spec,
      final String describedLocation) {

    final var trimmed = spec == null
        ? "*"
        : spec.trim();
    if (trimmed.isEmpty() || trimmed.equals("*")) {
      return new VersionRange("*", Kind.ALL, null, null);
    }
    if (trimmed.chars().anyMatch(Character::isWhitespace)) {
      throw unsupported(spec, describedLocation, "it contains a blank");
    }
    if (trimmed.startsWith(">") || trimmed.startsWith("<")) {
      final var included = trimmed.startsWith(">=") || trimmed.startsWith("<=");
      final var boundary = trimmed.substring(included
          ? 2
          : 1);
      if (boundary.isEmpty()) {
        throw unsupported(spec, describedLocation, "the version after '%s' is missing"
            .formatted(included
                ? trimmed.substring(0, 2)
                : trimmed.substring(0, 1)));
      }
      return trimmed.startsWith(">")
          ? new VersionRange(trimmed, Kind.GREATER, boundary, null, included)
          : new VersionRange(trimmed, Kind.LESS, null, boundary, included);
    }
    final var explicitSeparator = trimmed.indexOf(RANGE_SEPARATOR);
    if (explicitSeparator >= 0) {
      final var lower = trimmed.substring(0, explicitSeparator);
      final var upper = trimmed.substring(explicitSeparator + RANGE_SEPARATOR.length());
      if (lower.isEmpty() || upper.isEmpty() || upper.contains(RANGE_SEPARATOR)) {
        throw unsupported(spec, describedLocation, "a range needs exactly one version on each "
            + "side of '..'");
      }
      return new VersionRange(trimmed, Kind.RANGE, lower, upper);
    }
    if (trimmed.matches("\\d+-\\d+")) {
      final var boundaries = trimmed.split("-");
      return new VersionRange(trimmed, Kind.RANGE, boundaries[0], boundaries[1]);
    }
    // anything else is a single version respectively a version tag - a tag may
    // contain a '-', which is why a RANGE of tags is written with '..'
    return new VersionRange(trimmed, Kind.EXACT, trimmed, null);

  }

  private static IllegalStateException unsupported(
      final String spec,
      final String describedLocation,
      final String reason) {

    return new IllegalStateException(
        """
            Unsupported version specification '%s' at %s: %s! Supported formats: '*' (all \
            versions), '3' or 'release-2024' (that version respectively that version tag), \
            '1-3' or 'v1.0..v2.0' (a range, both boundaries included), '>3', '>=3', '<v2.0' \
            and '<=v2.0' (open ended). Use '..' as the separator if a boundary is a version tag \
            containing a '-'."""
            .formatted(spec, describedLocation, reason));

  }

  /**
   * @param processVersion The version the BPMS reported
   * @return Whether this specification covers that version - always
   *         <code>true</code> for <code>null</code> (a BPMS which cannot report a
   *         version matches every method)
   */
  public boolean matches(
      final String processVersion) {

    return matches(processVersion, NO_RESOLVER);

  }

  /**
   * @param processVersion The version the BPMS reported
   * @param resolver Resolves version tags of the BPMN process the version belongs to
   * @return Whether this specification covers that version
   */
  public boolean matches(
      final String processVersion,
      final ProcessVersionResolver resolver) {

    if ((kind == Kind.ALL) || (processVersion == null)) {
      return true;
    }
    if (kind == Kind.EXACT) {
      if (lower.equals(processVersion)) {
        return true;
      }
      if (isNumeric(lower) && isNumeric(processVersion)) {
        return Long.parseLong(lower) == Long.parseLong(processVersion);
      }
      if (isNumeric(lower)) {
        return false;
      }
      // a tag: it matches the version carrying it - which only the BPMS knows, and
      // which may have changed since this application booted (another cluster node
      // deploying a new version tagged the same way)
      final var reported = resolve(processVersion, resolver);
      return (reported != null) && lower.equals(reported.versionTag());
    }
    final var version = ordinalOf(processVersion, resolver);
    final var boundaries = boundaries(resolver);
    if ((version == null) || (boundaries == null) || !sameScale(version, boundaries[0]) || !sameScale(version,
        boundaries[1])) {
      return false;
    }
    // the boundaries are closed - '>' and '<' moved theirs by one when they were built
    return ((boundaries[0] == null) || (version.value() >= boundaries[0]
        .value())) && ((boundaries[1] == null) || (version.value() <= boundaries[1].value()));

  }

  /**
   * Whether two specifications cover at least one common version - what makes two
   * methods wired to the same BPMN element ambiguous. Disjoint ranges are a
   * legitimate way of serving several versions of a process, which is why
   * <code>1-2</code> next to <code>&gt;2</code> is fine and <code>1-3</code> next to
   * <code>2</code> is not.
   * <p>
   * A specification naming a version tag can only be placed once the BPMS resolved
   * it, so without a resolver two of them are reported as NOT overlapping unless
   * they are written identically. The check runs a second time when the tags are
   * resolved after the deployment (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#resolveProcessVersions}).
   *
   * @param other The other specification
   * @param resolver Resolves version tags of the BPMN process both belong to
   * @return Whether both cover a common version
   */
  public boolean overlaps(
      final VersionRange other,
      final ProcessVersionResolver resolver) {

    if ((kind == Kind.ALL) || (other.kind == Kind.ALL)) {
      return true;
    }
    // identically written specifications overlap by definition - no BPMS needed
    if (spec.equals(other.spec)) {
      return true;
    }
    final var here = boundaries(resolver);
    final var there = other.boundaries(resolver);
    if ((here == null) || (there == null)) {
      return false;
    }
    if (!sameScale(here[0], there[0]) || !sameScale(here[0], there[1]) || !sameScale(here[1],
        there[0]) || !sameScale(here[1], there[1])) {
      return false;
    }
    return ((here[0] == null) || (there[1] == null) || (here[0].value() <= there[1]
        .value())) && ((there[0] == null) || (here[1] == null) || (there[0].value() <= here[1].value()));

  }

  /**
   * @param other The other specification
   * @return Whether both cover a common version, decided without asking a BPMS
   */
  public boolean overlaps(
      final VersionRange other) {

    return overlaps(other, NO_RESOLVER);

  }

  /**
   * The version tags this specification names - the boundaries which are not numbers.
   * Empty for a specification a BPMS never has to be asked about.
   *
   * @return The version tags named
   */
  public java.util.List<String> versionTags() {

    return java.util.stream.Stream
        .of(lower, upper)
        .filter(java.util.Objects::nonNull)
        .filter(boundary -> !isNumeric(boundary))
        .distinct()
        .toList();

  }

  /**
   * A position in the deployment order and how it was determined - the version number
   * of a BPMS counting upwards, or a deployment timestamp. Positions of different
   * scales are not comparable.
   */
  private record Ordinal(
                         long value,
                         boolean numeric) {
  }

  /**
   * The closed interval this specification covers, <code>null</code> for an
   * unbounded end - or <code>null</code> altogether if a boundary names a version tag
   * the resolver does not know.
   */
  private Ordinal[] boundaries(
      final ProcessVersionResolver resolver) {

    switch (kind) {
      case ALL:
        return new Ordinal[]{
            null, null
        };
      case EXACT: {
        final var value = ordinalOf(lower, resolver);
        return value == null
            ? null
            : new Ordinal[]{
                value, value
            };
      }
      case GREATER: {
        final var value = ordinalOf(lower, resolver);
        return value == null
            ? null
            : new Ordinal[]{
                new Ordinal(value.value() + (boundaryIncluded ? 0 : 1), value.numeric()), null
            };
      }
      case LESS: {
        final var value = ordinalOf(upper, resolver);
        return value == null
            ? null
            : new Ordinal[]{
                null, new Ordinal(value.value() - (boundaryIncluded ? 0 : 1), value.numeric())
            };
      }
      default: {
        final var from = ordinalOf(lower, resolver);
        final var to = ordinalOf(upper, resolver);
        return (from == null) || (to == null)
            ? null
            : new Ordinal[]{
                from, to
            };
      }
    }

  }

  /**
   * Where a version or a version tag sits in the deployment order: the number itself
   * for a BPMS counting versions upwards, the deployment timestamp otherwise.
   */
  private static Ordinal ordinalOf(
      final String versionOrVersionTag,
      final ProcessVersionResolver resolver) {

    if (isNumeric(versionOrVersionTag)) {
      return new Ordinal(Long.parseLong(versionOrVersionTag), true);
    }
    final var resolved = resolve(versionOrVersionTag, resolver);
    if (resolved == null) {
      return null;
    }
    if (isNumeric(resolved.version())) {
      return new Ordinal(Long.parseLong(resolved.version()), true);
    }
    return resolved.deployedAt() == null
        ? null
        : new Ordinal(resolved.deployedAt().toEpochMilli(), false);

  }

  /**
   * Whether two positions were determined the same way - a version number and a
   * deployment timestamp say nothing about each other.
   */
  private static boolean sameScale(
      final Ordinal one,
      final Ordinal other) {

    return (one == null) || (other == null) || (one.numeric() == other.numeric());

  }

  private static DeployedProcessVersion resolve(
      final String versionOrVersionTag,
      final ProcessVersionResolver resolver) {

    return resolver == null
        ? null
        : resolver.resolve(versionOrVersionTag);

  }

  private static boolean isNumeric(
      final String value) {

    return (value != null) && value.matches("\\d+");

  }

  @Override
  public String toString() {

    return spec;

  }

}
