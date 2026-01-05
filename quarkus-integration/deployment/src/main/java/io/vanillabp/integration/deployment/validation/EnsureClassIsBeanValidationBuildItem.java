package io.vanillabp.integration.deployment.validation;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * A build item marking processing of workflow-module-specific config files as done.
 */
@Builder
@Getter
public final class EnsureClassIsBeanValidationBuildItem extends MultiBuildItem {

  private final DotName className;

  private final String usageDescription;

}
