package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The process versions ONE handler method serves, and where that statement came from.
 * <p>
 * This is the version half of every handler which VanillaBP wires: a
 * <code>&#64;WorkflowTask</code>, <code>&#64;WorkflowStartedByBpms</code> or
 * <code>&#64;WorkflowEnded</code> method of the core, and a method with the annotation of
 * an extension ({@link io.vanillabp.integration.extension.spi.handler.HandlerContract}). All
 * of them ask the same three questions. Does this method serve the version a delivery came
 * from? Do two methods serve a common version? Which version tags does a method name? All
 * of them ask these questions here, so the answer is the same everywhere (see decision 56
 * in the repository's DECISIONS.md).
 * <p>
 * {@link VersionRange} says what a single specification means. A method may name several,
 * and it serves a version which ANY of them covers.
 *
 * @see InheritedVersions
 */
public final class ServedVersions {

  /**
   * What a method which names no version serves: every version. It is also the only thing
   * which serves a delivery whose version the BPMS did not report (decision 20 in the
   * repository's DECISIONS.md).
   */
  public static final ServedVersions EVERY_VERSION = new ServedVersions(
      List.of(VersionRange.parse("*", "the default")), null);

  private final List<VersionRange> ranges;

  /**
   * Where the ranges came from, if the method named none itself: the
   * <code>&#64;BpmnProcess</code> declaration for which the handlers of its class were
   * registered. It is <code>null</code> where the method names its own. Their messages need
   * no origin, because the reader sees the attribute.
   */
  private final String inheritedFrom;

  ServedVersions(
      final List<VersionRange> ranges,
      final String inheritedFrom) {

    this.ranges = ranges;
    this.inheritedFrom = inheritedFrom;

  }

  /**
   * Reads what the <code>version</code> attribute of an annotation names.
   *
   * @param specifications The specifications written on the method, none for
   *          "every version"
   * @param describedLocation The method carrying them, for a message about a
   *          specification which cannot be read
   * @return What that method serves
   */
  public static ServedVersions parse(
      final String[] specifications,
      final String describedLocation) {

    return parse(
        specifications == null
            ? List.of()
            : Arrays.asList(specifications),
        describedLocation);

  }

  /**
   * Reads what the <code>version</code> attribute of an annotation names.
   *
   * @param specifications The specifications written on the method, none for
   *          "every version"
   * @param describedLocation The method carrying them, for a message about a
   *          specification which cannot be read
   * @return What that method serves
   */
  public static ServedVersions parse(
      final List<String> specifications,
      final String describedLocation) {

    if ((specifications == null) || specifications.isEmpty()) {
      return EVERY_VERSION;
    }
    return new ServedVersions(
        specifications
            .stream()
            .map(specification -> VersionRange.parse(specification, describedLocation))
            .toList(), null);

  }

  /**
   * @param inheritedFrom The declaration these ranges were read from
   * @return The same ranges, naming their origin in every message about them
   */
  ServedVersions inheritedFrom(
      final String inheritedFrom) {

    return new ServedVersions(ranges, inheritedFrom);

  }

  /**
   * Whether this method is unrestricted.
   * <p>
   * Two places rely on it. A method like this inherits the ranges of its class. And it is
   * the only kind of method which serves a delivery whose version the BPMS did not report.
   *
   * @return Whether every version is served. A method which names no version parses to
   *         that, and it makes the method inherit the ranges of its class
   */
  public boolean everyVersion() {

    return ranges.stream().allMatch(VersionRange::everyVersion);

  }

  /**
   * Whether a delivery of a BPMS which keeps no list of its deployed versions can ever reach
   * this method (see {@link VersionRange#canBeMetWithoutACatalog}). A method serves a version
   * which ANY of its specifications covers. So one specification which can be met is
   * enough.
   *
   * @param reported What a delivery of that BPMS carries as its process version
   * @return Whether such a delivery can reach this method
   */
  public boolean canBeMetWithoutACatalog(
      final io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion reported) {

    return ranges
        .stream()
        .anyMatch(range -> range.canBeMetWithoutACatalog(reported));

  }

  /**
   * Whether this method serves that version, using numbers alone.
   * <p>
   * For ranges made of numbers, that is the whole answer. A range which names a version tag
   * needs the BPMS. So use {@link #matches(String, VersionRange.ProcessVersionResolver)}
   * wherever a tag may turn up.
   *
   * @param processVersion The version the BPMS reported
   * @return Whether this method serves it, decided without asking a BPMS
   */
  public boolean matches(
      final String processVersion) {

    return matches(processVersion, VersionRange.NO_RESOLVER);

  }

  /**
   * Whether this method serves the version a delivery came from. Every dispatch asks this.
   * <p>
   * A method serves a version which ANY of its specifications covers. Where the BPMS
   * reported no version, only a method which names no range at all is served (decision 20
   * in the repository's DECISIONS.md).
   *
   * @param processVersion The version the BPMS reported, <code>null</code> where it
   *          reports none
   * @param resolver Resolves version tags of the BPMN process the version belongs to
   * @return Whether this method serves it
   */
  public boolean matches(
      final String processVersion,
      final VersionRange.ProcessVersionResolver resolver) {

    return ranges
        .stream()
        .anyMatch(range -> range.matches(processVersion, resolver));

  }

  /**
   * Whether two methods serve at least one common process version. That makes two methods
   * wired to the same element ambiguous. Ranges which do not overlap are a valid way to serve
   * several versions of a process. That is why <code>1-2</code> next to <code>&gt;2</code> is
   * fine, and <code>1-3</code> next to <code>2</code> is not.
   *
   * @param other What the other method serves
   * @param resolver Resolves version tags of the BPMN process both belong to
   * @return Whether both serve a common version
   */
  public boolean overlaps(
      final ServedVersions other,
      final VersionRange.ProcessVersionResolver resolver) {

    return ranges
        .stream()
        .anyMatch(range -> other.ranges
            .stream()
            .anyMatch(otherRange -> range.overlaps(otherRange, resolver)));

  }

  /**
   * The tags which have to be resolved before these ranges can be placed in the order of
   * the deployments.
   * <p>
   * If the answer is empty for every method of a process, the BPMS is not asked at all. A
   * tag which the BPMS does not know is reported, and the message names the method it came
   * from.
   *
   * @return The version tags which these specifications name, each once. Empty for
   *         specifications about which no BPMS has to be asked
   */
  public List<String> versionTags() {

    return ranges
        .stream()
        .flatMap(range -> range.versionTags().stream())
        .distinct()
        .toList();

  }

  /**
   * Whether a message about these ranges has to explain where they sit.
   * <p>
   * A range which the method does not have is the surprising case. Its author reads a
   * complaint about a version attribute which is nowhere near the method.
   *
   * @return Whether these ranges come from the class and not from the method
   */
  public boolean inherited() {

    return inheritedFrom != null;

  }

  /**
   * The specifications, for messages about a method which serves no version the BPMS
   * holds.
   *
   * @return The specifications, comma separated
   */
  public String describe() {

    return ranges
        .stream()
        .map(VersionRange::toString)
        .map("'%s'"::formatted)
        .collect(Collectors.joining(", "));

  }

  /**
   * The specifications and, where the method named none itself, the declaration they came
   * from. Every message about an ambiguity, or about a method which serves nothing, uses
   * this. A complaint about a range which the reader cannot see at the method looks like a
   * defect of VanillaBP.
   *
   * @return The specifications and their origin
   */
  public String describeWithOrigin() {

    return inheritedFrom == null
        ? describe()
        : "%s, inherited from %s".formatted(describe(), inheritedFrom);

  }

  /**
   * Where the ranges came from, ready to be added to a description of the method. It is
   * empty where the method names them itself.
   *
   * @return The clause to append, or an empty string
   */
  public String describeOrigin() {

    return inheritedFrom == null
        ? ""
        : ", which inherits the range of %s".formatted(inheritedFrom);

  }

  @Override
  public String toString() {

    return describeWithOrigin();

  }

}
