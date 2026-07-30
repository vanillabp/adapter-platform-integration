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
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
public class VanillaBpConfigurationProperties extends MigrationAdapterProperties {

}
