package io.vanillabp.migration.test.health;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.health.AdapterHealthReport;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the platform-neutral half of the health contribution does with the adapters'
 * answers: nothing is invented for an adapter which says nothing, an
 * adapter which throws is a defect worth seeing, and an adapter which is not
 * configured yet is not an outage.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterHealthReportTest {

  /**
   * An adapter which answers the health question and nothing else - the deployment
   * pipeline is not what this test is about.
   */
  private static AdapterDeploymentService<?, ?> adapter(
      final String adapterId,
      final java.util.function.Supplier<AdapterHealth> health) {

    return new AdapterDeploymentService<Object, Object>() {

      @Override
      public String getAdapterId() {
        return adapterId;
      }

      @Override
      public String getAdapterType() {
        return "dummy";
      }

      @Override
      public AdapterHealth checkHealth() {
        return health.get();
      }

      @Override
      public Class<Object> getModelType() {
        throw new UnsupportedOperationException();
      }

      @Override
      public Class<Object> getProcessContextType() {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<java.util.Map.Entry<String, Object>> readBpmn(
          final String workflowModuleId,
          final String filename,
          final java.io.InputStream bpmn,
          final boolean isVanillaBpBpmn) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Object prepareBpmn(
          final String workflowModuleId,
          final Object existingContext,
          final String filename,
          final String bpmnProcessId,
          final Object model) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void wireBpmn(
          final String workflowModuleId,
          final String filename,
          final String bpmnProcessId,
          final Object model,
          final Object bpmsProcessingContext) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void deployResources(
          final String workflowModuleId,
          final Object bpmsProcessingContext) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void startWorkflowProcessing(
          final String workflowModuleId,
          final Object bpmsProcessingContext) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void stopWorkflowProcessing(
          final String workflowModuleId,
          final Object bpmsProcessingContext) {
        throw new UnsupportedOperationException();
      }

    };

  }

  @Test
  @DisplayName("An adapter contributing nothing is absent from the report")
  public void adapterWithoutCheckIsAbsent() {

    final var report = new AdapterHealthReport(() -> List.of(adapter("silent", () -> null)));

    Assertions.assertTrue(report.collect().isEmpty());
    Assertions
        .assertEquals(
            AdapterHealth.Status.UNKNOWN,
            AdapterHealthReport.overallStatus(report.collect()),
            "nothing was checked, so nothing is claimed");

  }

  @Test
  @DisplayName("An adapter which throws is reported as down, naming what happened")
  public void throwingAdapterIsDown() {

    final var report = new AdapterHealthReport(
        () -> List
            .of(adapter("broken", () -> {
              throw new IllegalStateException("the check itself is broken");
            })));

    final var healths = report.collect();
    Assertions.assertEquals(1, healths.size());
    Assertions
        .assertEquals(
            AdapterHealth.Status.DOWN,
            healths
                .getFirst()
                .status());
    Assertions
        .assertTrue(
            healths
                .getFirst()
                .description()
                .contains("the check itself is broken"));

  }

  @Test
  @DisplayName("An unconfigured adapter next to a healthy one keeps the application up")
  public void unconfiguredAdapterDoesNotDragTheApplicationDown() {

    final var report = new AdapterHealthReport(
        () -> List
            .of(
                adapter("running", () -> AdapterHealth.up("running", "dummy", "ok", java.util.Map.of())),
                adapter(
                    "planned",
                    () -> AdapterHealth.unknown("planned", "dummy", "not configured yet", java.util.Map.of()))));

    Assertions
        .assertEquals(
            AdapterHealth.Status.UP,
            AdapterHealthReport.overallStatus(report.collect()));

  }

  @Test
  @DisplayName("One adapter which is down is enough to report the application down")
  public void oneDownAdapterIsEnough() {

    final var report = new AdapterHealthReport(
        () -> List
            .of(
                adapter("running", () -> AdapterHealth.up("running", "dummy", "ok", java.util.Map.of())),
                adapter(
                    "unreachable",
                    () -> AdapterHealth
                        .down("unreachable", "dummy", "Connection refused", java.util.Map.of()))));

    Assertions
        .assertEquals(
            AdapterHealth.Status.DOWN,
            AdapterHealthReport.overallStatus(report.collect()));

  }

  @Test
  @DisplayName("A detail without a value is left out")
  public void emptyDetailsAreDropped() {

    final var details = AdapterHealth
        .detailsBuilder()
        .with("address", "http://localhost:26500")
        .with("tenant", null)
        .with("region", "   ")
        .build();

    Assertions.assertEquals(java.util.Map.of("address", "http://localhost:26500"), details);

  }

}
