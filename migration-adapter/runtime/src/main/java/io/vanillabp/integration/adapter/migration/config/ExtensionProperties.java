package io.vanillabp.integration.adapter.migration.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings of one extension: everything below
 * <code>vanillabp.extensions.&lt;extension&gt;</code> globally, below
 * <code>vanillabp.workflow-modules.&lt;id&gt;.extensions.&lt;extension&gt;</code> per
 * workflow module, below
 * <code>vanillabp.workflow-modules.&lt;id&gt;.workflows.&lt;process&gt;.extensions.&lt;extension&gt;</code>
 * per workflow and below
 * <code>...workflows.&lt;process&gt;.tasks.&lt;task&gt;.extensions.&lt;extension&gt;</code>
 * per task, keyed by the rest of the path (<code>rest.base-url</code>). Each of those four
 * levels has an adapter section of its own
 * (<code>...adapters.&lt;adapter&gt;.extensions.&lt;extension&gt;</code>), which makes the
 * eight positions {@link io.vanillabp.integration.extension.spi.settings.SettingsResolution}
 * walks.
 * <p>
 * VanillaBP deliberately does not know what the keys mean - an extension binds and
 * validates its own, typed, the way an adapter binds the keys below its adapter id. What
 * the core owns is the LOCATION, so every extension is configured in one place and
 * beside the rest of the setup, and the resolution of the four levels
 * ({@link MigrationAdapterProperties#extensionProperties(String, String, String, String, String)}).
 */
public final class ExtensionProperties {

  private ExtensionProperties() {
  }

  /**
   * Merges the positions over one another, per key: a position which says something about
   * one key keeps whatever a less specific position says about the rest. That is the same
   * most-specific-wins rule an adapter setting follows (see decision 7 in the
   * repository's DECISIONS.md), and it is per key rather than per section because an
   * extension configured once for the application should be able to change one value for
   * one workflow without repeating the rest.
   *
   * @param positionsFromLeastSpecific The settings of each position, least specific first,
   *          as {@link io.vanillabp.integration.extension.spi.settings.SettingsResolution#positions}
   *          answers them; entries may be <code>null</code> or empty where a position says
   *          nothing
   * @return The merged settings, never <code>null</code>
   */
  public static Map<String, String> merge(
      final List<Map<String, String>> positionsFromLeastSpecific) {

    final var merged = new LinkedHashMap<String, String>();
    positionsFromLeastSpecific
        .stream()
        .filter(position -> (position != null) && !position.isEmpty())
        .forEach(merged::putAll);
    return Map.copyOf(merged);

  }

}
