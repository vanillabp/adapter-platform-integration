package io.vanillabp.migration.test.scoping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A name which lives on in a version the BPMS still holds and in no model of this
 * deployment - what renaming a message leaves behind.
 * <p>
 * The workflows on that version wait at their message event for something nothing sends
 * any more, and nothing else in VanillaBP would say so: the name is in a model the BPMS
 * holds and in no file of the application.
 * <p>
 * The line says "look at it" rather than "write this". The platform can see the case and
 * cannot know whether it is meant - a rename whose old workflows were finished by hand
 * looks exactly like a rename nobody finished - so it is a warning of the block a start
 * writes at its end, never a refusal (decision 38 in the repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ANameTheCurrentDeploymentNoLongerDeclaresTest {

  private static final String PAYMENTS = "payment-handling";

  private static final String ADAPTER = "c7";

  private MigrationAdapterProperties properties;

  private NameClashAvoidanceService testee;

  @BeforeEach
  public void setUp() {

    final var adapter = AdapterConfigProperties.ofType("camunda7");
    adapter.setNameClashAvoidance(NameClashAvoidance.NONE);
    properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, adapter))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    testee = new NameClashAvoidanceService(properties);

  }

  /**
   * What the adapter does while it deploys the models this application brings: it reads
   * their identifiers out and reports them.
   */
  private void theDeploymentDeclares(
      final ModelIdentifier... identifiers) {

    testee.reportIdentifiersTheModelsDeclare(ADAPTER, PAYMENTS, List.of(identifiers));

  }

  /**
   * What the core does once it read the model of a version the BPMS still holds.
   */
  private void aHeldVersionDeclares(
      final Long activeWorkflows,
      final ModelIdentifier... identifiers) {

    testee
        .reportIdentifiersOfHeldVersion(
            ADAPTER,
            PAYMENTS,
            "Settlement",
            "4",
            activeWorkflows,
            List.of(identifiers));

  }

  private List<StartupFindings.Finding> whatTheStartNoticed() {

    return properties
        .startupFindings()
        .findings();

  }

  private static ModelIdentifier message(
      final String name) {

    return new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, name, null);

  }

  private static ModelIdentifier signal(
      final String name) {

    return new ModelIdentifier(ScopedIdentifierKind.SIGNAL_NAME, name, null);

  }

  @Test
  @DisplayName("A message only the held version declares is named, with its version and its count")
  public void aMessageNobodySendsAnyMoreIsNamed() {

    theDeploymentDeclares(message("PaymentSettled"));

    aHeldVersionDeclares(3L, message("PaymentReceived"));

    assertEquals(1, whatTheStartNoticed().size(), whatTheStartNoticed().toString());
    final var finding = whatTheStartNoticed().get(0);
    assertEquals(StartupFindings.Severity.WARNING, finding.severity());
    assertEquals(StartupTopic.DEPLOYED_VERSIONS, finding.topic());
    assertEquals(
        "process 'Settlement' of workflow module '%s', adapter '%s'".formatted(PAYMENTS, ADAPTER),
        finding.scope());
    final var message = finding.message();
    assertTrue(message.contains("Version 4"), message);
    assertTrue(message.contains("message name 'PaymentReceived'"), message);
    assertTrue(message.contains("3 workflows still running on it"), message);
    // and what to look at, because nobody can say from here whether this was meant
    assertTrue(message.contains("Look at the workflows still running on that version"), message);

  }

  @Test
  @DisplayName("A signal counts as much as a message")
  public void aSignalNobodyBroadcastsAnyMoreIsNamed() {

    theDeploymentDeclares(message("PaymentSettled"));

    aHeldVersionDeclares(1L, signal("NightlyRun"));

    assertTrue(
        whatTheStartNoticed().get(0).message().contains("signal name 'NightlyRun'"),
        whatTheStartNoticed().toString());

  }

  @Test
  @DisplayName("A name the current models still declare is continuity")
  public void aNameWhichIsStillDeclaredIsQuiet() {

    theDeploymentDeclares(message("PaymentReceived"));

    aHeldVersionDeclares(3L, message("PaymentReceived"));

    assertEquals(List.of(), whatTheStartNoticed());

  }

  @Test
  @DisplayName("A version nobody is on says nothing: nothing waits there")
  public void aVersionWithoutWorkflowsIsQuiet() {

    theDeploymentDeclares(message("PaymentSettled"));

    aHeldVersionDeclares(0L, message("PaymentReceived"));

    assertEquals(List.of(), whatTheStartNoticed());

  }

  @Test
  @DisplayName("A BPMS which cannot count is asked all the same")
  public void aBpmsWhichCannotCountIsAskedAnyway() {

    theDeploymentDeclares(message("PaymentSettled"));

    // "cannot tell" is not "nobody", and a workflow waiting forever is what this is about
    aHeldVersionDeclares(null, message("PaymentReceived"));

    assertTrue(
        whatTheStartNoticed().get(0).message().contains("cannot count the workflows"),
        whatTheStartNoticed().toString());

  }

  @Test
  @DisplayName("An adapter which never read the current models says nothing")
  public void anAdapterWhichReportsNoIdentifiersIsQuiet() {

    // nobody said what this deployment declares, so every old name would look new - a
    // check which cannot answer for sure stays silent
    aHeldVersionDeclares(3L, message("PaymentReceived"));

    assertEquals(List.of(), whatTheStartNoticed());

  }

  @Test
  @DisplayName("An error code and a task definition are somebody else's question")
  public void onlyWhatReachesAWorkflowFromOutsideIsNamed() {

    theDeploymentDeclares(message("PaymentSettled"));

    aHeldVersionDeclares(
        3L,
        new ModelIdentifier(ScopedIdentifierKind.ERROR_CODE, "PaymentFailed", null),
        new ModelIdentifier(ScopedIdentifierKind.ESCALATION_CODE, "TooSlow", null),
        // a task definition nobody serves is the subject of its own check, which says
        // more about it than this could
        new ModelIdentifier(ScopedIdentifierKind.TASK_DEFINITION, "scoreApplicant", "Settlement"),
        new ModelIdentifier(ScopedIdentifierKind.BPMN_PROCESS_ID, "Settlement", "Settlement"));

    assertEquals(List.of(), whatTheStartNoticed());

  }

  @Test
  @DisplayName("Several names of one version are one entry")
  public void severalNamesAreOneEntry() {

    theDeploymentDeclares(message("PaymentSettled"));

    aHeldVersionDeclares(3L, message("PaymentReceived"), signal("NightlyRun"));

    assertEquals(1, whatTheStartNoticed().size(), whatTheStartNoticed().toString());
    final var message = whatTheStartNoticed().get(0).message();
    assertTrue(message.contains("PaymentReceived"), message);
    assertTrue(message.contains("NightlyRun"), message);

  }

}
