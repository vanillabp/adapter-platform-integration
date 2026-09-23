package io.vanillabp.integration.adapter;

/**
 * An abstract class to be extended by adapters.
 * It is used to collect all adapters available during autoconfiguration of VanillaBP Spring Boot integration.
 * <p>
 * <i>Hint:</i> Do not build any other beans in configuration classes
 * deriving this base class because it is important to build this bean early
 * and dependencies of other beans might change the order.
 */
public abstract class AdapterConfigurationBase {

  /**
   * Called by the adapter's own configuration class, which Spring builds as a bean. There
   * is no state here: the base class exists so the integration can collect the adapter
   * types by asking the bean factory for this one type.
   */
  protected AdapterConfigurationBase() {

  }

  /**
   * The BPMS kind this adapter speaks to, spelled the way the configuration spells it,
   * e.g. <code>camunda8</code>. It is not an adapter id: an application may
   * configure several ids of one type, which is what a migration between two setups of the
   * same BPMS is made of.
   * <p>
   * What the integration does with it: the types of all these beans are the adapter types
   * of {@code ClasspathFacts}, and those are what the conventions are derived from before
   * the configuration is validated - an application with a single adapter type on the
   * classpath needs no adapter section at all (see decision 8 in the repository's
   * DECISIONS.md).
   *
   * @return The adapter's type, never <code>null</code>
   */
  public abstract String getAdapterType();

}
