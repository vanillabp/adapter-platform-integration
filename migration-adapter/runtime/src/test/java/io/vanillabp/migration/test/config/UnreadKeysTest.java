package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.ReadableKeys;
import io.vanillabp.integration.adapter.migration.config.UnreadKeys;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The message about keys nobody reads, built from a tree as a platform builds it. The
 * platforms hold the whole way from a configuration file to the message, see
 * {@code AKeyNobodyReadsEndsTheStartTest} on Spring Boot. This test holds the cases which a
 * booted application hardly reaches.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnreadKeysTest {

  /** A tree shaped like the core tree, with one adapter key and one map of plain texts. */
  private static ReadableKeys readable() {

    final var readable = new ReadableKeys();
    final var root = readable.root();
    root
        .named("prioritized-adapters")
        .readAsValue();
    root
        .named("adapters")
        .anyKey()
        .named("tenant-id")
        .readAsValue();
    root
        .named("extensions")
        .anyKey()
        .readWithAnythingBelow();
    final var module = root
        .named("workflow-modules")
        .anyKey();
    module
        .named("prioritized-adapters")
        .readAsValue();
    module
        .named("adapters")
        .anyKey()
        .named("tenant-id")
        .readAsValue();
    module
        .named("workflows")
        .anyKey()
        .named("tasks")
        .anyKey()
        .named("adapters")
        .anyKey()
        .named("job-timeout")
        .readAsValue();
    return readable;

  }

  private static UnreadKeys.UnreadKey key(
      final String key) {

    return new UnreadKeys.UnreadKey(key, Arrays.asList(key.split("\\.")), null);

  }

  private static String messageFor(
      final String... keys) {

    return UnreadKeys
        .refusal(readable(), Arrays
            .stream(keys)
            .map(UnreadKeysTest::key)
            .toList())
        .orElseThrow()
        .getMessage();

  }

  @Test
  @DisplayName("A key which ends at a position some part reads is no key nobody reads")
  public void aKeyWhichIsReadAfterAll() {

    assertTrue(UnreadKeys
        .refusal(
            readable(), List
                .of(
                    key("vanillabp.adapters.c7"), key("vanillabp.extensions.cockpit.any.thing"), key(
                        "vanillabp.workflow-modules.m.workflows.w.tasks.t.adapters.c7.job-timeout")))
        .isEmpty());

  }

  @Test
  @DisplayName("A key at the wrong level names every level which reads it, with the ids of the written key")
  public void aKeyAtTheWrongLevel() {

    final var message = messageFor("vanillabp.workflow-modules.m.workflows.w.tasks.t.adapters.c7.tenant-id");

    assertTrue(
        message.endsWith(String.join(
            "\n", "", "  vanillabp.workflow-modules.m.workflows.w.tasks.t.adapters.c7.tenant-id",
            "    'tenant-id' is not read at a task, for one adapter. It is read here:",
            "      vanillabp.adapters.c7.tenant-id                      at an adapter",
            "      vanillabp.workflow-modules.m.adapters.c7.tenant-id   at a workflow module, for one adapter",
            "    Move the line to one of these places, or remove it.")),
        message);

  }

  @Test
  @DisplayName("A place whose ids the written key does not have gets placeholders")
  public void placeholdersForTheIdsTheKeyDoesNotHave() {

    final var message = messageFor("vanillabp.job-timeout");

    assertTrue(
        message.contains("vanillabp.workflow-modules.<workflow-module>.workflows.<workflow>.tasks.<task>"
            + ".adapters.<adapter>.job-timeout   at a task, for one adapter"),
        message);

  }

  @Test
  @DisplayName("The section 'resilience' of version 1 is named as such")
  public void theResilienceOfVersionOne() {

    assertTrue(messageFor("vanillabp.resilience.retries").contains(
        "This section of version 1 was never read by anything. Remove it."));

  }

  @Test
  @DisplayName("A key which is no typo lists the keys of its place, an id as a placeholder")
  public void theKeysOfThePlace() {

    final var message = messageFor("vanillabp.workflow-modules.m.something");
    assertTrue(
        message.contains("There is no key 'something' at 'vanillabp.workflow-modules.m'. These keys are read there:\n"
            + "      adapters, prioritized-adapters, workflows"),
        message);
    assertTrue(messageFor("vanillabp.workflow-modules.m.workflows.w.tasks.t.adapters.c7.job-timeout.more").contains(
        "'vanillabp.workflow-modules.m.workflows.w.tasks.t.adapters.c7.job-timeout' is a single value, so nothing "
            + "below it is read."));

  }

  @Test
  @DisplayName("Several keys are counted and sorted")
  public void severalKeys() {

    final var message = messageFor("vanillabp.zzz", "vanillabp.aaa");

    assertTrue(message.startsWith("Nothing reads these 2 keys below 'vanillabp'."), message);
    assertTrue(message.indexOf("vanillabp.aaa") < message.indexOf("vanillabp.zzz"), message);

  }

  @Test
  @DisplayName("A typo is found by the number of letters which differ")
  public void typos() {

    assertTrue(messageFor("vanillabp.prioritised-adapter").contains("Did you mean 'vanillabp.prioritized-adapters'?"));
    // a short name allows one letter only
    assertTrue(messageFor("vanillabp.adapterz.c7.tenant-id").contains(
        "Did you mean 'vanillabp.adapters.c7.tenant-id'?"));
    assertFalse(messageFor("vanillabp.adapt").contains("Did you mean"));

  }

}
