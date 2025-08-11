package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
public class AdapterConfiguration {

  private String resourcesLocation;

}
