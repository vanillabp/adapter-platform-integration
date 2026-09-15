package io.vanillabp.integration.extension.spi.settings;

/**
 * One level of the configuration tree of a plug-in: what the level says, what it says for
 * one adapter, and how to get to the next more specific level.
 * <p>
 * A plug-in binds its own tree and hands in one of these per level, so the section it is
 * configured in keeps the plug-in's own name. The Business Cockpit is configured below
 * <code>vanillabp.cockpit</code> because that is where it was configured in version 1;
 * a plug-in without such a past is configured below
 * <code>vanillabp.extensions.&lt;id&gt;</code>. The walk over the levels does not know the
 * difference, it knows one section per level (see decision 53 in the repository's
 * DECISIONS.md).
 *
 * @param <S> What a section is for the plug-in which implements this. A plug-in binding a
 *          typed tree answers with its own type and reads it with a typed accessor; the
 *          core answers with the <code>Map&lt;String, String&gt;</code> it binds below
 *          <code>vanillabp.extensions.&lt;id&gt;</code>
 */
public interface SettingsLevel<S> {

  /**
   * What this level says about the plug-in, regardless of the adapter asked about.
   *
   * @return The section, or <code>null</code> where this level says nothing
   */
  S settings();

  /**
   * What this level says about the plug-in for ONE adapter. A plug-in hangs on every
   * configured adapter separately, so two adapters of the same BPMS type may be told
   * different things at the same level, and what an adapter is told beats what the level
   * says in general.
   *
   * @param adapterId The adapter asked about
   * @return The section, or <code>null</code> where this level says nothing about that
   *         adapter
   */
  S settingsOfAdapter(
      String adapterId);

  /**
   * The next more specific level: the workflow module below the application, the workflow
   * below the workflow module, the task below the workflow.
   *
   * @param id The id of the more specific level, never <code>null</code>
   * @return The level, or <code>null</code> where nothing is configured below this one
   */
  SettingsLevel<S> levelBelow(
      String id);

}
