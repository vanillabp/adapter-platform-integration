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

  /**
   * Resilience settings used when talking to BPMSs providing eventual consistency.
   * May be overridden on workflow module and workflow level - the most specific
   * block configured wins as a whole (see
   * {@link MigrationAdapterProperties#getResilienceFor(String, String)}).
   */
  private ResilienceProperties resilience;

}
