package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.extension.spi.settings.SettingsLevel;
import io.vanillabp.integration.extension.spi.settings.SettingsResolution;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The walk over the eight positions, asked with a TYPED tree rather than with the flat
 * text VanillaBP binds below <code>vanillabp.extensions</code>. That is the shape the
 * Business Cockpit hands in: it keeps its own section name and its own types, and only
 * the order of the positions comes from here (decision 53 in the repository's
 * DECISIONS.md).
 * <p>
 * That the core's own tree gives the same answers is held by
 * {@code ExtensionSettingsResolutionTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SettingsResolutionTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String TASK = "awaitSignature";

  private static final String ADAPTER = "saas";

  /**
   * What a plug-in with a section of its own binds: a typed value, not a string keyed by
   * the rest of a path.
   *
   * @param title What the plug-in calls this scope
   */
  private record Section(String title) {
  }

  /**
   * One level of such a tree: what it says, what it says about one adapter, and the
   * levels below it.
   *
   * @param settings What this level says
   * @param ofAdapters What this level says per adapter
   * @param below The levels below, by id
   */
  private record Level(
                       Section settings,
                       Map<String, Section> ofAdapters,
                       Map<String, Level> below) implements SettingsLevel<Section> {

    private static Level saying(
        final String title) {

      return new Level(new Section(title), Map.of(), Map.of());

    }

    private Level withAdapter(
        final String adapterId,
        final String title) {

      return new Level(settings, Map.of(adapterId, new Section(title)), below);

    }

    private Level withLevelBelow(
        final String id,
        final Level level) {

      return new Level(settings, ofAdapters, Map.of(id, level));

    }

    @Override
    public Section settingsOfAdapter(
        final String adapterId) {

      return ofAdapters.get(adapterId);

    }

    @Override
    public SettingsLevel<Section> levelBelow(
        final String id) {

      return below.get(id);

    }

  }

  /**
   * All eight positions filled, each with the name of its own position.
   */
  private static Level tree() {

    final var task = Level
        .saying("task")
        .withAdapter(ADAPTER, "task of the adapter");
    final var workflow = Level
        .saying("workflow")
        .withAdapter(ADAPTER, "workflow of the adapter")
        .withLevelBelow(TASK, task);
    final var module = Level
        .saying("workflow module")
        .withAdapter(ADAPTER, "workflow module of the adapter")
        .withLevelBelow(PROCESS, workflow);
    return Level
        .saying("application")
        .withAdapter(ADAPTER, "application of the adapter")
        .withLevelBelow(MODULE, module);

  }

  @Test
  @DisplayName("The eight positions are answered from the least specific to the most specific one")
  public void thePositionsAreOrdered() {

    final var positions = SettingsResolution.positions(tree(), MODULE, PROCESS, TASK, ADAPTER);

    assertEquals(
        List
            .of(
                new Section("application"),
                new Section("application of the adapter"),
                new Section("workflow module"),
                new Section("workflow module of the adapter"),
                new Section("workflow"),
                new Section("workflow of the adapter"),
                new Section("task"),
                new Section("task of the adapter")),
        positions);

  }

  @Test
  @DisplayName("The most specific position which says something wins")
  public void theMostSpecificPositionWins() {

    final var tree = tree();

    assertEquals(
        "task of the adapter",
        SettingsResolution.resolve(tree, MODULE, PROCESS, TASK, ADAPTER, Section::title));
    assertEquals(
        "task",
        SettingsResolution.resolve(tree, MODULE, PROCESS, TASK, null, Section::title));
    assertEquals(
        "workflow of the adapter",
        SettingsResolution.resolve(tree, MODULE, PROCESS, null, ADAPTER, Section::title));
    assertEquals(
        "workflow module of the adapter",
        SettingsResolution.resolve(tree, MODULE, null, null, ADAPTER, Section::title));
    assertEquals(
        "application of the adapter",
        SettingsResolution.resolve(tree, null, null, null, ADAPTER, Section::title));
    assertEquals(
        "application",
        SettingsResolution.resolve(tree, null, null, null, null, Section::title));

  }

  @Test
  @DisplayName("A value of one adapter never reaches another one")
  public void anotherAdapterReadsTheGeneralSections() {

    assertEquals(
        "task",
        SettingsResolution.resolve(tree(), MODULE, PROCESS, TASK, "on-premise", Section::title));

  }

  @Test
  @DisplayName("A position nothing is configured at is left out")
  public void anEmptyPositionIsSkipped() {

    final var tree = Level
        .saying("application")
        .withLevelBelow(MODULE, new Level(null, Map.of(), Map.of()));

    assertEquals(
        List.of(new Section("application")),
        SettingsResolution.positions(tree, MODULE, null, null, ADAPTER));
    assertEquals(
        "application",
        SettingsResolution.resolve(tree, MODULE, null, null, ADAPTER, Section::title));

  }

  @Test
  @DisplayName("An id nobody configured stops the walk instead of failing it")
  public void anUnknownIdStopsTheWalk() {

    final var tree = tree();

    assertEquals(
        "application of the adapter",
        SettingsResolution.resolve(tree, "no-such-module", PROCESS, TASK, ADAPTER, Section::title));
    assertEquals(
        "workflow module of the adapter",
        SettingsResolution.resolve(tree, MODULE, "NoSuchProcess", TASK, ADAPTER, Section::title));
    assertEquals(
        "workflow of the adapter",
        SettingsResolution.resolve(tree, MODULE, PROCESS, "noSuchTask", ADAPTER, Section::title));

  }

  @Test
  @DisplayName("A value no position writes is null rather than a neighbour's value")
  public void whatNobodyWritesIsNull() {

    assertNull(
        SettingsResolution.resolve(tree(), MODULE, PROCESS, TASK, ADAPTER, section -> null));
    assertEquals(1, SettingsResolution.positions(tree(), null, null, null, null).size());

  }

  @Test
  @DisplayName("The positions are read-only, so a caller cannot change what the next one reads")
  public void thePositionsAreReadOnly() {

    final var positions = SettingsResolution.positions(tree(), MODULE, null, null, null);

    assertThrows(
        UnsupportedOperationException.class,
        () -> positions.add(new Section("mine")));

  }

}
