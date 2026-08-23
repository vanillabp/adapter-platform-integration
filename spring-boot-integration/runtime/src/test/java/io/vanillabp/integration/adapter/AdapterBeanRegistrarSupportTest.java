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
 * {@code MigrationAdapterProperties#adapterTypes()} and on the wiki: an adapter section
 * carrying no {@code type} takes its ID as the type, and an id named in
 * {@code prioritized-adapters} which IS an adapter type needs no section at all.
 * <p>
 * Both halves of that convention are what an application relies on, and the second one
 * was held by nothing here (story 106's rule, found while a blueprint ran): the helper
 * bound the properties itself and applied the derivation only where NO adapter section
 * existed at all. A migration setup - one section for the new BPMS, the old one named in
 * {@code prioritized-adapters} - therefore got no beans for the old adapter, while the
 * core derived its section and expected them, and the election found nothing serving that
 * id.
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

    // 'webapps' belongs to the Camunda 7 adapter's own overlay, so the core model knows
    // none of the keys of this section - which must not decide whether the id exists
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

    // the migration setup: the new BPMS is configured, the old one is named in the order
    // and derived from the classpath by the core (story 34) - so its beans have to exist
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

    // exactly what 'module-bpms-migration' configures: the new BPMS with one key the CORE
    // model knows (name-clash-avoidance) next to the old BPMS whose section holds nothing
    // but the adapter's own keys. That one core key used to decide whether the old
    // adapter got beans at all
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

    // 'Several BPMS: naming the order is the whole configuration' of the Spring Boot page:
    // the id which IS a type carries only the adapter's own key, the custom id names its
    // type. Both adapters have to get their beans - the example was broken before this
    // test existed, because the custom id's 'type' made the core model bind and the other
    // section had nothing the core knows
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

}
