package io.vanillabp.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * A thin Spring Boot binding of the <code>vanillabp.*</code> configuration tree, which
 * users write, onto the core model, which does not know the platform. Spring Boot binds
 * the core POJOs directly, with relaxed names, profiles and environment-variable
 * overrides. So the tree is modeled exactly once, in the core. Spring Boot picks up every
 * property added there without any platform code.
 * <p>
 * This class only exists to carry the {@link ConfigurationProperties} annotation. It also
 * lets the Spring Boot configuration processor write IDE metadata for the inherited
 * properties. All the model, the defaults ({@code normalize()}) and the validation live
 * in {@link MigrationAdapterProperties}.
 * <p>
 * BPMS adapters add their own keys to the same tree (e.g.
 * <code>vanillabp.adapters.&lt;id&gt;.rest-address</code>). For that, an adapter binds an
 * overlay class of its own, annotated with <code>@ConfigurationProperties("vanillabp")</code>.
 * Classes with the same prefix can live side by side. The JavaBean binding ignores keys
 * which this core view does not know.
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
public class VanillaBpConfigurationProperties extends MigrationAdapterProperties {

  /**
   * Spring Boot builds the empty instance and then fills the inherited fields from the
   * environment, setter by setter. So everything this object holds arrives after the
   * constructor ran, and nothing may be computed here.
   * <p>
   * Defaults and validation come even later, once the classpath facts are known. The
   * bean method which builds the {@link MigrationAdapterProperties} bean of this application
   * calls {@link MigrationAdapterProperties#validateProperties(io.vanillabp.integration.adapter.migration.config.ClasspathFacts, String)},
   * which derives the defaults first (see
   * {@link io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration}).
   */
  public VanillaBpConfigurationProperties() {

  }

}
