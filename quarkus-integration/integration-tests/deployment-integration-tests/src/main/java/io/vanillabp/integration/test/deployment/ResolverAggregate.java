package io.vanillabp.integration.test.deployment;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the multi-instance resolver test.
 */
@Getter
@Setter
public class ResolverAggregate {

  private String id;

  private String resolved;

}
