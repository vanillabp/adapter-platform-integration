package io.vanillabp.integration.test.deployment;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the transaction-contract acceptance test.
 */
@Getter
@Setter
public class TransactionAggregate {

  private String id;

  private String status;

}
