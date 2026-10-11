package io.vanillabp.integration.adapter.migration.config;

/**
 * What happens when workflows still run on a process version which the configuration faded
 * out (<code>vanillabp.adapters.&lt;id&gt;.outfaded-versions</code>).
 * <p>
 * Those workflows will run into an incident at their next task whose definition nobody
 * serves. So the finding is FATAL either way. The operator decides whether that also stops
 * the application. Living with old instances, or with an application which does not start, is
 * a choice which nobody but the operator can make.
 */
public enum OutfadedVersionsInUsePolicy {

  /**
   * The default. The finding is logged as FATAL, and names the process, the version, and how
   * many workflows are affected. The application starts.
   */
  LOG,

  /**
   * The boot stops with the same message.
   */
  FAIL

}
