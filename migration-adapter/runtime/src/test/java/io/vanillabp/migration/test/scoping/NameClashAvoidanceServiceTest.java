package io.vanillabp.migration.test.scoping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The name-clash-avoidance model (story 35): resolving the mode, composing the
 * identifiers a BPMS sees, reading them back and the validations which make the
 * whole thing safe.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NameClashAvoidanceServiceTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "RiskAssessment";

  private static final String ADAPTER = "c8";

  /**
   * Properties with a mode at the adapter level and, optionally, overrides per
   * workflow module and workflow.
   */
  private static MigrationAdapterProperties propertiesWith(
      final NameClashAvoidance adapterLevel,
      final NameClashAvoidance moduleLevel,
      final NameClashAvoidance workflowLevel,
      final Boolean prefixTaskDefinitionsPerProcess) {

    final var adapter = AdapterConfigProperties.ofType("camunda8");
    adapter.setNameClashAvoidance(adapterLevel);
    adapter.setPrefixTaskDefinitionsPerProcess(prefixTaskDefinitionsPerProcess);

    final var workflow = new WorkflowAdapterProperties();
    final var workflowAdapter = new AdapterProperties();
    workflowAdapter.setNameClashAvoidance(workflowLevel);
    workflow.setAdapters(Map.of(ADAPTER, workflowAdapter));

    final var module = new WorkflowModuleAdapterProperties();
    final var moduleAdapter = new AdapterProperties();
    moduleAdapter.setNameClashAvoidance(moduleLevel);
    module.setAdapters(Map.of(ADAPTER, moduleAdapter));
    module.setWorkflows(Map.of(PROCESS, workflow));

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, adapter))
        .prioritizedAdapters(List.of(ADAPTER))
        .workflowModules(Map.of(MODULE, module))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private static NameClashAvoidanceService serviceWith(
      final NameClashAvoidance adapterLevel,
      final NameClashAvoidance moduleLevel,
      final NameClashAvoidance workflowLevel,
      final Boolean prefixTaskDefinitionsPerProcess) {

    return new NameClashAvoidanceService(
        propertiesWith(adapterLevel, moduleLevel, workflowLevel, prefixTaskDefinitionsPerProcess));

  }

  private static NameClashAvoidanceService serviceWith(
      final NameClashAvoidance adapterLevel) {

    return serviceWith(adapterLevel, null, null, null);

  }

  /**
   * An adapter of the id {@link #ADAPTER} declaring the given default mode
   * (<code>null</code> for an adapter which declares none).
   */
  @SuppressWarnings("unchecked")
  private static AdapterDeploymentService<Object, Object> adapterDeclaring(
      final NameClashAvoidance defaultMode) {

    final AdapterDeploymentService<Object, Object> deploymentService = Mockito
        .mock(AdapterDeploymentService.class);
    Mockito
        .lenient()
        .when(deploymentService.getAdapterId())
        .thenReturn(ADAPTER);
    Mockito
        .lenient()
        .when(deploymentService.defaultNameClashAvoidance())
        .thenReturn(defaultMode);
    return deploymentService;

  }

  /**
   * A service knowing the adapter's deployment service - the shape both platform
   * integrations wire up (the adapter is asked for its default mode and reports a
   * workflow module whose identifiers are not scoped).
   */
  private static NameClashAvoidanceService serviceWith(
      final NameClashAvoidance adapterLevel,
      final AdapterDeploymentService<Object, Object> deploymentService) {

    return new NameClashAvoidanceService(
        propertiesWith(adapterLevel, null, null,
            null), () -> List.<AdapterDeploymentService<?, ?>>of(deploymentService));

  }

  @Test
  @DisplayName("Without any configuration the mode is BY_ADAPTER - version 1's behavior")
  public void defaultsToByAdapter() {

    final var testee = serviceWith(null);

    assertEquals(NameClashAvoidance.BY_ADAPTER, testee.modeFor(MODULE, PROCESS, ADAPTER));
    // the tenant of BY_ADAPTER is the workflow module id ...
    assertEquals(MODULE, testee.tenantIdFor(MODULE, PROCESS, ADAPTER, null));
    // ... unless the adapter configured a name
    assertEquals("banking", testee.tenantIdFor(MODULE, PROCESS, ADAPTER, "banking"));
    // and nothing is prefixed
    assertEquals(PROCESS, testee.scopedProcessId(MODULE, PROCESS, ADAPTER));

  }

  @Test
  @DisplayName("The most specific configured level wins: workflow > workflow module > adapter")
  public void mostSpecificLevelWins() {

    assertEquals(
        NameClashAvoidance.USE_PREFIX,
        serviceWith(NameClashAvoidance.NONE, NameClashAvoidance.BY_ADAPTER, NameClashAvoidance.USE_PREFIX, null)
            .modeFor(MODULE, PROCESS, ADAPTER));
    assertEquals(
        NameClashAvoidance.BY_ADAPTER,
        serviceWith(NameClashAvoidance.NONE, NameClashAvoidance.BY_ADAPTER, null, null)
            .modeFor(MODULE, PROCESS, ADAPTER));
    assertEquals(
        NameClashAvoidance.NONE,
        serviceWith(NameClashAvoidance.NONE)
            .modeFor(MODULE, PROCESS, ADAPTER));
    // an unknown module falls back to the adapter level
    assertEquals(
        NameClashAvoidance.NONE,
        serviceWith(NameClashAvoidance.NONE, NameClashAvoidance.USE_PREFIX, null, null)
            .modeFor("other-module", PROCESS, ADAPTER));

  }

  @Test
  @DisplayName("NONE scopes nothing and uses no tenant")
  public void noneScopesNothing() {

    final var testee = serviceWith(NameClashAvoidance.NONE);

    assertEquals(PROCESS, testee.scopedProcessId(MODULE, PROCESS, ADAPTER));
    assertEquals("PaymentReceived", testee.scopedIdentifier(MODULE, "PaymentReceived", ADAPTER));
    assertEquals("scoreApplicant", testee.scopedTaskDefinition(MODULE, PROCESS, "scoreApplicant", ADAPTER));
    assertNull(testee.tenantIdFor(MODULE, PROCESS, ADAPTER, null));
    assertNull(testee.tenantIdFor(MODULE, PROCESS, ADAPTER, "banking"), "an explicit tenant does not revive tenants");

  }

  @Test
  @DisplayName("USE_PREFIX composes module (and process for task definitions) - and uses no tenant")
  public void prefixComposesIdentifiers() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX);

    assertEquals("loan-approval__RiskAssessment", testee.scopedProcessId(MODULE, PROCESS, ADAPTER));
    assertEquals("loan-approval__PaymentReceived", testee.scopedIdentifier(MODULE, "PaymentReceived", ADAPTER));
    assertEquals("loan-approval__PAYMENT_FAILED", testee.scopedIdentifier(MODULE, "PAYMENT_FAILED", ADAPTER));
    assertEquals(
        "loan-approval__RiskAssessment__scoreApplicant",
        testee.scopedTaskDefinition(MODULE, PROCESS, "scoreApplicant", ADAPTER),
        "task definitions are scoped per process by default");
    assertNull(testee.tenantIdFor(MODULE, PROCESS, ADAPTER, "banking"), "the prefix IS the isolation - no tenant");
    // null identifiers stay null (an adapter may pass an absent value)
    assertNull(testee.scopedIdentifier(MODULE, null, ADAPTER));
    assertNull(testee.scopedTaskDefinition(MODULE, PROCESS, null, ADAPTER));

  }

  @Test
  @DisplayName("Task definitions may be scoped by the module alone")
  public void taskDefinitionsPerProcessCanBeDisabled() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX, null, null, Boolean.FALSE);

    assertEquals(
        "loan-approval__scoreApplicant",
        testee.scopedTaskDefinition(MODULE, PROCESS, "scoreApplicant", ADAPTER));
    assertEquals(
        "scoreApplicant",
        testee.plainTaskDefinition(MODULE, PROCESS, "loan-approval__scoreApplicant", ADAPTER));

  }

  @Test
  @DisplayName("Reading identifiers back strips a KNOWN prefix, never up to the first separator")
  public void readingBackStripsKnownPrefixOnly() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX);

    assertEquals(PROCESS, testee.plainProcessId(MODULE, "loan-approval__RiskAssessment", ADAPTER));
    assertEquals("PaymentReceived", testee.plainIdentifier(MODULE, "loan-approval__PaymentReceived", ADAPTER));
    assertEquals(
        "scoreApplicant",
        testee.plainTaskDefinition(MODULE, PROCESS, "loan-approval__RiskAssessment__scoreApplicant", ADAPTER));

    // an identifier NOT carrying the expected prefix is returned unchanged - the
    // separator is never searched for, so a value containing it survives
    assertEquals(
        "other-module__RiskAssessment",
        testee.plainProcessId(MODULE, "other-module__RiskAssessment", ADAPTER));
    assertEquals("plain", testee.plainProcessId(MODULE, "plain", ADAPTER));

    // in the other modes nothing is stripped at all
    assertEquals(
        "loan-approval__RiskAssessment",
        serviceWith(NameClashAvoidance.BY_ADAPTER).plainProcessId(MODULE, "loan-approval__RiskAssessment", ADAPTER));

  }

  @Test
  @DisplayName("Colliding scoped process ids fail with a message naming both processes")
  public void collidingProcessIdsAreReported() {

    final var testee = serviceWith(NameClashAvoidance.USE_PREFIX);

    // 'a' + 'b__c' and 'a__b' + 'c' both compose to 'a__b__c'
    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.validateNoCollidingProcessIds(
            ADAPTER,
            List.of(
                new NameClashAvoidanceSupport.DeployedProcess("a", "b__c"),
                new NameClashAvoidanceSupport.DeployedProcess("a__b", "c"))));
    final var message = exception.getMessage();
    assertTrue(message.contains("'a__b__c'"), () -> message);
    assertTrue(message.contains("'b__c'") && message.contains("'a__b'"), () -> message);

    // the same process reported twice (several files/adapters) is not a collision
    testee.validateNoCollidingProcessIds(
        ADAPTER,
        List.of(
            new NameClashAvoidanceSupport.DeployedProcess(MODULE, PROCESS),
            new NameClashAvoidanceSupport.DeployedProcess(MODULE, PROCESS)));
    testee.validateNoCollidingProcessIds(ADAPTER, null);

  }

  @Test
  @DisplayName("A BPMS without own isolation rejects BY_ADAPTER, naming the levels and the alternatives")
  public void byAdapterIsRejectedWithoutNativeIsolation() {

    // configured explicitly ...
    final var explicit = assertThrowsExactly(
        IllegalStateException.class,
        () -> serviceWith(NameClashAvoidance.BY_ADAPTER)
            .validateNativeIsolationSupported(ADAPTER, null, "the Process-Engine-API"));
    assertTrue(explicit.getMessage().contains("vanillabp.adapters.c8"), explicit::getMessage);
    assertTrue(explicit.getMessage().contains("use-prefix"), explicit::getMessage);
    assertTrue(explicit.getMessage().contains("none"), explicit::getMessage);

    // ... and by simply not configuring anything (BY_ADAPTER is the default)
    final var byDefault = assertThrowsExactly(
        IllegalStateException.class,
        () -> serviceWith(null).validateNativeIsolationSupported(ADAPTER, null, "the Process-Engine-API"));
    assertTrue(byDefault.getMessage().contains("nothing configured"), byDefault::getMessage);

    // a supported mode everywhere passes
    serviceWith(NameClashAvoidance.USE_PREFIX)
        .validateNativeIsolationSupported(ADAPTER, null, "the Process-Engine-API");
    serviceWith(NameClashAvoidance.NONE).validateNativeIsolationSupported(ADAPTER, null, "the Process-Engine-API");

    // per WORKFLOW MODULE: the mode resolved for the module being deployed decides,
    // and the message names that module
    final var perModule = assertThrowsExactly(
        IllegalStateException.class,
        () -> serviceWith(NameClashAvoidance.USE_PREFIX, NameClashAvoidance.BY_ADAPTER, null, null)
            .validateNativeIsolationSupported(ADAPTER, MODULE, "the Process-Engine-API"));
    assertTrue(perModule.getMessage().contains("'"
        + MODULE
        + "'"), perModule::getMessage);
    // ... and a module using a supported mode passes even though another one does not
    serviceWith(NameClashAvoidance.BY_ADAPTER, NameClashAvoidance.USE_PREFIX, null, null)
        .validateNativeIsolationSupported(ADAPTER, MODULE, "the Process-Engine-API");

  }

  @Test
  @DisplayName("Without configuration the ADAPTER's own default applies")
  public void adapterDefaultApplies() {

    // an adapter whose BPMS has to be set up for isolation first (Camunda 8)
    assertEquals(
        NameClashAvoidance.NONE,
        serviceWith(null, adapterDeclaring(NameClashAvoidance.NONE)).modeFor(MODULE, PROCESS, ADAPTER));
    // ... which a configured mode still overrules
    assertEquals(
        NameClashAvoidance.USE_PREFIX,
        serviceWith(NameClashAvoidance.USE_PREFIX, adapterDeclaring(NameClashAvoidance.NONE))
            .modeFor(MODULE, PROCESS, ADAPTER));
    // an adapter declaring no default keeps version 1's behavior ...
    assertEquals(
        NameClashAvoidance.BY_ADAPTER,
        serviceWith(null, adapterDeclaring(null)).modeFor(MODULE, PROCESS, ADAPTER));
    // ... as does an adapter unknown to the service (tests, platforms not wiring them)
    assertEquals(NameClashAvoidance.BY_ADAPTER, serviceWith(null).modeFor(MODULE, PROCESS, ADAPTER));

  }

  @Test
  @DisplayName("NONE is reported by the ADAPTER, once per workflow module and adapter id")
  public void noneIsReportedByTheAdapter() {

    final var byDefault = adapterDeclaring(NameClashAvoidance.NONE);
    final var testee = serviceWith(null, byDefault);

    // the mode is resolved again at every boundary, the WARN belongs to startup
    testee.modeFor(MODULE, PROCESS, ADAPTER);
    testee.modeFor(MODULE, PROCESS, ADAPTER);
    Mockito
        .verify(byDefault, Mockito.times(1))
        .warnAboutUnscopedIdentifiers(MODULE, true);

    // every workflow module is reported on its own ...
    testee.modeFor("other-module", null, ADAPTER);
    Mockito
        .verify(byDefault, Mockito.times(1))
        .warnAboutUnscopedIdentifiers("other-module", true);
    // ... while resolving the mode without one reports nothing (e.g. comparing two
    // adapter instances) - the per-module resolutions of the deployment do
    testee.modeFor(null, null, ADAPTER);
    Mockito
        .verify(byDefault, Mockito.never())
        .warnAboutUnscopedIdentifiers(Mockito.isNull(), Mockito.anyBoolean());

    // a CONFIGURED 'none' is reported as well, but as a deliberate choice
    final var configured = adapterDeclaring(NameClashAvoidance.BY_ADAPTER);
    serviceWith(NameClashAvoidance.NONE, configured).modeFor(MODULE, PROCESS, ADAPTER);
    Mockito
        .verify(configured)
        .warnAboutUnscopedIdentifiers(MODULE, false);

    // the other modes protect the identifiers, so there is nothing to report
    final var prefixing = adapterDeclaring(NameClashAvoidance.NONE);
    serviceWith(NameClashAvoidance.USE_PREFIX, prefixing).modeFor(MODULE, PROCESS, ADAPTER);
    Mockito
        .verify(prefixing, Mockito.never())
        .warnAboutUnscopedIdentifiers(Mockito.anyString(), Mockito.anyBoolean());

  }

  @Test
  @DisplayName("An adapter without native isolation defaulting to NONE is not asked to choose")
  public void unconfiguredLevelsHonorTheAdapterDefault() {

    // nothing configured, so BY_ADAPTER never applies - no boot failure to raise
    serviceWith(null, adapterDeclaring(NameClashAvoidance.NONE))
        .validateNativeIsolationSupported(ADAPTER, null, "the Process-Engine-API");

  }

  @Test
  @DisplayName("Without properties the service still answers the default")
  public void withoutPropertiesTheDefaultApplies() {

    final var testee = new NameClashAvoidanceService(null);

    assertEquals(NameClashAvoidance.BY_ADAPTER, testee.modeFor(MODULE, PROCESS, ADAPTER));
    assertEquals(PROCESS, testee.scopedProcessId(MODULE, PROCESS, ADAPTER));
    assertEquals(MODULE, testee.tenantIdFor(MODULE, PROCESS, ADAPTER, null));

  }

}
