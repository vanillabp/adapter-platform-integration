package io.vanillabp.integration.test.discovery;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of {@link ForgottenWorkflowService}. Nothing ever persists it, because the
 * class which declares it never became a bean.
 */
@Getter
@Setter
public class ForgottenAggregate {

  private String id;

}
