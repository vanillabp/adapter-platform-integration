package io.vanillabp.integration.adapter.migration.config;

/**
 * What happens when workflows still run on a process version the configuration faded
 * out (<code>vanillabp.adapters.&lt;id&gt;.outfaded-versions</code>, story 57).
 * <p>
 * Those workflows will run into an incident at their next task of an unserved
 * definition, so the finding is FATAL either way. What the operator decides is whether
 * that also stops the application: living with old instances or with an application
 * which does not start is a trade nobody but the operator can make.
 */
public enum OutfadedVersionsInUsePolicy {

  /**
   * The default: the finding is logged as FATAL, naming the process, the version and
   * how many workflows are affected, and the application starts.
   */
  LOG,

  /**
   * The boot aborts with the same message.
   */
  FAIL

}
