package io.vanillabp.integration.adapter.migration.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.ReadableKeys.Position;

/**
 * Ends the start for the keys below <code>vanillabp</code> which nothing reads, and says for
 * each of them what to write instead.
 * <p>
 * A key which nothing reads changes nothing. The application boots and runs without the
 * setting the developer meant, and nobody tells them. So such a key ends the start, on both
 * platforms. The platform finds the keys: it knows which keys its binding took over and which
 * keys the configuration holds. This class only writes the message, so both platforms can say
 * it the same way.
 * <p>
 * For each key the message gives the first answer of this list which fits:
 * <ol>
 * <li>the key of version 1 it is, and what version 2 reads instead,</li>
 * <li>the other places where the same key IS read, with the level of each of them. This is
 * the answer for a key written at the wrong level, for example a tenant at a task,</li>
 * <li>the key it most likely is, for a typo,</li>
 * <li>the keys which are read at the place where the written key went wrong.</li>
 * </ol>
 * The keys are printed sorted, so the same configuration reads the same way on every boot.
 * <p>
 * See decision 148 in the repository's DECISIONS.md for why a key nobody
 * reads ends the start.
 */
public final class UnreadKeys {

  /**
   * The names of the maps of the core tree whose keys are ids, mapped to the words a message
   * uses for such an id. They also tell the level of a key: a key below <code>tasks</code>
   * is written at a task. Adapters and extensions add no such maps, they put their keys
   * below these.
   */
  private static final Map<String, String> IDS = Map
      .of(
          "adapters", "adapter",
          "workflowmodules", "workflow-module",
          "workflows", "workflow",
          "tasks", "task",
          "messages", "message",
          "extensions", "extension");

  private UnreadKeys() {

  }

  /**
   * A key of the configuration which no binding took over.
   *
   * @param key The key as the platform prints it
   * @param steps The steps of the key, <code>vanillabp</code> first. The index of a list
   *          element is left out, because a list is read as a whole
   * @param origin Where the key was written, for example the file and the line. Or
   *          <code>null</code> if the platform does not know
   */
  public record UnreadKey(
                          String key,
                          List<String> steps,
                          String origin) {
  }

