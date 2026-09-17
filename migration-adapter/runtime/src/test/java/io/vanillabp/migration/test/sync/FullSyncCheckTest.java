package io.vanillabp.migration.test.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport;
import io.vanillabp.integration.adapter.migration.sync.FullSyncCheck;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.WorkflowService;
import lombok.Getter;

/**
 * An aggregate carrying no {@code @NoSyncWithBPMS} anywhere hands every attribute to the
 * BPMS. Nobody decided that, it is where an application lands by doing nothing, so the
 * startup ends until the workflow says that it may.
 * <p>
 * What the message has to carry is under test as much as the refusal itself: the
 * workflow, the aggregate, the attributes which travel and the property line to copy.
 */
@ExtendWith(SuppressOutputExtension.class)
public class FullSyncCheckTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private final FullSyncCheck allowing = checkWith(true);

  private final FullSyncCheck refusing = checkWith(false);

  /**
   * @param allowed Whether the workflow allows sharing its aggregate as a whole
   * @return A check reading exactly that configuration
   */
  private static FullSyncCheck checkWith(
      final boolean allowed) {

    final var workflow = WorkflowAdapterProperties
        .builder()
        .bpmnProcessId(PROCESS)
        .allowFullSyncWithBpms(allowed
            ? Boolean.TRUE
            : null)
        .build();
    final var properties = new MigrationAdapterProperties();
    properties
        .setWorkflowModules(
            Map
                .of(
                    MODULE,
                    WorkflowModuleAdapterProperties
                        .builder()
                        .workflowModuleId(MODULE)
                        .workflows(Map.of(PROCESS, workflow))
                        .build()));
    return new FullSyncCheck(new AggregateSyncSupport(), properties);

  }

  private IllegalStateException refusalOf(
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass) {

    return assertThrowsExactly(
        IllegalStateException.class,
        () -> refusing
            .refuseSharingEverythingUnlessAllowed(MODULE, PROCESS, workflowServiceClass, workflowAggregateClass, "id"));

  }

  @Test
  @DisplayName("An aggregate annotating nothing ends the startup, naming both ways on")
  public void anAggregateWhichSharesEverythingIsRefused() {

    final var refusal = refusalOf(LoanApprovalService.class, Loan.class);

    assertTrue(refusal.getMessage().contains(Loan.class.getName()), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("'LoanApproval'"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("'loan-approval'"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("amount, cardNumber, customer"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("@NoSyncWithBPMS"), "the first way on");
    assertTrue(
        refusal
            .getMessage()
            .contains(
                "vanillabp.workflow-modules.loan-approval.workflows.LoanApproval.allow-full-sync-with-bpms: true"),
        "the second way on, ready to copy");
    assertTrue(refusal.getMessage().contains("not inherited"), "and where it may not stand");

  }

  @Test
  @DisplayName("The ID attribute is not counted - it reaches the BPMS whatever is annotated")
  public void theIdIsNotWhatIsGivenAway() {

    final var refusal = refusalOf(LoanApprovalService.class, Loan.class);

    assertTrue(refusal.getMessage().contains("amount, cardNumber, customer."), refusal.getMessage());

  }

  @Test
  @DisplayName("An aggregate holding nothing but its ID starts, there is nothing to protect")
  public void anAggregateOfAnIdAloneStarts() {

    refusing
        .refuseSharingEverythingUnlessAllowed(MODULE, PROCESS, NothingButAnIdService.class, NothingButAnId.class, "id");

  }

  @Test
  @DisplayName("An ID nobody could name is still an ID: the conventional name stands in")
  public void anAggregateOfAnIdNobodyNamedStarts() {

    refusing
        .refuseSharingEverythingUnlessAllowed(MODULE, PROCESS, NothingButAnIdService.class, NothingButAnId.class, null);

  }

  @Test
  @DisplayName("One @NoSyncWithBPMS is enough to start without the permission")
  public void anAggregateHoldingSomethingBackStarts() {

    refusing.refuseSharingEverythingUnlessAllowed(MODULE, PROCESS, HidingService.class, LoanHidingTheCard.class, "id");

  }

  @Test
  @DisplayName("A nested type which shares only what it names is enough as well")
  public void anAggregateWhoseNestedTypeHoldsSomethingBackStarts() {

    refusing.refuseSharingEverythingUnlessAllowed(MODULE, PROCESS, NestedService.class, LoanWithACustomer.class, "id");

  }

  @Test
  @DisplayName("The permission at the workflow starts the same application")
  public void thePermissionAtTheWorkflowStartsIt() {

    allowing.refuseSharingEverythingUnlessAllowed(MODULE, PROCESS, LoanApprovalService.class, Loan.class, "id");

  }

  @Test
  @DisplayName("A secondary BPMN process is covered by the permission of the primary one")
  public void aSecondaryProcessIsNotAskedForItsOwnPermission() {

    refusing
        .refuseSharingEverythingUnlessAllowed(MODULE, "loan_approval", RenamedService.class, Loan.class, "id");

  }

  @Test
  @DisplayName("A check without a sync model or without properties refuses nothing")
  public void aCheckWithoutItsCollaboratorsIsQuiet() {

    new FullSyncCheck(null, null)
        .refuseSharingEverythingUnlessAllowed(MODULE, PROCESS, LoanApprovalService.class, Loan.class, "id");

  }

  @Test
  @DisplayName("The permission is refused wherever it stands outside a workflow")
  public void thePermissionIsRefusedAtEveryOtherLevel() {

    final var properties = new MigrationAdapterProperties();
    properties.setAllowFullSyncWithBpms(Boolean.TRUE);
    properties.setAdapters(Map.of("saas", AdapterConfigProperties.ofType("adapter2")));
    properties
        .getAdapters()
        .get("saas")
        .setAllowFullSyncWithBpms(Boolean.TRUE);
    properties
        .setWorkflowModules(
            Map
                .of(
                    MODULE,
                    WorkflowModuleAdapterProperties
                        .builder()
                        .workflowModuleId(MODULE)
                        .allowFullSyncWithBpms(Boolean.TRUE)
                        .build()));
    properties.setPrioritizedAdapters(List.of("saas"));

    final var refusal = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("adapter2"), List.of(MODULE)));

    assertEquals(
        """
            Sharing a whole workflow aggregate is allowed at the workflow and nowhere else, but it is configured at:
              vanillabp.allow-full-sync-with-bpms
              vanillabp.adapters.saas.allow-full-sync-with-bpms
              vanillabp.workflow-modules.loan-approval.allow-full-sync-with-bpms
            Move each of them to the workflow it is meant for:
              vanillabp.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.allow-full-sync-with-bpms: true
            An inherited permission would cover the next workflow somebody adds to the module as well, and that is the workflow nobody looked at.""",
        refusal.getMessage());

  }

  @Getter
  public static class Loan {

    private String id;

    private String customer;

    private int amount;

    private String cardNumber;

  }

  @Getter
  public static class LoanHidingTheCard {

    private String id;

    private String customer;

    @NoSyncWithBPMS
    private String cardNumber;

  }

  @Getter
  public static class LoanWithACustomer {

    private String id;

    private Customer customer;

  }

  @Getter
  public static class Customer {

    private String name;

    @NoSyncWithBPMS
    private String dateOfBirth;

  }

  @Getter
  public static class NothingButAnId {

    private String id;

  }

  @WorkflowService(
      workflowAggregateClass = Loan.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class LoanApprovalService {
  }

  @WorkflowService(
      workflowAggregateClass = Loan.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "loan_approval"))
  public static class RenamedService {
  }

  @WorkflowService(
      workflowAggregateClass = LoanHidingTheCard.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class HidingService {
  }

  @WorkflowService(
      workflowAggregateClass = LoanWithACustomer.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class NestedService {
  }

  @WorkflowService(
      workflowAggregateClass = NothingButAnId.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class NothingButAnIdService {
  }

}
