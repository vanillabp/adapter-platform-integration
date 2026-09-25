package io.vanillabp.migration.test.values;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport;
import io.vanillabp.integration.adapter.migration.workflowtask.PortableValuesCheck;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * What the application is told when a value travels which the BPMS may not give back the
 * way it was given.
 * <p>
 * The message IS the feature of this check, so the assertions read it rather than only
 * counting that something was thrown: it has to name the value, its type, the direction,
 * why it does not travel, and the two ways on.
 */
@ExtendWith(SuppressOutputExtension.class)
@DisplayName("Only portable values reach the BPMS")
public class PortableValuesCheckTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  /**
   * An aggregate which shares a decision as a boolean and the rest as texts, which is
   * what the rule asks for.
   */
  @NoSyncWithBPMS
  public static class PortableAggregate {

    @SyncWithBPMS
    public boolean isApproved() {

      return true;

    }

    @SyncWithBPMS
    public String getRegion() {

      return "AT";

    }

    public BigDecimal getAmount() {

      return BigDecimal.ONE;

    }

  }

  /**
   * An aggregate sharing a decimal and a date, neither of which every expression language
   * reads the same way.
   */
  @NoSyncWithBPMS
  public static class UnportableAggregate {

    @SyncWithBPMS
    public BigDecimal getAmount() {

      return BigDecimal.ONE;

    }

    @SyncWithBPMS
    public LocalDate getDueDate() {

      return LocalDate.EPOCH;

    }

  }

  /**
   * An aggregate sharing the WRAPPER of a boolean, which is the case the message has to
   * explain rather than just refuse.
   */
  @NoSyncWithBPMS
  public static class WrappedBooleanAggregate {

    @SyncWithBPMS
    public Boolean getExpressShipping() {

      return null;

    }

  }

  /**
   * A workflow service whose handler asks for a decimal and for a value of no type at
   * all.
   */
  public static class ServiceWithTaskParams {

    @WorkflowTask(taskDefinition = "chargeFare")
    public void chargeFare(
        @TaskParam("fare") final BigDecimal fare,
        @TaskParam("whatever") final Object whatever) {

    }

  }

  private static MigrationAdapterProperties propertiesDeclaring(
      final List<String> aggregateValues,
      final List<String> taskParams) {

    final var workflow = new WorkflowAdapterProperties();
    workflow.setDeclaredAggregateValues(aggregateValues);
    workflow.setDeclaredTaskParams(taskParams);
    final var module = new WorkflowModuleAdapterProperties();
    module.setWorkflows(Map.of(PROCESS, workflow));
    final var properties = new MigrationAdapterProperties();
    properties.setWorkflowModules(Map.of(MODULE, module));
    return properties;

  }

  private static PortableValuesCheck checkWith(
      final MigrationAdapterProperties properties) {

    return new PortableValuesCheck(new AggregateSyncSupport(), properties);

  }

  private static String refusalFor(
      final Class<?> aggregateClass,
      final Class<?> serviceClass,
      final MigrationAdapterProperties properties,
      final List<? extends MigratableProcessService<?>> adapters) {

    return assertThrows(
        IllegalStateException.class,
        () -> checkWith(properties)
            .refuseValuesWhichDoNotTravelWell(
                MODULE,
                PROCESS,
                serviceClass,
                aggregateClass,
                "id",
                adapters))
        .getMessage();

  }

  @Test
  @DisplayName("a boolean and a text travel without being declared")
  public void portableValuesAreAccepted() {

    assertDoesNotThrow(
        () -> checkWith(propertiesDeclaring(List.of("amount"), List.of()))
            .refuseValuesWhichDoNotTravelWell(
                MODULE,
                PROCESS,
                Object.class,
                PortableAggregate.class,
                "id",
                List.of()));

  }

  @Test
  @DisplayName("a decimal of the aggregate ends the startup, naming the value, the type and both ways on")
  public void aDecimalOfTheAggregateIsRefused() {

    final var message = refusalFor(
        UnportableAggregate.class,
        Object.class,
        propertiesDeclaring(List.of(), List.of()),
        List.of());

    assertTrue(message.contains("'amount'"), message);
    assertTrue(message.contains("java.math.BigDecimal"), message);
    assertTrue(message.contains("TO the BPMS"), message);
    assertTrue(message.contains("java.time.LocalDate"), message);
    assertTrue(message.contains("boolean getter"), message);
    assertTrue(
        message.contains("vanillabp.workflow-modules.loan-approval.workflows.LoanApproval.declared-aggregate-values"),
        message);
    assertTrue(message.contains("business rule task"), message);

  }

  @Test
  @DisplayName("a declared value of the aggregate travels")
  public void aDeclaredValueOfTheAggregateTravels() {

    assertDoesNotThrow(
        () -> checkWith(propertiesDeclaring(List.of("amount", "dueDate"), List.of()))
            .refuseValuesWhichDoNotTravelWell(
                MODULE,
                PROCESS,
                Object.class,
                UnportableAggregate.class,
                "id",
                List.of()));

  }

  @Test
  @DisplayName("the wrapper of a boolean is refused with a reason of its own")
  public void theWrapperOfABooleanIsExplained() {

    final var message = refusalFor(
        WrappedBooleanAggregate.class,
        Object.class,
        propertiesDeclaring(List.of(), List.of()),
        List.of());

    assertTrue(message.contains("'expressShipping'"), message);
    assertTrue(message.contains("A plain boolean travels, the wrapper does not"), message);
    assertTrue(message.contains("a value nobody computed must not carry a decision"), message);

  }

  @Test
  @DisplayName("a @TaskParam is refused where an adapter says its BPMS changes the type")
  public void aTaskParamTheBpmsChangesIsRefused() {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Object> adapter = org.mockito.Mockito
        .mock(MigratableProcessService.class);
    org.mockito.Mockito.when(adapter.getAdapterId()).thenReturn("camunda8");
    org.mockito.Mockito
        .when(adapter.whatThisBpmsDoesWith(BigDecimal.class, ValueDirection.FROM_BPMS))
        .thenReturn(ValueTypeVerdict.changed("that the scale is gone"));

    final var message = refusalFor(
        PortableAggregate.class,
        ServiceWithTaskParams.class,
        propertiesDeclaring(List.of("amount"), List.of()),
        List.of(adapter));

    assertTrue(message.contains("@TaskParam 'fare'"), message);
    assertTrue(message.contains("FROM the BPMS"), message);
    assertTrue(message.contains("adapter 'camunda8' says that the scale is gone"), message);
    assertTrue(
        message
            .contains(
                "vanillabp.workflow-modules.loan-approval.workflows.LoanApproval.tasks.<task>.declared-task-params"),
        message);

  }

  @Test
  @DisplayName("a @TaskParam naming no type is refused even where every adapter is silent")
  public void aTaskParamWithoutATypeIsRefused() {

    final var message = refusalFor(
        PortableAggregate.class,
        ServiceWithTaskParams.class,
        propertiesDeclaring(List.of("amount"), List.of("fare")),
        List.of());

    assertTrue(message.contains("@TaskParam 'whatever'"), message);
    assertTrue(message.contains("java.lang.Object"), message);
    assertTrue(message.contains("names no type at all"), message);
    assertTrue(message.contains("cover it with tests"), message);

  }

  @Test
  @DisplayName("an adapter which cannot say does not end the startup")
  public void anAdapterWhichCannotSayIsNoRefusal() {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Object> adapter = org.mockito.Mockito
        .mock(MigratableProcessService.class);
    org.mockito.Mockito.when(adapter.getAdapterId()).thenReturn("pea");
    org.mockito.Mockito
        .when(adapter.whatThisBpmsDoesWith(BigDecimal.class, ValueDirection.FROM_BPMS))
        .thenReturn(ValueTypeVerdict.cannotSay("that it names no engine"));

    assertDoesNotThrow(
        () -> checkWith(propertiesDeclaring(List.of("amount"), List.of("whatever")))
            .refuseValuesWhichDoNotTravelWell(
                MODULE,
                PROCESS,
                ServiceWithTaskParams.class,
                PortableAggregate.class,
                "id",
                List.of(adapter)));

  }

}
