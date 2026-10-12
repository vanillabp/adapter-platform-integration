package io.vanillabp.integration.test.transaction;

import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the test with transactions the application owns. The
 * application stores it in a system Spring knows nothing about. So nothing here is a JPA
 * entity or a MongoDB document.
 */
@Getter
@Setter
public class AppTxAggregate implements AppTxStored {

  private String id;

  private String status;

  private int invocations;

}
