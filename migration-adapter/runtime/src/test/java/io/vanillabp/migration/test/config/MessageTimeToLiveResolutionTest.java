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
 * The <code>message-time-to-live</code> is resolved once, for every adapter which knows the
 * key: four levels, the message most specific, and the process whose model waits for the
 * message. Each level gives a different value, so every answer names the level it came from.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MessageTimeToLiveResolutionTest {

  private static final String ADAPTER_ID = "c7";

  private static AdapterProperties living(
      final Duration timeToLive) {

    return AdapterProperties
        .builder()
        .messageTimeToLive(timeToLive)
        .build();

  }

  private static MessageAdapterProperties aMessageLiving(
      final Duration timeToLive) {

    final var settings = new MessageAdapterProperties.MessageSettings();
    settings.setMessageTimeToLive(timeToLive);
    final var message = new MessageAdapterProperties();
    message.setAdapters(Map.of(ADAPTER_ID, settings));
    return message;

  }

  /**
   * One minute at the adapter, two at the workflow module, three at the workflow
   * <code>LoanApproval</code>, four for its message <code>ContractSigned</code>. The called
   * process <code>Signing</code> says five for the same message.
   */
  private static MigrationAdapterProperties allLevels() {

    final var adapter = AdapterConfigProperties.ofType("camunda7");
    adapter.setMessageTimeToLive(Duration.ofMinutes(1));
    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER_ID, adapter));
    properties.setPrioritizedAdapters(List.of(ADAPTER_ID));
    properties.setWorkflowModules(Map.of("loans", WorkflowModuleAdapterProperties
        .builder()
        .workflowModuleId("loans")
        .adapters(Map.of(ADAPTER_ID, living(Duration.ofMinutes(2))))
        .workflows(Map.of(
            "LoanApproval", WorkflowAdapterProperties
                .builder()
                .adapters(Map.of(ADAPTER_ID, living(Duration.ofMinutes(3))))
                .messages(Map.of("ContractSigned", aMessageLiving(Duration.ofMinutes(4))))
                .build(),
            "Signing", WorkflowAdapterProperties
                .builder()
                .messages(Map.of("ContractSigned", aMessageLiving(Duration.ofMinutes(5))))
                .build()))
        .build()));
    return properties;

  }

  @Test
  @DisplayName("The most specific level wins, and the message is more specific than the workflow")
  public void theMostSpecificLevelWins() {

    final var properties = allLevels();

    assertEquals(Duration.ofMinutes(1), properties.messageTimeToLive("payments", "Payment", "Paid", ADAPTER_ID));
    assertEquals(Duration.ofMinutes(2), properties.messageTimeToLive("loans", "Other", "ContractSigned", ADAPTER_ID));
    assertEquals(Duration.ofMinutes(3), properties.messageTimeToLive("loans", "LoanApproval", "Other", ADAPTER_ID));
    assertEquals(Duration.ofMinutes(4),
        properties.messageTimeToLive("loans", "LoanApproval", "ContractSigned", ADAPTER_ID));
    assertEquals(Duration.ofMinutes(5), properties.messageTimeToLive("loans", "Signing", "ContractSigned", ADAPTER_ID));

  }

  @Test
  @DisplayName("An adapter about which nothing is written gets null, and decides itself what that means")
  public void nothingWrittenIsNull() {

    final var properties = allLevels();

    assertNull(properties.messageTimeToLive("loans", "LoanApproval", "ContractSigned", "c8"));

  }

  @Test
  @DisplayName("A task of the same name as a message is not read for the message")
  public void aTaskIsNotAMessage() {

    final var properties = allLevels();
    properties
        .getWorkflowModules()
        .get("loans")
        .getWorkflows()
        .get("LoanApproval")
        .setTasks(Map.of("Other", TaskAdapterProperties
            .builder()
            .adapters(Map.of(ADAPTER_ID, living(Duration.ofMinutes(9))))
            .build()));

    assertEquals(Duration.ofMinutes(3), properties.messageTimeToLive("loans", "LoanApproval", "Other", ADAPTER_ID));

  }

  @Test
  @DisplayName("Every written value is listed under its key, so an adapter can check it")
  public void everyWrittenValueIsListed() {

    final var expected = new LinkedHashMap<String, Duration>();
    expected.put("vanillabp.adapters.c7.message-time-to-live", Duration.ofMinutes(1));
    expected.put("vanillabp.workflow-modules.loans.adapters.c7.message-time-to-live", Duration.ofMinutes(2));
    expected.put("vanillabp.workflow-modules.loans.workflows.LoanApproval.adapters.c7.message-time-to-live",
        Duration.ofMinutes(3));
    expected.put(
        "vanillabp.workflow-modules.loans.workflows.LoanApproval.messages.ContractSigned.adapters.c7.message-time-to-live",
        Duration.ofMinutes(4));
    expected.put(
        "vanillabp.workflow-modules.loans.workflows.Signing.messages.ContractSigned.adapters.c7.message-time-to-live",
        Duration.ofMinutes(5));

    // the maps behind the levels keep no order, so the order is not part of the answer
    assertEquals(expected, Map.copyOf(allLevels().messageTimesToLiveWrittenFor(ADAPTER_ID)));
    assertEquals(Map.of(), allLevels().messageTimesToLiveWrittenFor("c8"));

  }

  @Test
  @DisplayName("A time-to-live written at a task ends the startup and says where it belongs")
  public void aTimeToLiveAtATaskIsRefused() {

    final var properties = allLevels();
    properties
        .getWorkflowModules()
        .get("loans")
        .getWorkflows()
        .get("LoanApproval")
        .setTasks(Map.of("signContract", TaskAdapterProperties
            .builder()
            .adapters(Map.of(ADAPTER_ID, living(Duration.ofMinutes(9))))
            .build()));

    final var refusal = assertThrowsExactly(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("camunda7"), List.of("loans")));

    assertEquals(
        """
            The message time-to-live is read at the message, at the workflow, at the workflow module and at the adapter, but it is configured at:
              vanillabp.workflow-modules.loans.workflows.LoanApproval.tasks.signContract.adapters.c7.message-time-to-live
            Move each of them to the message it is meant for, or to the workflow, its workflow module or the adapter:
              vanillabp.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.messages.<message-name>.adapters.<adapter>.message-time-to-live: 10m
              vanillabp.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>.adapters.<adapter>.message-time-to-live: 10m
              vanillabp.workflow-modules.<workflow-module>.adapters.<adapter>.message-time-to-live: 10m
              vanillabp.adapters.<adapter>.message-time-to-live: 10m
            A message is correlated to a workflow, not to a task. A receive task waits for a message as well, and its time-to-live is read under the name of that message.""",
        refusal.getMessage());

  }

  @Test
  @DisplayName("Without a time-to-live at a task the startup goes on")
  public void allLevelsWithoutATaskStartTheApplication() {

    allLevels().validateProperties(List.of("camunda7"), List.of("loans"));

  }

  @Test
  @DisplayName("A called process which waits for the message gives its own time-to-live")
  public void theCalledProcessWhichWaitsAnswers() {

    final var resolver = allLevels().messageTimeToLiveResolverFor(ADAPTER_ID);

    assertEquals(Duration.ofMinutes(5), resolver.resolveFor(
        "loans", "LoanApproval", "ContractSigned", List.of("LoanApproval", "Signing"), () -> List.of("Signing")));

  }

  @Test
  @DisplayName("The process of the call wins where its own model waits too, or where nothing tells the others apart")
  public void theProcessOfTheCallAnswersWhereNothingElseCan() {

    final var resolver = allLevels().messageTimeToLiveResolverFor(ADAPTER_ID);

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

    final var resolver = allLevels().messageTimeToLiveResolverFor(ADAPTER_ID);
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
