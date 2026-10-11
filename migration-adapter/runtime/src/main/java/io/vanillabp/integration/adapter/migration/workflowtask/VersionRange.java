package io.vanillabp.integration.adapter.migration.workflowtask;

import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;

/**
 * One version specification of the <code>version</code> attribute of
 * <code>&#64;WorkflowTask</code>, <code>&#64;WorkflowStartedByBpms</code> or
 * <code>&#64;WorkflowEnded</code>.
 * <p>
 * A limit is either a version as the BPMS counts it (a number for Camunda 7 and Camunda 8),
 * or a version TAG which the modeller gave a version (<code>camunda:versionTag</code>,
 * <code>zeebe:versionTag</code>):
 * <ul>
 * <li><code>*</code>: every version (the default);</li>
 * <li><code>3</code> or <code>release-2024</code>: exactly that version, or every version
 * with that tag;</li>
 * <li><code>1-3</code> or <code>v1.0..v2.0</code>: a range, both limits included;</li>
 * <li><code>&gt;3</code>, <code>&gt;=3</code>, <code>&lt;v2.0</code>,
 * <code>&lt;=v2.0</code>: open ended.</li>
 * </ul>
 * Ranges accept <code>..</code> as well as <code>-</code> as their separator. A limit which
 * names a tag with a <code>-</code> in it HAS to use <code>..</code>. Otherwise nobody can
 * tell a range from a tag.
 * <p>
 * <b>What "greater" and "less" mean:</b> the order of the deployments. For a BPMS which
 * counts versions upwards, that IS the numeric order. That is why a specification made of
 * numbers is compared with the reported version straight away. As soon as a tag is
 * involved, both sides are resolved through the
 * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog} of the BPMS
 * (see {@link ProcessVersionResolver}). A tag alone says nothing about its position.
 * Without a resolver, or if the BPMS does not know a tag, such a specification matches
 * nothing.
 * <p>
 * Decision 20 in the repository's DECISIONS.md says why a delivery without a reported
 * version is served only by a method without a version range.
 */
public class VersionRange {

  private enum Kind {
    /**
     * <code>*</code>: every version.
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
     * <code>&gt;x</code> or <code>&gt;=x</code>.
     */
    GREATER,
    /**
     * <code>&lt;x</code> or <code>&lt;=x</code>.
     */
    LESS
  }

  /**
   * Resolves a version identifier or a version tag to the deployed version of ONE BPMN
   * process (see {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog}).
   */
  public interface ProcessVersionResolver {

    /**
     * Asks the BPMS what it holds under that version or tag.
     * <p>
     * An unknown tag is not an error here. The application may name a tag which nobody has
     * deployed yet, and a specification which cannot be placed simply matches nothing. The
     * code which wires the handlers reports such a tag once, and names the method.
     *
     * @param versionOrVersionTag A version identifier or a version tag
     * @return The deployed version or <code>null</code> if the BPMS does not know it
     */
    DeployedProcessVersion resolve(
        String versionOrVersionTag);

  }

  /**
   * Used wherever no BPMS can be asked. A version specification made of numbers never
   * needs one.
   */
  public static final ProcessVersionResolver NO_RESOLVER = versionOrVersionTag -> null;

  private static final String RANGE_SEPARATOR = "..";

  private final String spec;

  private final Kind kind;

  /**
   * The lower limit of a {@link Kind#RANGE}, the single value of {@link Kind#EXACT}, and the
   * excluded limit of {@link Kind#GREATER}.
   */
  private final String lower;

  /**
   * The upper limit of a {@link Kind#RANGE}, and the excluded limit of
   * {@link Kind#LESS}.
   */
  private final String upper;

  /**
   * Whether the limit of a {@link Kind#GREATER} or {@link Kind#LESS} belongs to the range.
   * <code>&gt;=3</code> and <code>&lt;=3</code> say it does.
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

  /**
   * Reads one written specification, while the application boots.
   * <p>
   * Nothing is asked of a BPMS here. A limit which is not a number is kept as written and
   * resolved later, because the deployment has not happened yet at this point.
   * <p>
   * A specification which cannot be read ends the boot with an
   * {@link IllegalStateException}, which names the location and every supported format. That
   * is on purpose. Otherwise a version attribute with a typo would match nothing, and the
   * task would stay untouched until somebody looks for it.
   *
   * @param spec What the <code>version</code> attribute says. <code>null</code> and an
   *          empty text both mean every version
   * @param describedLocation Where that text was written, for the message about a
   *          specification which cannot be read
   * @return The specification, ready to be matched
   */
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
    // anything else is a single version or a version tag. A tag may contain a '-'. That is
    // why a RANGE of tags is written with '..'
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
   * A BPMS which reports NO version (<code>null</code>) is served by <code>*</code> only.
   * Whether a version lies within <code>1-3</code> cannot be answered without knowing the
   * version. Answering it with "yes" would run a method for a version which its author
   * excluded. That is also why the choice is not left to the order in which reflection
   * happens to return the methods.
   *
   * @param processVersion The version the BPMS reported
   * @param resolver Resolves version tags of the BPMN process the version belongs to
   * @return Whether this specification covers that version
   */
  public boolean matches(
      final String processVersion,
      final ProcessVersionResolver resolver) {

    if (kind == Kind.ALL) {
      return true;
    }
    if (processVersion == null) {
      return false;
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
      // a tag. It matches the version which has it. Only the BPMS knows that, and it may have
      // changed since this application booted (another cluster node deploying a new version
      // with the same tag)
      final var reported = resolve(processVersion, resolver);
      return (reported != null) && lower.equals(reported.versionTag());
    }
    if (!versionTags().isEmpty()) {
      // the reported version first, as for a single tag above. The tag may have been unknown
      // when this node booted, and then the BPMS is not asked about it again for a while. A
      // version which this node has not seen yet makes it ask the BPMS once, and that answer
      // teaches the tag as well
      resolve(processVersion, resolver);
    }
    final var version = ordinalOf(processVersion, resolver);
    final var boundaries = boundaries(resolver);
    if ((version == null) || (boundaries == null) || !sameScale(version, boundaries[0]) || !sameScale(version,
        boundaries[1])) {
      return false;
    }
    // the limits are closed. '>' and '<' moved theirs by one when they were built
    return ((boundaries[0] == null) || (version.value() >= boundaries[0]
        .value())) && ((boundaries[1] == null) || (version.value() <= boundaries[1].value()));

  }

