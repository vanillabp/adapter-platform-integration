package io.vanillabp.integration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * The section <code>vanillabp.outbox.gruelbox.*</code>: whether the gruelbox-based
 * outbox takes the place of the one VanillaBP writes itself.
 * <p>
 * Spring Boot is the only platform which builds that store, so the key is bound here and
 * not in the core model, which carries what every platform has. The auto-configurations
 * of the two stores decide by a condition, and a condition reads the environment without
 * any bean. This class binds the key anyway, for two reasons: the name is written once
 * and the conditions cite it, and a value which is no boolean ends the startup naming
 * this key instead of counting as "off", which {@code VanillaBpConfigurationBindingTest}
 * holds.
 * <p>
 * A development environment proposes the key because this class stands in this module:
 * the annotation processor of Spring Boot writes the metadata of what it compiles here.
 * The text it shows comes from
 * <code>META-INF/additional-spring-configuration-metadata.json</code>, where the key is
 * described next to the rest of the outbox family and where a description written by
 * hand wins over the one taken from the field below.
 */
@Getter
@Setter
@ConfigurationProperties(GruelboxOutboxProperties.PREFIX)
public class GruelboxOutboxProperties {

  /**
   * The section this class binds. It hangs below the outbox section of the core model,
   * which knows the keys of the two stores VanillaBP writes itself.
   */
  public static final String PREFIX = MigrationAdapterProperties.PREFIX
      + ".outbox.gruelbox";

  /**
   * The key the conditions of the outbox auto-configurations read, written the way an
   * application writes it.
   */
  public static final String ENABLED = PREFIX
      + ".enabled";

  /**
   * Whether the gruelbox-based outbox is built in place of the JDBC one VanillaBP writes
   * itself. It needs gruelbox on the classpath, which VanillaBP stopped bringing along
   * when that store stopped being the default, and an application which switches it on
   * without the library is told so at startup by
   * {@link io.vanillabp.integration.outbox.gruelbox.GruelboxMissingAutoConfiguration}.
   */
  private boolean enabled;

  /**
   * Spring Boot builds the empty instance and then fills it from the environment. An
   * application which writes nothing about gruelbox keeps the store VanillaBP writes
   * itself.
   */
  public GruelboxOutboxProperties() {

  }

}
