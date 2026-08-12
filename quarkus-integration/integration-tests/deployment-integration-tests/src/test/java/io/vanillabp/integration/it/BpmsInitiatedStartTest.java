package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.adapter.dummy.runtime.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.test.deployment.StartAggregate;
import io.vanillabp.integration.test.deployment.StartAggregatePersistence;
import io.vanillabp.integration.test.deployment.StartProcessStartEventSource;
import io.vanillabp.integration.test.deployment.StartWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of workflows the BPMS starts on its own (story 41) on Quarkus,
 * with the dummy adapter standing in for a BPMS reporting a timer or signal start:
 * the aggregate is built without a line of application code, a repeated
 * notification creates nothing twice, and an optional
 * <code>&#64;WorkflowStartedByBpms</code> method adds what the application wants.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmsInitiatedStartTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "StartProcess";

  private static final Instant TRIGGER_TIME = Instant.parse("2026-08-12T04:00:00Z");

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("bpms-initiated-start/application.yaml", "application.yaml")
          .addClass(StartAggregate.class)
          .addClass(StartAggregatePersistence.class)
          .addClass(StartWorkflowService.class)
          .addClass(StartProcessStartEventSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/StartProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  StartAggregatePersistence persistence;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> "demo1".equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  private BpmsInitiatedStartContext context(
      final BpmsStartTrigger.Kind kind,
      final String startEventId,
      final Map<String, Object> variables) {

    return new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return startEventId;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return kind;
      }

      @Override
      public Instant getTriggerTime() {
        return TRIGGER_TIME;
      }

      @Override
      public String getSignalName() {
        return kind == BpmsStartTrigger.Kind.SIGNAL
            ? "OrderReceived"
            : null;
      }

      @Override
      public Map<String, Object> getVariables() {
        return variables;
      }

    };

  }

  @Test
  @DisplayName("A timer start builds the aggregate, a repeated one changes nothing, a signal start runs the application's method")
  public void bpmsInitiatedStartsBuildTheirAggregate() {

    final var dummyAdapter = dummyAdapter();

    // (a) a timer start without any application code
    final var timerStart = dummyAdapter
        .startWorkflowByBpms(
            MODULE,
            PROCESS,
            context(
                BpmsStartTrigger.Kind.TIMER,
                "DailyTimer",
                Map.of("region", "north", "amount", 42, "notModelled", "ignored")));

    assertTrue(timerStart.created());
    assertEquals(TRIGGER_TIME.toString(), timerStart.workflowAggregateId());
    assertEquals(TRIGGER_TIME.toString(), timerStart.variables().get("id"));

    final var timerAggregate = persistence.stored(TRIGGER_TIME.toString());
    assertNotNull(timerAggregate);
    assertEquals("north", timerAggregate.getRegion());
    assertEquals(42, timerAggregate.getAmount());
    // the application's method serves the SIGNAL start event only
    assertNull(timerAggregate.getStartedBy());

    // (b) the same timer time reported again: nothing is created twice and business
    // data written meanwhile survives
    timerAggregate.setRegion("changed meanwhile");
    persistence.put(timerAggregate);
    final var repeated = dummyAdapter
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.TIMER, "DailyTimer", Map.of("region", "north")));
    assertFalse(repeated.created());
    assertEquals(1, persistence.count());
    assertEquals("changed meanwhile", persistence.stored(TRIGGER_TIME.toString()).getRegion());

    // (c) the signal start passes through the application's method
    final var signalStart = dummyAdapter
        .startWorkflowByBpms(
            MODULE, PROCESS, context(BpmsStartTrigger.Kind.SIGNAL, "SignalStart", Map.of("region", "south")));

    assertTrue(signalStart.created());
    assertNotEquals(TRIGGER_TIME.toString(), signalStart.workflowAggregateId());
    final var signalAggregate = persistence.stored(signalStart.workflowAggregateId());
    assertEquals("SIGNAL/OrderReceived", signalAggregate.getStartedBy());
    assertEquals("SOUTH", signalAggregate.getRegion());

  }

}
