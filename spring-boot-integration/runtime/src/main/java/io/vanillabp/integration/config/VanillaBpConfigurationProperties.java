package io.vanillabp.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * Thin Spring Boot binding of the user-facing <code>vanillabp.*</code> configuration
 * tree onto the platform-neutral core model: the core POJOs are bound directly
 * (relaxed names, profiles and environment-variable overrides included), so the tree
 * is modeled exactly once - in the core - and every property added there is picked up
 * by Spring Boot without any platform code.
 * <p>
 * This class only exists to carry the {@link ConfigurationProperties} annotation (and
 * to let the Spring Boot configuration processor emit IDE metadata for the inherited
 * properties). All model, defaulting ({@code normalize()}) and validation logic lives
 * in {@link MigrationAdapterProperties}.
 * <p>
 * BPMS adapters contribute their own keys to the same tree (e.g.
 * <code>vanillabp.adapters.&lt;id&gt;.rest-address</code>) by binding an adapter-owned
 * overlay class annotated with <code>@ConfigurationProperties("vanillabp")</code> -
 * same-prefix classes coexist, and keys unknown to this core view are ignored by the
 * JavaBean binding.
 * <p>
 * One section of the tree belongs to this platform rather than to the core:
 * {@link GruelboxOutboxProperties} binds <code>vanillabp.outbox.gruelbox.*</code>,
 * because Spring Boot is the only platform which builds that store.
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
public class VanillaBpConfigurationProperties extends MigrationAdapterProperties {

  /**
   * Spring Boot builds the empty instance and then fills the inherited fields from the
   * environment, setter by setter. So everything this object holds arrives after the
   * constructor ran, and nothing may be computed here.
   * <p>
   * Defaulting and validation happen later still, once the classpath facts are known: the
   * bean method building the {@link MigrationAdapterProperties} bean of this application
   * calls {@link MigrationAdapterProperties#validateProperties(io.vanillabp.integration.adapter.migration.config.ClasspathFacts, String)},
   * which derives the defaults first (see
   * {@link io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration}).
   */
  public VanillaBpConfigurationProperties() {

  }

}
