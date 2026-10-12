package io.vanillabp.integration.adapter;

/**
 * The base class of an adapter's configuration class. The autoconfiguration of
 * VanillaBP's Spring Boot integration uses it to collect all adapters there are.
 * <p>
 * <i>Hint:</i> Do not build any other beans in a configuration class which extends this
 * class. This bean has to be built early, and the dependencies of other beans might
 * change the order.
 */
public abstract class AdapterConfigurationBase {

  /**
   * Called by the adapter's own configuration class, which Spring builds as a bean. There
   * is no state here. The base class exists so the integration can collect the adapter
   * types by asking the bean factory for this one type.
   */
  protected AdapterConfigurationBase() {

  }

  /**
   * The kind of BPMS this adapter talks to, spelled the way the configuration spells it,
   * e.g. <code>camunda8</code>. It is not an adapter id. An application may configure
   * several ids of one type. A migration between two setups of the same BPMS is made of
   * exactly that.
   * <p>
   * What the integration does with it: the types of all these beans are the adapter types
   * of {@code ClasspathFacts}. The conventions are derived from them before the
   * configuration is validated. So an application with a single adapter type on the
   * classpath needs no adapter section at all (see decision 8 in the repository's
   * DECISIONS.md).
   *
   * @return The adapter's type, never <code>null</code>
   */
  public abstract String getAdapterType();

}
