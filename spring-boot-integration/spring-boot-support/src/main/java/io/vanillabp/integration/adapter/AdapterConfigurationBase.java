package io.vanillabp.integration.adapter;

/**
 * A abstract class to be extended by adapters.
 * It is used to collect all adapters available during autoconfiguration of VanillaBP Spring Boot integration.
 */
public abstract class AdapterConfigurationBase {

  /**
   * @return The ID of the adapter
   */
  public abstract String getAdapterId();

}
