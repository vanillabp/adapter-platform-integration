package io.vanillabp.integration.adapter.migration.config;

import java.util.List;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * The adapter properties.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class AdapterProperties {

  @Builder.Default
  private List<String> prioritizedAdapters = List.of();

  /**
   * Where to load BPMN files from, which are specific to the adapter
   */
  private String resourcesLocation;

}
