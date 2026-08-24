package io.vanillabp.integration.test.transaction;

import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the application-owned-transaction test: stored by
 * the application in a system Spring knows nothing about, so nothing here is a JPA entity
 * or a MongoDB document.
 */
@Getter
@Setter
public class AppTxAggregate implements AppTxStored {

  private String id;

  private String status;

  private int invocations;

}
