package io.vanillabp.adapters.dummy.springboot;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.adapters.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

@Configuration
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyAdapterConfiguration extends AdapterConfigurationBase {

  @Bean
  @Qualifier("JUHU")
  Object juhu() {
    return new Object();
  }

  @Override
  public String getAdapterId() {
    return "dummy";
  }

}