  /**
   * Builds the message which ends the start, if any of the given keys is really not read.
   * <p>
   * A key which ends at a position some part reads is left out. That is a section written as
   * <code>{}</code>, or a key whose binding took over no value. The platform reports what its
   * binding did not take over, and this tree is the second opinion.
   *
   * @param readable The keys which the core, the adapters and the extensions read
   * @param candidates The keys the platform's binding did not take over
   * @return The exception to throw, or empty if every candidate is read after all
   */
  public static Optional<IllegalStateException> refusal(
      final ReadableKeys readable,
      final Collection<UnreadKey> candidates) {

    final var explanations = new LinkedHashMap<String, String>();
    candidates
        .stream()
        .sorted(Comparator.comparing(UnreadKey::key))
        .forEach(candidate -> {
          final var explanation = explain(readable, candidate);
          if (explanation != null) {
            explanations.putIfAbsent(candidate.key(), "%s%s\n    %s"
                .formatted(
                    candidate.key(),
                    candidate.origin() == null
                        ? ""
                        : " (%s)".formatted(candidate.origin()),
                    explanation.replace("\n", "\n    ")));
          }
        });
    if (explanations.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(new IllegalStateException(
        """
            Nothing reads %s below '%s'. A key nobody reads changes nothing. The application \
            would run without the setting you meant, so the start ends here. Keys below '%s' are \
            read by VanillaBP and by the adapters and extensions on the classpath, nobody else.

              %s"""
            .formatted(
                explanations.size() == 1
                    ? "this key"
                    : "these %d keys".formatted(explanations.size()),
                MigrationAdapterProperties.PREFIX,
                MigrationAdapterProperties.PREFIX,
                String.join("\n\n  ", explanations.values()))));

  }

  /**
   * The answer for one key.
   *
   * @return The lines which explain the key, or <code>null</code> if some part reads it
   */
  static String explain(
      final ReadableKeys readable,
      final UnreadKey key) {

    final var steps = key.steps();
    final var reached = deepest(readable.root(), steps, 1);
    if (reached.depth() >= steps.size()) {
      return null;
    }

    final var written = String.join(".", steps.subList(0, reached.depth()));
    final var wrong = steps.get(reached.depth());
    final var rest = steps.subList(reached.depth(), steps.size());

    final var versionOne = versionOneHint(reached.position(), written, wrong);
    if (versionOne != null) {
      return versionOne;
    }

    final var places = placesWhichRead(readable, reached.position(), steps, rest);
    if (!places.isEmpty()) {
      final var width = places
          .keySet()
          .stream()
          .mapToInt(String::length)
          .max()
          .orElse(0);
      return """
          '%s' is not read at %s. It is read here:
            %s
          Move the line to one of these places, or remove it."""
          .formatted(
              String.join(".", rest),
              levelOf(reached.position()),
              places
                  .entrySet()
                  .stream()
                  // the padding carries the gap in front of 'at' as well, so the
                  // places line up in one column
                  .map(place -> "%s%s at %s"
                      .formatted(
                          place.getKey(),
                          " ".repeat(width - place
                              .getKey()
                              .length() + 2),
                          place.getValue()))
                  .collect(Collectors.joining("\n  ")));
    }

    if (reached
        .position()
        .isValue() && reached
            .position()
            .namedBelow()
            .isEmpty()) {
      return "'%s' is a single value, so nothing below it is read. Remove the part after it."
          .formatted(written);
    }

    final var closest = closestName(reached.position(), wrong);
    if (closest != null) {
      final var meant = new ArrayList<String>(steps.subList(0, reached.depth()));
      meant.add(closest);
      meant.addAll(steps.subList(reached.depth() + 1, steps.size()));
      return "There is no key '%s' at '%s'. Did you mean '%s'?"
          .formatted(wrong, written, String.join(".", meant));
    }

    final var known = new ArrayList<String>();
    reached
        .position()
        .namedBelow()
        .forEach(position -> known.add(position.name()));
    if (reached
        .position()
        .anyKeyOrNull() != null) {
      known.add("<%s>".formatted(idWordOf(reached
          .position()
          .name())));
    }
    return """
        There is no key '%s' at '%s'. These keys are read there:
          %s"""
        .formatted(wrong, written, String.join(", ", known));

  }

  /**
   * The deepest position which the steps of a key reach.
   *
   * @param position Where to go on from
   * @param steps The steps of the key
   * @param index The first step which is not matched yet
   * @return The position and how many steps reached it. All steps for a key which is read
   */
  private static Reached deepest(
      final Position position,
      final List<String> steps,
      final int index) {

    if (position.readsAnythingBelow()) {
      return new Reached(position, steps.size());
    }
    var best = new Reached(position, index);
    if (index >= steps.size()) {
      return best;
    }
    final var named = position.namedOrNull(steps.get(index));
    if (named != null) {
      final var below = deepest(named, steps, index + 1);
      if (below.depth() > best.depth()) {
        best = below;
      }
    }
    final var anyKey = position.anyKeyOrNull();
    if (anyKey != null) {
      final var below = deepest(anyKey, steps, index + 1);
      if (below.depth() > best.depth()) {
        best = below;
      }
    }
    return best;

  }

  private record Reached(
                         Position position,
                         int depth) {
  }

  /**
   * Every place at which the rest of the key is read: a position whose named position below
   * is the step which went wrong, and below which the remaining steps lead to a value.
   *
   * @return The keys of the places, filled with the ids of the written key, mapped to their
   *         levels. The shortest key first
   */
  private static Map<String, String> placesWhichRead(
      final ReadableKeys readable,
      final Position wentWrongAt,
      final List<String> steps,
      final List<String> rest) {

    final var found = new LinkedHashMap<String, String>();
    readable
        .root()
        .withEverythingBelow()
        .stream()
        .filter(position -> isTheSameKindOfPlace(position, wentWrongAt))
        .filter(position -> {
          final var first = position.namedOrNull(rest.getFirst());
          return (first != null) && readsTheRest(first, rest, 1);
        })
        .map(position -> {
          final var path = new ArrayList<String>();
          position
              .path()
              .forEach(step -> path.add(step.isAnyKey()
                  ? idOf(step, steps)
                  : step.name()));
          path.addAll(rest);
          return new Place(String.join(".", path), levelOf(position));
        })
        .sorted(Comparator
            .comparingInt((
                Place place) -> place
                    .key()
                    .split("\\.").length)
            .thenComparing(Place::key))
        .forEach(place -> found.putIfAbsent(place.key(), place.level()));
    return found;

  }

  /**
   * Whether a key below one position means the same as below the other. That holds for two
   * levels, which are <code>vanillabp</code> itself and the ids of the maps of the tree, such
   * as an adapter section and the adapter section of a task. It also holds for two sections of
   * the same name. A key of the same name in another section is something else:
   * <code>time-to-live</code> of the cache of workflow adapters is not the time-to-live of a
   * message.
   */
  private static boolean isTheSameKindOfPlace(
      final Position candidate,
      final Position wentWrongAt) {

    final var isLevel = (wentWrongAt.parent() == null) || wentWrongAt.isAnyKey();
    if (isLevel) {
      return (candidate.parent() == null) || candidate.isAnyKey();
    }
    return !candidate.isAnyKey() && (candidate.parent() != null) && ReadableKeys
        .comparable(candidate.name())
        .equals(ReadableKeys.comparable(wentWrongAt.name()));

  }

  private record Place(
                       String key,
                       String level) {
  }

  private static boolean readsTheRest(
      final Position position,
      final List<String> rest,
      final int index) {

    if (position.readsAnythingBelow()) {
      return true;
    }
    if (index >= rest.size()) {
      return position.isValue();
    }
    final var named = position.namedOrNull(rest.get(index));
    if ((named != null) && readsTheRest(named, rest, index + 1)) {
      return true;
    }
    final var anyKey = position.anyKeyOrNull();
    return (anyKey != null) && readsTheRest(anyKey, rest, index + 1);

  }

  /**
   * The id to print for the id of a map in a place: the id the written key has for the same
   * map, or a placeholder where it has none.
   */
  private static String idOf(
      final Position id,
      final List<String> steps) {

    final var map = ReadableKeys.comparable(id
        .parent()
        .name());
    for (var index = steps.size() - 2; index >= 0; --index) {
      if (ReadableKeys
          .comparable(steps.get(index))
          .equals(map)) {
        return steps.get(index + 1);
      }
    }
    return "<%s>".formatted(idWordOf(id
        .parent()
        .name()));

  }

  private static String idWordOf(
      final String mapName) {

    return IDS.getOrDefault(ReadableKeys.comparable(mapName), "key");

  }

  /**
   * The level of a position in words: the most specific id above it, and whether the
   * position belongs to one adapter.
   */
  static String levelOf(
      final Position position) {

    final var ids = position
        .path()
        .stream()
        .filter(Position::isAnyKey)
        .map(id -> ReadableKeys.comparable(id
            .parent()
            .name()))
        .toList();
    final var forOneAdapter = ids.contains("adapters");
    final String level;
    if (ids.contains("tasks")) {
      level = "a task";
    } else if (ids.contains("messages")) {
      level = "a message";
    } else if (ids.contains("workflows")) {
      level = "a workflow";
    } else if (ids.contains("workflowmodules")) {
      level = "a workflow module";
    } else {
      return forOneAdapter
          ? "an adapter"
          : "the application";
    }
    return forOneAdapter
        ? "%s, for one adapter".formatted(level)
        : level;

  }

  /**
   * What became of a key of version 1 which version 2 does not read. Only the keys of the
   * core are here. An adapter refuses the keys it had in version 1 itself, before this check
   * runs.
   */
  private static String versionOneHint(
      final Position position,
      final String written,
      final String wrong) {

    final var key = ReadableKeys.comparable(wrong);
    if ("defaultadapter".equals(key) && (position.namedOrNull("prioritized-adapters") != null)) {
      return """
          This is the key of version 1. It named one adapter. Version 2 reads an ordered list
          instead, '%s.prioritized-adapters'. Its first entry starts new workflows."""
          .formatted(written);
    }
    if ("resilience".equals(key) && (position.parent() == null)) {
      return "This section of version 1 was never read by anything. Remove it.";
    }
    return null;

  }

  /**
   * The name read at a position which is closest to a step that is not read there. Close
   * means a few letters differ, as in a typo.
   *
   * @return The name, or <code>null</code> if no name is close enough
   */
  private static String closestName(
      final Position position,
      final String wrong) {

    final var comparableWrong = ReadableKeys.comparable(wrong);
    final var allowed = comparableWrong.length() <= 4
        ? 1
        : Math.max(2, comparableWrong.length() / 3);
    String closest = null;
    var closestDistance = Integer.MAX_VALUE;
    for (final var candidate : position.namedBelow()) {
      final var distance = distance(comparableWrong, ReadableKeys.comparable(candidate.name()));
      if ((distance <= allowed) && (distance < closestDistance)) {
        closest = candidate.name();
        closestDistance = distance;
      }
    }
    return closest;

  }

  /** The number of letters to insert, delete or replace to turn one text into the other. */
  static int distance(
      final String first,
      final String second) {

    var previous = new int[second.length() + 1];
    var current = new int[second.length() + 1];
    for (var j = 0; j <= second.length(); ++j) {
      previous[j] = j;
    }
    for (var i = 1; i <= first.length(); ++i) {
      current[0] = i;
      for (var j = 1; j <= second.length(); ++j) {
        final var replace = previous[j - 1] + (first.charAt(i - 1) == second.charAt(j - 1)
            ? 0
            : 1);
        current[j] = Math.min(replace, Math.min(previous[j] + 1, current[j - 1] + 1));
      }
      final var swap = previous;
      previous = current;
      current = swap;
    }
    return previous[second.length()];

  }

}
