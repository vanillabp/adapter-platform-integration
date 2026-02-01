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
public class AdaptersConfigurationProperties {

  /**
   * Order of adapters to be used for running {@link io.vanillabp.spi.process.ProcessService} methods.
   */
  @Builder.Default
  private List<String> prioritizedAdapters = List.of();

}
