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
   * @return The name of the adapter's type
   */
  public abstract String getAdapterType();

}
