package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.OutfadedVersionsInUsePolicy;

/**
 * The versions of a BPMN process an application declares obsolete:
 * <code>vanillabp.adapters.&lt;id&gt;.outfaded-versions</code>, resolvable per workflow
 * module and workflow like every adapter-scoped property.
 * <p>
 * A specification is written in the grammar of the <code>version</code> attribute (see
 * {@link VersionRange}), so nobody has to learn a second one, and a version is outfaded
 * as soon as ANY specification of the most specific configured level covers it.
 * Specifications naming a version TAG need the BPMS, which is why the resolver of the
 * process is handed in - the same one the annotations use.
 */
public class OutfadedProcessVersions {

  /**
   * The bound <code>vanillabp.*</code> tree, or <code>null</code> where nobody bound
   * one (test doubles registering workflow services directly) - then nothing is
   * outfaded.
   */
  private final MigrationAdapterProperties properties;

  private record SpecKey(
                         String workflowModuleId,
                         String bpmnProcessId,
                         String adapterId) {
  }

  /**
   * The parsed specifications per scope - parsing reports a broken specification with
   * the grammar, and doing that once per boot is enough.
   */
  private final Map<SpecKey, List<VersionRange>> parsed = new ConcurrentHashMap<>();

  public OutfadedProcessVersions(
      final MigrationAdapterProperties properties) {

    this.properties = properties;

  }

  /**
   * The specifications configured for that process and adapter, most specific level
   * wins.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return The parsed specifications, empty if nothing is outfaded
   * @throws IllegalStateException If a specification cannot be parsed (the message
   *           carries the whole grammar)
   */
  public List<VersionRange> specificationsFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (properties == null) {
      return List.of();
    }
    return parsed
        .computeIfAbsent(
            new SpecKey(workflowModuleId, bpmnProcessId, adapterId),
            key -> parse(key, configuredFor(key)));

  }

  /**
   * Whether that version is faded out - a tag is placed through the BPMS, and a tag no
   * BPMS knows covers nothing (same rule as the annotations).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @param version The version identifier the BPMS reported
   * @param resolver Resolves version tags of that process
   * @return Whether the version is outfaded
   */
  public boolean isOutfaded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final String version,
      final VersionRange.ProcessVersionResolver resolver) {

    return specificationsFor(workflowModuleId, bpmnProcessId, adapterId)
        .stream()
        .anyMatch(specification -> specification.matches(version, resolver));

  }

  /**
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param adapterId The adapter ID
   * @return What happens when workflows still run on an outfaded version
   */
  public OutfadedVersionsInUsePolicy policyFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    if (properties == null) {
      return OutfadedVersionsInUsePolicy.LOG;
    }
    final var configured = properties
        .resolveForAdapter(
            workflowModuleId,
            bpmnProcessId,
            null,
            adapterId,
            AdapterProperties::getOutfadedVersionsInUse);
    return configured == null
        ? OutfadedVersionsInUsePolicy.LOG
        : configured;

  }

  /**
   * The property name of that scope, for messages which have to name what to change.
   *
   * @param adapterId The adapter ID
   * @return The least specific spelling of the property
   */
  public static String propertyName(
      final String adapterId) {

    return "vanillabp.adapters.%s.outfaded-versions".formatted(adapterId);

  }

  private List<String> configuredFor(
      final SpecKey key) {

    final var configured = properties
        .resolveForAdapter(
            key.workflowModuleId(),
            key.bpmnProcessId(),
            null,
            key.adapterId(),
            adapter -> {
              final var versions = adapter.getOutfadedVersions();
              // a level which materialized the section but left the list empty is a
              // level which configured nothing - the next one decides
              return (versions == null) || versions.isEmpty()
                  ? null
                  : versions;
            });
    return configured == null
        ? List.of()
        : configured;

  }

  private static List<VersionRange> parse(
      final SpecKey key,
      final List<String> configured) {

    return configured
        .stream()
        .map(specification -> VersionRange
            .parse(
                specification,
                "'%s' of BPMN process '%s' (workflow module '%s')".formatted(
                    propertyName(key.adapterId()),
                    key.bpmnProcessId(),
                    key.workflowModuleId())))
        .toList();

  }

}
