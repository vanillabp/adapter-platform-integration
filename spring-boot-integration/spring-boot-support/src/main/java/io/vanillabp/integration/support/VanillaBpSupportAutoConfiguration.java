package io.vanillabp.integration.support;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The only autoconfiguration of this module. It reports an application which has a
 * workflow module but no BPMS adapter.
 * <p>
 * <code>vanillabp-spring-boot-integration</code> configures everything else VanillaBP does
 * on Spring Boot, and a BPMS adapter brings it along. This module is on the classpath of
 * every workflow module. So it is the only place which can speak up when the adapter is
 * missing, and with it the integration.
 */
@AutoConfiguration
public class VanillaBpSupportAutoConfiguration {

  /**
   * Built by Spring Boot in every application which has
   * <code>vanillabp-spring-boot-support</code> on its classpath. A workflow module
   * compiles against that module alone. So this runs wherever a workflow module is, with
   * or without a BPMS adapter.
   */
  public VanillaBpSupportAutoConfiguration() {

  }

  /**
   * The check, registered as a {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor}
   * so it runs before the first bean of the application is created.
   *
   * @return The check
   */
  @Bean
  public static NoBpmsAdapterCheck vanillaBpNoBpmsAdapterCheck() {

    return new NoBpmsAdapterCheck();

  }

}
