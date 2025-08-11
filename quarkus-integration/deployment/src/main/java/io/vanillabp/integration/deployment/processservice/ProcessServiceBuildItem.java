package io.vanillabp.integration.deployment.processservice;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.vanillabp.integration.runtime.processservice.ProcessServiceCdiBean;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@SuppressWarnings("rawtypes")
public final class ProcessServiceBuildItem extends MultiBuildItem {
  private RuntimeValue<ProcessServiceCdiBean> processService;
}
