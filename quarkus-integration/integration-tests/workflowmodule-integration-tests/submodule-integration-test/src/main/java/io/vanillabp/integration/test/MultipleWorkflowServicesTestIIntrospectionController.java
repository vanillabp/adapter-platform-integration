package io.vanillabp.integration.test;

import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vanillabp.integration.test.multibpmn.Aggregate1;
import io.vanillabp.integration.test.multibpmn.Aggregate2;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import lombok.extern.slf4j.Slf4j;

@Path("/introspect")
@ApplicationScoped
@Slf4j
public class MultipleWorkflowServicesTestIIntrospectionController {

  @Inject
  ProcessService<Aggregate> processService;

  @Inject
  ProcessService<Aggregate1> processService1;

  @Inject
  ProcessService<Aggregate2> processService2;

  @ConfigProperty(name = "test-module.test", defaultValue = "-1")
  int testModuleProperty;

  @ConfigProperty(name = "test-module.test2", defaultValue = "-1")
  int testModuleProperty2;

  @ConfigProperty(name = "test-module.test-unmodified", defaultValue = "-1")
  int testModuleUnmodifiedProperty;

  @ConfigProperty(name = "multi-bpmn-module.test", defaultValue = "-1")
  int multiBpmnModuleProperty;

  @ConfigProperty(name = "multi-bpmn-module.test2", defaultValue = "-1")
  int multiBpmnModuleProperty2;

  @ConfigProperty(name = "multi-bpmn-module.test-unmodified", defaultValue = "-1")
  int multiBpmnModuleUnmodifiedProperty;

  @ConfigProperty(name = "no-module.test", defaultValue = "-1")
  int noModuleProperty;

  @ConfigProperty(name = "no-module.test2", defaultValue = "-1")
  int noModuleProperty2;

  @ConfigProperty(name = "no-module.test-unmodified", defaultValue = "-1")
  int noModuleUnmodifiedProperty;

  /**
   * Written as <code>${urls.external-system}</code> in the workflow
   * module's own file and resolved against the application's configuration.
   */
  @ConfigProperty(name = "test-module.external-system-url", defaultValue = "-")
  String testModuleExternalSystemUrl;

  /**
   * Shipped by the workflow module and overridden by the
   * application's classpath <code>application.yaml</code>.
   */
  @ConfigProperty(name = "test-module.overridden-from-classpath", defaultValue = "-")
  String testModuleOverriddenFromClasspath;

  /**
   * Shipped by the workflow module and overridden by a configuration
   * file next to the runner.
   */
  @ConfigProperty(name = "test-module.overridden-from-external-file", defaultValue = "-")
  String testModuleOverriddenFromExternalFile;

  /**
   * Shipped by the workflow module's profile-specific file only.
   */
  @ConfigProperty(name = "test-module.profile-only", defaultValue = "-")
  String testModuleProfileOnly;

  @GET
  @Path("/workflow-modules")
  public Map<String, String> getWorkflowModules() {

    return Map.of(
        "processService", processService.getWorkflowModuleId(),
        "processService1", processService1.getWorkflowModuleId(),
        "processService2", processService2.getWorkflowModuleId());

  }

  @GET
  @Path("/test-properties")
  public Map<String, Integer> getTestProperties() {

    return Map.of(
        "test-module", testModuleProperty,
        "multi-bpmn-module", multiBpmnModuleProperty,
        "no-module", noModuleProperty);

  }

  @GET
  @Path("/test2-properties")
  public Map<String, Integer> getTest2Properties() {

    return Map.of(
        "test-module", testModuleProperty2,
        "multi-bpmn-module", multiBpmnModuleProperty2,
        "no-module", noModuleProperty2);

  }

  /**
   * The precedence of a workflow module's files against the
   * application's, read once through an injected {@link ConfigProperty} and
   * once through the {@link org.eclipse.microprofile.config.Config} itself, so
   * that both ways of reading are covered.
   *
   * @return The values the configuration resolved to
   */
  @GET
  @Path("/precedence-properties")
  public Map<String, String> getPrecedenceProperties() {

    final var config = ConfigProvider.getConfig();

    return Map.of(
        "injected-external-system-url", testModuleExternalSystemUrl,
        "external-system-url", config.getValue("test-module.external-system-url", String.class),
        "injected-overridden-from-classpath", testModuleOverriddenFromClasspath,
        "overridden-from-classpath", config.getValue("test-module.overridden-from-classpath", String.class),
        "injected-overridden-from-external-file", testModuleOverriddenFromExternalFile,
        "overridden-from-external-file", config.getValue("test-module.overridden-from-external-file", String.class),
        "profile-only", testModuleProfileOnly);

  }

  @GET
  @Path("/unmodified-properties")
  public Map<String, Integer> getUnmodifiedTestProperties() {

    return Map.of(
        "test-module", testModuleUnmodifiedProperty,
        "multi-bpmn-module", multiBpmnModuleUnmodifiedProperty,
        "no-module", noModuleUnmodifiedProperty);

  }

}
