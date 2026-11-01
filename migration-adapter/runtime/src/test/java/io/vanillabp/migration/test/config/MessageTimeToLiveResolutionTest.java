package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MessageAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.spi.MessageTimeToLiveResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The <code>message-time-to-live</code> is resolved once, for every adapter alike: three levels
 * without an adapter ID, the message most specific, the workflow module least specific, and
 * the process whose model waits for the message. Each level gives a different value, so every
 * answer names the level it came from.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MessageTimeToLiveResolutionTest {

  private static final String ADAPTER_ID = "c7";

  private static MessageAdapterProperties aMessageLiving(
      final Duration timeToLive) {

    final var message = new MessageAdapterProperties();
    message.setMessageTimeToLive(timeToLive);
    return message;

  }

  /**
   * Two minutes at the workflow module, three at the workflow <code>LoanApproval</code>, four
   * for its message <code>ContractSigned</code>. The called process <code>Signing</code> says
   * five for the same message.
   */
  private static MigrationAdapterProperties allLevels() {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER_ID, AdapterConfigProperties.ofType("camunda7")));
    properties.setPrioritizedAdapters(List.of(ADAPTER_ID));
    properties.setWorkflowModules(Map.of("loans", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("loans")
        .messageTimeToLive(Duration.ofMinutes(2))
        .workflows(Map.of(
            "LoanApproval", WorkflowAdapterProperties
                .builder()
                .messageTimeToLive(Duration.ofMinutes(3))
                .messages(Map.of("ContractSigned", aMessageLiving(Duration.ofMinutes(4))))
                .build(),
            "Signing", WorkflowAdapterProperties
                .builder()
                .messages(Map.of("ContractSigned", aMessageLiving(Duration.ofMinutes(5))))
                .build()))
        .build()));
    return properties;

  }

  private static WorkflowAdapterProperties loanApproval(
      final MigrationAdapterProperties properties) {

    return properties
        .getWorkflowModules()
        .get("loans")
        .getWorkflows()
        .get("LoanApproval");

  }

  private static IllegalStateException refusalOf(
      final MigrationAdapterProperties properties) {

    return assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("camunda7"), List.of("loans")));

  }

  private static String refusalNaming(
      final String misplacedKey) {

    return """
        The message time-to-live is read without an adapter ID, at the message, at the workflow and at the workflow module, but it is configured at:
          %s
        Move each of them to the message it is meant for, or to the workflow or its workflow module:
          vanillabp.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.messages.<message-name>.message-time-to-live: 10m
          vanillabp.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.message-time-to-live: 10m
          vanillabp.workflow-modules.<workflow-module>.message-time-to-live: 10m
        How long a message waits for a workflow means the same on every BPMS, so the key names no adapter. How long it may wait depends on the business of the workflows, so the widest level is the workflow module. A message is correlated to a workflow, not to a task. A receive task waits for a message as well, and its time-to-live is read under the name of that message."""
        .formatted(misplacedKey);

  }

  @Test
  @DisplayName("The most specific level wins, and the message is more specific than the workflow")
  public void theMostSpecificLevelWins() {

    final var properties = allLevels();

    assertEquals(Duration.ofMinutes(2), properties.messageTimeToLive("loans", "Other", "ContractSigned"));
    assertEquals(Duration.ofMinutes(3), properties.messageTimeToLive("loans", "LoanApproval", "Other"));
    assertEquals(Duration.ofMinutes(4), properties.messageTimeToLive("loans", "LoanApproval", "ContractSigned"));
    assertEquals(Duration.ofMinutes(5), properties.messageTimeToLive("loans", "Signing", "ContractSigned"));

  }

  @Test
  @DisplayName("A workflow module about which nothing is written gets null, and the adapter decides what that means")
  public void nothingWrittenIsNull() {

    assertNull(allLevels().messageTimeToLive("payments", "Payment", "Paid"));
    assertNull(allLevels().messageTimeToLive(null, null, null));

  }

  @Test
  @DisplayName("Every adapter gets the same answer, because the key names no adapter")
  public void everyAdapterGetsTheSameAnswer() {

    final var resolver = allLevels().messageTimeToLiveResolver();

    assertEquals(Duration.ofMinutes(4), resolver.configuredFor("loans", "LoanApproval", "ContractSigned"));

  }

  @Test
  @DisplayName("A task of the same name as a message is not read for the message")
  public void aTaskIsNotAMessage() {

    final var properties = allLevels();
    loanApproval(properties).setTasks(Map.of("Other", TaskAdapterProperties.builder().build()));

    assertEquals(Duration.ofMinutes(3), properties.messageTimeToLive("loans", "LoanApproval", "Other"));

  }

  @Test
  @DisplayName("Every written value is listed under its key, so an adapter can check it")
  public void everyWrittenValueIsListed() {

    final var expected = new LinkedHashMap<String, Duration>();
    expected.put("vanillabp.workflow-modules.loans.message-time-to-live", Duration.ofMinutes(2));
    expected.put("vanillabp.workflow-modules.loans.workflows.LoanApproval.message-time-to-live",
        Duration.ofMinutes(3));
    expected.put("vanillabp.workflow-modules.loans.workflows.LoanApproval.messages.ContractSigned.message-time-to-live",
        Duration.ofMinutes(4));
    expected.put("vanillabp.workflow-modules.loans.workflows.Signing.messages.ContractSigned.message-time-to-live",
        Duration.ofMinutes(5));

    // the maps behind the levels keep no order, so the order is not part of the answer
    assertEquals(expected, Map.copyOf(allLevels().messageTimesToLiveWrittenFor(ADAPTER_ID)));
    assertEquals(Map.of(), new MigrationAdapterProperties().messageTimesToLiveWrittenFor(ADAPTER_ID));

  }

  @Test
  @DisplayName("An adapter is shown only the values of the workflow modules it serves, and a migrating module is shown to both")
  public void anAdapterSeesOnlyTheModulesItServes() {

    final var properties = allLevels();
    properties.setAdapters(Map.of(
        ADAPTER_ID, AdapterConfigProperties.ofType("camunda7"),
        "c8", AdapterConfigProperties.ofType("camunda8")));
    final var modules = new LinkedHashMap<>(properties.getWorkflowModules());
    // served by c8 alone, through the module's own list
    modules.put("payments", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("payments")
        .prioritizedAdapters(List.of("c8"))
        .messageTimeToLive(Duration.ofMinutes(6))
        .build());
    // migrating: the module is on c7, one of its workflows on c8 already
    modules.put("claims", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("claims")
        .messageTimeToLive(Duration.ofMinutes(7))
        .workflows(Map.of("Claim", WorkflowAdapterProperties
            .builder()
            .prioritizedAdapters(List.of("c8", ADAPTER_ID))
            .build()))
        .build());
    properties.setWorkflowModules(modules);

    final var ofC7 = properties.messageTimesToLiveWrittenFor(ADAPTER_ID);
    final var ofC8 = properties.messageTimesToLiveWrittenFor("c8");

    assertEquals(
        java.util.Set.of(
            "vanillabp.workflow-modules.loans.message-time-to-live",
            "vanillabp.workflow-modules.loans.workflows.LoanApproval.message-time-to-live",
            "vanillabp.workflow-modules.loans.workflows.LoanApproval.messages.ContractSigned.message-time-to-live",
            "vanillabp.workflow-modules.loans.workflows.Signing.messages.ContractSigned.message-time-to-live",
            "vanillabp.workflow-modules.claims.message-time-to-live"),
        ofC7.keySet());
    assertEquals(
        java.util.Set.of(
            "vanillabp.workflow-modules.payments.message-time-to-live",
            "vanillabp.workflow-modules.claims.message-time-to-live"),
        ofC8.keySet());

  }

  @Test
  @DisplayName("A time-to-live for the whole application ends the startup and names the workflow module")
  public void aTimeToLiveForTheWholeApplicationIsRefused() {

    final var properties = allLevels();
    properties.setMessageTimeToLive(Duration.ofMinutes(1));

    assertEquals(refusalNaming("vanillabp.message-time-to-live"), refusalOf(properties).getMessage());

  }

  @Test
  @DisplayName("A time-to-live in the adapter section of the application ends the startup")
  public void aTimeToLiveInTheAdapterSectionIsRefused() {

    final var properties = allLevels();
    properties.getAdapters().get(ADAPTER_ID).setMessageTimeToLive(Duration.ofMinutes(1));

    assertEquals(refusalNaming("vanillabp.adapters.c7.message-time-to-live"), refusalOf(properties).getMessage());

  }

  @Test
  @DisplayName("A time-to-live in an adapter section below the application ends the startup, at every level")
  public void aTimeToLiveInAnAdapterSectionOfALevelIsRefused() {

    final var properties = allLevels();
    final var module = properties.getWorkflowModules().get("loans");
    module.setAdapters(Map.of(ADAPTER_ID, AdapterProperties
        .builder()
        .messageTimeToLive(Duration.ofMinutes(2))
        .build()));
    final var workflow = loanApproval(properties);
    workflow.setAdapters(Map.of(ADAPTER_ID, AdapterProperties
        .builder()
        .messageTimeToLive(Duration.ofMinutes(3))
        .build()));
    workflow.setTasks(Map.of("signContract", TaskAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER_ID, AdapterProperties
            .builder()
            .messageTimeToLive(Duration.ofMinutes(9))
            .build()))
        .build()));
    final var settings = new MessageAdapterProperties.MessageSettings();
    settings.setMessageTimeToLive(Duration.ofMinutes(4));
    workflow.getMessages().get("ContractSigned").setAdapters(Map.of(ADAPTER_ID, settings));

    final var message = refusalOf(properties).getMessage();

    assertEquals(
        refusalNaming(String.join(
            "\n  ",
            "vanillabp.workflow-modules.loans.adapters.c7.message-time-to-live",
            "vanillabp.workflow-modules.loans.workflows.LoanApproval.adapters.c7.message-time-to-live",
            "vanillabp.workflow-modules.loans.workflows.LoanApproval.messages.ContractSigned.adapters.c7.message-time-to-live",
            "vanillabp.workflow-modules.loans.workflows.LoanApproval.tasks.signContract.adapters.c7.message-time-to-live")),
        message);

  }

  @Test
  @DisplayName("A time-to-live written at a task ends the startup and says where it belongs")
  public void aTimeToLiveAtATaskIsRefused() {

    final var properties = allLevels();
    loanApproval(properties).setTasks(Map.of("signContract", TaskAdapterProperties
        .builder()
        .messageTimeToLive(Duration.ofMinutes(9))
        .build()));

    assertEquals(
        refusalNaming(
            "vanillabp.workflow-modules.loans.workflows.LoanApproval.tasks.signContract.message-time-to-live"),
        refusalOf(properties).getMessage());

  }

  @Test
  @DisplayName("With the time-to-live at its three levels only, the startup goes on")
  public void allLevelsWithoutAMisplacedValueStartTheApplication() {

    allLevels().validateProperties(List.of("camunda7"), List.of("loans"));

  }

  @Test
  @DisplayName("A called process which waits for the message gives its own time-to-live")
  public void theCalledProcessWhichWaitsAnswers() {

    final var resolver = allLevels().messageTimeToLiveResolver();

    assertEquals(Duration.ofMinutes(5), resolver.resolveFor(
        "loans", "LoanApproval", "ContractSigned", List.of("LoanApproval", "Signing"), () -> List.of("Signing")));

  }

  @Test
  @DisplayName("The process of the call wins where its own model waits too, or where nothing tells the others apart")
  public void theProcessOfTheCallAnswersWhereNothingElseCan() {

    final var resolver = allLevels().messageTimeToLiveResolver();

    assertEquals(Duration.ofMinutes(4), resolver.resolveFor(
        "loans", "LoanApproval", "ContractSigned", List.of("LoanApproval", "Signing"),
        () -> List.of("Signing", "LoanApproval")));
    assertEquals(Duration.ofMinutes(4), resolver.resolveFor(
        "loans", "LoanApproval", "ContractSigned", List.of("LoanApproval", "Signing", "Third"),
        () -> List.of("Signing", "Third")));
    assertEquals(Duration.ofMinutes(4), resolver.resolveFor(
        "loans", "LoanApproval", "ContractSigned", List.of("LoanApproval", "Signing"), List::of));

  }

  @Test
  @DisplayName("The models are not read where every process of the module gives the same answer")
  public void theModelsAreReadOnlyWhereTheAnswersDiffer() {

    final var resolver = allLevels().messageTimeToLiveResolver();
    final var modelsRead = new AtomicInteger();

    assertEquals(Duration.ofMinutes(3), resolver.resolveFor(
        "loans", "LoanApproval", "Other", List.of("LoanApproval"), () -> {
          modelsRead.incrementAndGet();
          return List.of();
        }));
    assertEquals(0, modelsRead.get());

  }

  @Test
  @DisplayName("The same process named twice by the models is still one process")
  public void aProcessNamedTwiceIsOneProcess() {

    assertEquals("Signing", MessageTimeToLiveResolver
        .processWaitingForTheMessage("LoanApproval", List.of("Signing", "Signing")));

  }

}
