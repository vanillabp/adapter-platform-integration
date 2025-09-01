package io.vanillabp.integration.deployment.processservice;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.vanillabp.integration.runtime.processservice.ProcessServiceCdiBean;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A build item holding all process services built for later initialization.
 */
@Getter
@AllArgsConstructor
@SuppressWarnings("rawtypes")
public final class ProcessServiceBuildItem extends MultiBuildItem {

  /**
   * The process services built.
   */
  private RuntimeValue<ProcessServiceCdiBean> processService;

}
