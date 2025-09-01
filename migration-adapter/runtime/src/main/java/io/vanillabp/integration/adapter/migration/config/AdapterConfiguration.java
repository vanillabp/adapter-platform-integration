package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class AdapterConfiguration {

  private String resourcesLocation;

}
