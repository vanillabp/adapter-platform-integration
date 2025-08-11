package io.vanillabp.integration.adapter.migration.config;

import java.util.List;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class AdapterProperties {

  @Builder.Default
  private List<String> prioritizedAdapters = List.of();

}
