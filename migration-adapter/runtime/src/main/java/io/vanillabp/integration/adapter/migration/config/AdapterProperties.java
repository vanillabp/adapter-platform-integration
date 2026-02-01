package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class AdapterProperties {

  /**
   * Where to load BPMN files from, which are specific to the adapter
   */
  private String resourcesLocation;

}
