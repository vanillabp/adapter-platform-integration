package io.vanillabp.integration.test.inheritance;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the workflow service whose handler methods VanillaBP does not see.
 */
@Getter
@Setter
public class InvisibleHandlersAggregate {

  private String id;

  private String servedBy;

}
