package io.vanillabp.integration.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which adapter ids an adapter registers beans for. The convention is documented in
 * {@code MigrationAdapterProperties#adapterTypes()} and on the wiki. An adapter section
 * without a {@code type} takes its ID as the type. An id in {@code prioritized-adapters}
 * which IS an adapter type needs no section at all.
 * <p>
 * An application relies on both halves of that convention. Nothing here held the second
 * half, and a blueprint run found that. The helper bound the properties itself. It applied
 * the rule only where NO adapter section existed at all. Take a migration setup: one section
 * for the new BPMS, and the old one named in {@code prioritized-adapters}. It got no beans for
 * the old adapter. But the core derived the old adapter's section and expected those beans. So
 * the election found nothing which served that id.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterBeanRegistrarSupportTest {

  private static final String CAMUNDA7 = "camunda7";

  private static List<String> idsOf(
      final Map<String, Object> properties,
      final String adapterType) {

    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", new LinkedHashMap<>(properties)));
    final var ids = new LinkedList<String>();
    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment, adapterType, ids::add);
    return ids;

  }

  @Test
  @DisplayName("A section carrying nothing but the adapter's own keys takes its id as the type")
  public void aSectionWithoutATypeIsTheType() {

    // 'webapps' belongs to the Camunda 7 adapter's own overlay. So the core model knows none
    // of the keys of this section, and that must not decide whether the id exists
    assertEquals(
        List.of(CAMUNDA7),
        idsOf(
            Map.of(
                "vanillabp.prioritized-adapters[0]", CAMUNDA7,
                "vanillabp.adapters.camunda7.webapps.admin-user.id", "demo"),
            CAMUNDA7));

  }

  @Test
  @DisplayName("An id named in prioritized-adapters needs no section, even next to another adapter")
  public void aPrioritizedIdNeedsNoSection() {

    // the migration setup: the new BPMS is configured. The old one is named in the order,
    // and the core derives it from the classpath. So its beans have to exist
    assertEquals(
        List.of(CAMUNDA7),
        idsOf(
            Map.of(
                "vanillabp.prioritized-adapters[0]", "camunda8",
                "vanillabp.prioritized-adapters[1]", CAMUNDA7,
                "vanillabp.adapters.camunda8.rest-address", "http://localhost:8080"),
            CAMUNDA7));

  }

  @Test
  @DisplayName("The migration blueprint's configuration, which lost the old adapter")
  public void theMigrationSetupOfTheBlueprint() {

    // exactly what 'module-bpms-migration' configures. The new BPMS has one key which the
    // CORE model knows (name-clash-avoidance). The section of the old BPMS holds only the
    // adapter's own keys. That one core key used to decide whether the old adapter got
    // beans at all
    final var properties = Map.<String, Object>of(
        "vanillabp.prioritized-adapters[0]", "camunda8",
        "vanillabp.prioritized-adapters[1]", CAMUNDA7,
        "vanillabp.adapters.camunda8.name-clash-avoidance", "use-prefix",
        "vanillabp.adapters.camunda8.rest-address", "http://localhost:8080",
        "vanillabp.adapters.camunda7.webapps.admin-user.id", "demo");

    assertEquals(List.of(CAMUNDA7), idsOf(properties, CAMUNDA7));
    assertEquals(List.of("camunda8"), idsOf(properties, "camunda8"));

  }

  @Test
  @DisplayName("The wiki's own example of a migration serves both adapters")
  public void theExampleOfTheWiki() {

    // 'Several BPMS: naming the order is the whole configuration' of the Spring Boot page.
    // The id which IS a type has only the adapter's own key. The custom id names its type.
    // Both adapters have to get their beans. The example was broken before this test
    // existed. The custom id's 'type' made the core model bind, and the other section had
    // nothing the core knows

    final var properties = Map.<String, Object>of(
        "vanillabp.prioritized-adapters[0]", "camunda8",
        "vanillabp.prioritized-adapters[1]", "legacy",
        "vanillabp.adapters.camunda8.rest-address", "http://localhost:8080",
        "vanillabp.adapters.legacy.type", CAMUNDA7,
        "vanillabp.adapters.legacy.deployment-failure", "warn");

    assertEquals(List.of("camunda8"), idsOf(properties, "camunda8"));
    assertEquals(List.of("legacy"), idsOf(properties, CAMUNDA7));

  }

  @Test
  @DisplayName("An id of ANOTHER type is not served, whether it names its type or not")
  public void anotherTypeIsNotServed() {

    assertEquals(
        List.of(),
        idsOf(
            Map.of(
                "vanillabp.prioritized-adapters[0]", "camunda8",
                "vanillabp.adapters.camunda8.rest-address", "http://localhost:8080"),
            CAMUNDA7));
    assertEquals(
        List.of(),
        idsOf(
            Map.of(
                "vanillabp.prioritized-adapters[0]", "old-engine",
                "vanillabp.adapters.old-engine.type", "camunda8"),
            CAMUNDA7));

  }

  @Test
  @DisplayName("An id named like this type but declaring another one belongs to that other adapter")
  public void anIdNamedLikeTheTypeMayDeclareAnother() {

    assertEquals(
        List.of(),
        idsOf(
            Map.of(
                "vanillabp.prioritized-adapters[0]", CAMUNDA7,
                "vanillabp.adapters.camunda7.type", "camunda8"),
            CAMUNDA7));

  }

  @Test
  @DisplayName("A custom id naming this type is served, and the type's own name next to it")
  public void aCustomIdNamingTheTypeIsServed() {

    assertEquals(
        List.of("camunda7", "old-engine"),
        idsOf(
            Map.of(
                "vanillabp.prioritized-adapters[0]", CAMUNDA7,
                "vanillabp.prioritized-adapters[1]", "old-engine",
                "vanillabp.adapters.camunda7.database-schema-update", "true",
                "vanillabp.adapters.old-engine.type", CAMUNDA7),
            CAMUNDA7));

  }

  /**
   * What a registrar gets handed, and what it is told when a collaborator did not
   * resolve: the two the platform provides for every application are looked up as
   * beans, the two an application may not ask for as providers.
   */
  @org.junit.jupiter.api.Nested
  @DisplayName("The collaborators a registrar asks for")
  class Collaborators {

    private org.springframework.beans.factory.BeanRegistry.SupplierContext contextWithout(
        final Class<?>... absent) {

      final var absentTypes = List.of(absent);
      final var context = org.mockito.Mockito
          .mock(org.springframework.beans.factory.BeanRegistry.SupplierContext.class);
      org.mockito.Mockito
          .lenient()
          .when(context.bean(org.mockito.ArgumentMatchers.any(Class.class)))
          .thenAnswer(invocation -> org.mockito.Mockito.mock((Class<?>) invocation.getArgument(0)));
      org.mockito.Mockito
          .lenient()
          .when(context.beanProvider(org.mockito.ArgumentMatchers.any(Class.class)))
          .thenAnswer(invocation -> {
            final Class<?> type = invocation.getArgument(0);
            final var provider = org.mockito.Mockito
                .mock(org.springframework.beans.factory.ObjectProvider.class);
            org.mockito.Mockito
                .lenient()
                .when(provider.getIfAvailable())
                .thenReturn(
                    absentTypes.contains(type)
                        ? null
                        : org.mockito.Mockito.mock(type));
            return provider;
          });
      return context;

    }

    @Test
    @DisplayName("A complete context yields every collaborator")
    public void aCompleteContextYieldsEverything() {

      final var collaborators = AdapterBeanRegistrarSupport.collaborators(contextWithout(), "c7");

      assertEquals("c7", collaborators.adapterId());
      org.junit.jupiter.api.Assertions.assertNotNull(collaborators.workflowTaskWiring());
      org.junit.jupiter.api.Assertions.assertNotNull(collaborators.workflowTaskInvoker());
      org.junit.jupiter.api.Assertions.assertNotNull(collaborators.scoping());
      org.junit.jupiter.api.Assertions.assertNotNull(collaborators.workflowAggregateSync());
      org.junit.jupiter.api.Assertions.assertNotNull(collaborators.preCommitRegistrar());
      org.junit.jupiter.api.Assertions.assertTrue(collaborators.workflowEndedInvoker().isPresent());
      org.junit.jupiter.api.Assertions.assertTrue(collaborators.bpmsInitiatedStartInvoker().isPresent());

    }

    @Test
    @DisplayName("A collaborator which did not resolve is absent and named in the log")
    public void anUnresolvedCollaboratorIsNamed(
        final io.vanillabp.integration.test.utils.CapturedOutput output) {

      final var collaborators = AdapterBeanRegistrarSupport
          .collaborators(
              contextWithout(io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker.class),
              "dummy");

      org.junit.jupiter.api.Assertions
          .assertTrue(collaborators.workflowEndedInvoker().isEmpty(), "the adapter reports no workflow end");
      org.junit.jupiter.api.Assertions
          .assertTrue(collaborators.bpmsInitiatedStartInvoker().isPresent(), "the other one arrived");
      final var logged = output.getAll();
      org.junit.jupiter.api.Assertions.assertTrue(logged.contains("'dummy'"), "the line names the adapter id: "
          + logged);
      org.junit.jupiter.api.Assertions
          .assertTrue(logged.contains("workflowEndedInvoker"), "and the collaborator: "
              + logged);

    }

  }

}