  /**
   * Whether this specification can be met at all on a BPMS which keeps no list of its
   * deployed versions (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}).
   * <p>
   * Without a list, nothing can be placed in the order in which the versions were deployed.
   * So a range is never met. What is left is a plain comparison with the value which the
   * delivery carries. <code>*</code> takes any value, and a single version tag is met by a
   * delivery which carries that tag.
   *
   * @param reported What a delivery of that BPMS carries as its process version
   * @return Whether a delivery can meet this specification there
   */
  public boolean canBeMetWithoutACatalog(
      final io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion reported) {

    return switch (kind) {
      case ALL -> true;
      case EXACT ->
        (reported == io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion.VERSION_TAG) && !isNumeric(
            lower);
      default -> false;
    };

  }

  /**
   * Whether this specification restricts anything at all.
   * <p>
   * A method whose attribute nobody wrote and a method which names <code>*</code> are the
   * same thing. So both of them inherit the range of their class.
   *
   * @return Whether this specification covers every version. A <code>version</code> attribute
   *         which nobody wrote parses to that, and it makes a handler method inherit the
   *         range of its class
   */
  public boolean everyVersion() {

    return kind == Kind.ALL;

  }

  /**
   * The reason why a delivery of a BPMS which reports no version found no method, ready to
   * be added to a message. It is empty for a reported version, where the version itself is
   * the reason.
   *
   * @param processVersion The version the BPMS reported
   * @param someRangeIsInherited Whether one of the methods which were skipped serves a range
   *          which comes from the <code>&#64;BpmnProcess</code> of its class, not from its own
   *          attribute
   * @return The sentence to append, or an empty string
   */
  public static String noVersionReportedHint(
      final String processVersion,
      final boolean someRangeIsInherited) {

    if (processVersion != null) {
      return "";
    }
    final var hint = " This BPMS reports no process version, so only a method whose version is '*' "
        + "(the default) can serve the delivery - whether a version lies within a range nobody "
        + "reported is not something VanillaBP guesses.";
    // the surprise this is for: the method has no attribute, so its author reads a
    // complaint about a range which is nowhere near it
    return someRangeIsInherited
        ? hint
            + " A method which inherits its range from the @BpmnProcess of its class is as "
            + "restricted as one naming it, so a delivery like this needs a method whose class "
            + "names no version either."
        : hint;

  }

  /**
   * Whether two specifications cover at least one common version. That makes two methods
   * wired to the same BPMN element ambiguous. Ranges which do not overlap are a valid way to
   * serve several versions of a process. That is why <code>1-2</code> next to
   * <code>&gt;2</code> is fine, and <code>1-3</code> next to <code>2</code> is not.
   * <p>
   * A specification which names a version tag can only be placed once the BPMS has resolved
   * it. So without a resolver, two of them count as NOT overlapping unless they are written
   * the same. The check runs a second time when the tags are resolved after the deployment
   * (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#resolveProcessVersions}).
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
    // specifications which are written the same overlap by definition. No BPMS is needed
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
   * The version tags which this specification names: the limits which are not numbers. It is
   * empty for a specification about which a BPMS never has to be asked.
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
   * A position in the order of the deployments, and how it was found: the version number of
   * a BPMS which counts upwards, or a deployment timestamp. Positions of different scales
   * cannot be compared.
   */
  private record Ordinal(
                         long value,
                         boolean numeric) {
  }

  /**
   * The closed interval which this specification covers. An end without a limit is
   * <code>null</code>. The whole answer is <code>null</code> if a limit names a version tag
   * which the resolver does not know.
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
   * Where a version or a version tag sits in the order of the deployments: the number itself
   * for a BPMS which counts versions upwards, otherwise the deployment timestamp.
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
   * Whether two positions were found the same way. A version number and a deployment
   * timestamp say nothing about each other.
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
