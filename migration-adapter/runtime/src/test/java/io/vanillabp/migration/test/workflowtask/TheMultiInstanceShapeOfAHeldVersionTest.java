package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Whether a method reading the item of an iteration gets one on a version the BPMS only
 * still holds.
 * <p>
 * While a model is DEPLOYED the adapter asks that question and refuses a pairing its model
 * cannot serve: an element without <code>camunda:elementVariable</code> respectively
 * without <code>inputElement</code> iterates without ever naming the value of a round, and
 * a handler reading it gets <code>null</code>. A version a BPMS only holds is never
 * deployed again, so nobody asked - while the methods of the application serve it all the
 * same, because their version ranges say so.
 * <p>
 * Reading the old model belongs to the adapter ({@link ProcessVersionCatalog}), deciding
 * what it means belongs to the core, and an adapter which does not read the shape answers
 * nothing rather than a guess.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheMultiInstanceShapeOfAHeldVersionTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c7";

  @Getter
  public static class Aggregate {

    String id;

    Object element;

  }

  /**
   * The application as it is written today: one method for every version, reading the item
   * of the iteration it runs in.
   */
  public static class Service {

    @WorkflowTask(taskDefinition = "handleOrder")
    public void handleOrder(
        final Aggregate aggregate,
        @MultiInstanceElement("Subprocess_orders") final Object element) {

      aggregate.element = element;

    }

  }

  /**
   * The same application written the other way round: the method reads which round it is
   * in and never the value of that round, which is a pairing every model can serve.
   */
  public static class ServiceReadingTheIndexOnly {

    @WorkflowTask(taskDefinition = "handleOrder")
    public void handleOrder(
        final Aggregate aggregate,
        @MultiInstanceIndex("Subprocess_orders") final int index) {

    }

  }

  /**
   * What the BPMS holds, with every answer handed in by the test.
   */
  private static class CatalogStub implements ProcessVersionCatalog {

    private List<DeployedProcessVersion> versions = List.of();

    private final Map<String, Long> instancesPerVersion = new java.util.HashMap<>();

    /**
     * The elements a version iterates without naming the item, per version.
     * <code>null</code> for a version is an adapter which does not read the shape.
     */
    private final Map<String, List<String>> withoutAnItemPerVersion = new java.util.HashMap<>();

    private boolean readsTheShape = true;

    @Override
    public List<DeployedProcessVersion> deployedVersionsOf(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return versions;

    }

    @Override
    public DeployedProcessVersion resolveVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String versionOrVersionTag) {

      return versions
          .stream()
          .filter(version -> version.version().equals(versionOrVersionTag))
          .findFirst()
          .orElse(null);

    }

    @Override
    public Collection<BpmnTaskSpec> tasksOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      return List
          .of(new BpmnTaskSpec(
              "Activity_handleOrder", "handleOrder", false, null, readsTheShape
                  ? withoutAnItemPerVersion.getOrDefault(version, List.of())
                  : null));

    }

    @Override
    public Long activeInstanceCountOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      return instancesPerVersion.get(version);

    }

  }

  private MigrationAdapterProperties properties;

  private CatalogStub catalog;

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("camunda7")));

    catalog = new CatalogStub();
    catalog.versions = List
        .of(DeployedProcessVersion.of("1"), DeployedProcessVersion.of("2"), DeployedProcessVersion.of("3"));
    // the model this boot deployed names the item; version 1 never did
    catalog.withoutAnItemPerVersion.put("1", List.of("Subprocess_orders"));
    catalog.instancesPerVersion.put("1", 4L);
    catalog.instancesPerVersion.put("2", 0L);

  }

  @Test
  @DisplayName("A held version which names no item is reported, with its version and its count")
  public void aHeldVersionWithoutAnItemIsReported() {

    final var findings = aboutTheShape(theModuleFinishedDeploying(Service.class));

    assertEquals(1, findings.size(), findings.toString());
    final var finding = findings.get(0);
    assertEquals(StartupFindings.Severity.WARNING, finding.severity());
    // the fix is in the code, but what a reader has to look at first is the version the
    // BPMS still holds
    assertEquals(StartupTopic.DEPLOYED_VERSIONS, finding.topic());
    assertEquals(
        "process '%s' of workflow module '%s', adapter '%s'".formatted(PROCESS, MODULE, ADAPTER),
        finding.scope());
    final var message = finding.message();
    assertTrue(message.contains("Version '1'"), message);
    assertTrue(message.contains("'handleOrder' reads the item of 'Subprocess_orders'"), message);
    assertTrue(message.contains("which is 4 workflows at the moment"), message);
    // and what the developer does about a model nobody can change any more
    assertTrue(message.contains("narrow the version range of the method"), message);

  }

  @Test
  @DisplayName("A method reading the index and not the item is no finding")
  public void theIndexAndTheTotalAreServedByEveryModel() {

    assertEquals(
        List.of(),
        aboutTheShape(theModuleFinishedDeploying(ServiceReadingTheIndexOnly.class)));

  }

  @Test
  @DisplayName("A version which names its item is no finding")
  public void aVersionNamingItsItemIsQuiet() {

    catalog.withoutAnItemPerVersion.clear();

    assertEquals(List.of(), aboutTheShape(theModuleFinishedDeploying(Service.class)));

  }

  @Test
  @DisplayName("An adapter which does not read the shape says nothing at all")
  public void anAdapterWhichDoesNotReadTheShapeIsQuiet() {

    catalog.readsTheShape = false;

    // a check which cannot answer for sure stays silent rather than refusing - decision 38
    assertEquals(List.of(), aboutTheShape(theModuleFinishedDeploying(Service.class)));

  }

  @Test
  @DisplayName("A BPMS which cannot count the workflows says that instead of a number")
  public void aBpmsWhichCannotCountSaysSo() {

    catalog.instancesPerVersion.clear();

    final var message = aboutTheShape(theModuleFinishedDeploying(Service.class))
        .get(0)
        .message();

    assertTrue(message.contains("this BPMS cannot say how many that are"), message);

  }

  /**
   * What the check of this test reported, out of everything the deployment noticed. The
   * versions a BPMS holds are read by several checks at once, and only one of them is
   * the subject here.
   *
   * @param findings Everything the deployment noticed
   * @return The findings about a method reading an item no model names
   */
  private static List<StartupFindings.Finding> aboutTheShape(
      final StartupFindings findings) {

    return findings
        .findings()
        .stream()
        .filter(finding -> finding.message().contains("reads the item of"))
        .toList();

  }

  /**
   * The deployment of a workflow module, up to the moment the versions the BPMS holds are
   * read - and what the start noticed while it did.
   */
  private StartupFindings theModuleFinishedDeploying(
      final Class<?> workflowServiceClass) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            workflowServiceClass,
            () -> null,
            type -> null,
            processService(workflowServiceClass));
    registry
        .validateTaskWiring(
            MODULE,
            PROCESS,
            List.of(new BpmnTaskSpec("Activity_handleOrder", "handleOrder")));
    registry.registerProcessVersions(ADAPTER, MODULE, PROCESS, catalog);
    registry.registerDeployedVersion(ADAPTER, MODULE, PROCESS, "3");
    registry.resolveProcessVersions(MODULE);
    return properties.startupFindings();

  }

  /**
   * The registry needs a process service to register a workflow service; nothing here
   * invokes it.
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private static io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService<?> processService(
      final Class<?> workflowServiceClass) {

    final var processService = org.mockito.Mockito
        .mock(io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService.class);
    org.mockito.Mockito
        .when(processService.getWorkflowAggregateClass())
        .thenReturn((Class) Aggregate.class);
    return processService;

  }

  /**
   * The transaction runner is irrelevant here - no test in this class runs a handler.
   */
  private static class TransactionRunnerStub implements TransactionRunner {

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {

      return work.get();

    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {

      return work.get();

    }

    @Override
    public boolean isRollbackOnly() {

      return false;

    }

  }

}
