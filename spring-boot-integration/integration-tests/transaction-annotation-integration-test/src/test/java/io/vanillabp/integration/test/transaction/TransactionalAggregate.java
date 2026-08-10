package io.vanillabp.integration.test.transaction;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the workflow service whose handler carries a transaction annotation.
 */
@Getter
@Setter
public class TransactionalAggregate {

  private String id;

  private String status;

}
