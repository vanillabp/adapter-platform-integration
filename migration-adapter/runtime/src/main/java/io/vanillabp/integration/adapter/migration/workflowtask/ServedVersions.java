package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The process versions ONE handler method serves, and where that statement came from.
 * <p>
 * This is the version half of every handler VanillaBP wires: a
 * <code>&#64;WorkflowTask</code>, <code>&#64;WorkflowStartedByBpms</code> or
 * <code>&#64;WorkflowEnded</code> method of the core, and a method carrying the
 * annotation of an extension
 * ({@link io.vanillabp.integration.extension.spi.handler.HandlerContract}). All of them
 * ask the same three questions - does this method serve the version a delivery came
 * from, do two methods serve a common version, which version tags does a method name -
 * and all of them ask them here, so the answer is the same everywhere (see decision 56
 * in the repository's DECISIONS.md).
 * <p>
 * What a single specification means is {@link VersionRange}; a method may name several,
 * and it serves a version ANY of them covers.
 *
 * @see InheritedVersions
 */
public final class ServedVersions {

  /**
   * What a method naming no version serves - every version, which is also the only
   * thing served by a delivery whose version the BPMS did not report (decision 20 in
   * the repository's DECISIONS.md).
   */
  public static final ServedVersions EVERY_VERSION = new ServedVersions(
      List.of(VersionRange.parse("*", "the default")), null);

  private final List<VersionRange> ranges;

  /**
   * Where the ranges came from if the method named none itself: the
   * <code>&#64;BpmnProcess</code> declaration the handlers of its class were registered
   * for. <code>null</code> where the method names its own, whose messages need no
   * origin - the attribute is in front of whoever reads them.
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
   * @return Whether every version is served - what a method naming no version parses
   *         to, and what makes it inherit the ranges of its class
   */
  public boolean everyVersion() {

    return ranges.stream().allMatch(VersionRange::everyVersion);

  }

  /**
   * @param processVersion The version the BPMS reported
   * @return Whether this method serves it, decided without asking a BPMS
   */
  public boolean matches(
      final String processVersion) {

    return matches(processVersion, VersionRange.NO_RESOLVER);

  }

  /**
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
   * Whether two methods serve at least one common process version - what makes two
   * methods wired to the same element ambiguous. Disjoint ranges are a legitimate way of
   * serving several versions of a process, which is why <code>1-2</code> next to
   * <code>&gt;2</code> is fine and <code>1-3</code> next to <code>2</code> is not.
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
   * @return The version tags these specifications name, each once - empty for
   *         specifications no BPMS has to be asked about
   */
  public List<String> versionTags() {

    return ranges
        .stream()
        .flatMap(range -> range.versionTags().stream())
        .distinct()
        .toList();

  }

  /**
   * @return Whether these ranges come from the class rather than from the method
   */
  public boolean inherited() {

    return inheritedFrom != null;

  }

  /**
   * The specification(s), for messages about a method serving no version the BPMS
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
   * The specification(s) plus, where the method named none itself, the declaration they
   * came from. Every message about an ambiguity or a method serving nothing uses this: a
   * complaint about a range the reader cannot see in front of the method reads like a
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
   * Where the ranges came from, ready to be appended to a description of the method -
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
