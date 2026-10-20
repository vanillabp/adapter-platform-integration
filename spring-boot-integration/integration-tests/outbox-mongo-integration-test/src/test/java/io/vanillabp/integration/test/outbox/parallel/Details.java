package io.vanillabp.integration.test.outbox.parallel;

import lombok.Getter;
import lombok.Setter;

/**
 * A plain object which Spring Data maps into the document of the aggregate, as a
 * sub-document. So each change in here is a change of the aggregate.
 */
@Getter
@Setter
public class Details {

  private String note;

}
