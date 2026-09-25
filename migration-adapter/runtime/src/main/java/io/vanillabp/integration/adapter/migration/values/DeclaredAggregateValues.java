package io.vanillabp.integration.adapter.migration.values;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an application declared about the values of ONE workflow aggregate, read from
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.declared-aggregate-values</code>.
 * <p>
 * A declaration does two jobs, and both are the same statement: the developer looked at
 * this value and knows what it is. It lets a value whose type is neither a
 * <code>boolean</code> nor a text travel to the BPMS, and it may name the value to write
 * where the path does not resolve.
 *
 * <h2>The path which does not resolve</h2>
 *
 * A section of a larger process usually sits as a sub-object in the aggregate, and as
 * long as the section has not run that object is <code>null</code>. Then
 * <code>shipping.express</code> reaches the BPMS as nothing, and a gateway reading it
 * decides on a value nobody computed. Writing <code>false</code> there would be the worst
 * answer of all, because it cannot be told apart from a computed <code>false</code>.
 * <p>
 * So a declared path which does not resolve ends the sync with a message naming the path
 * and the link which is <code>null</code>, unless the declaration says what to write
 * instead (<code>shipping.express=false</code>). A stopped workflow is better than a
 * decision on a value nobody computed.
 */
public class DeclaredAggregateValues {

  /**
   * Built by the sync model, once per application, and filled by the startup check which
   * reads the configuration.
   */
  public DeclaredAggregateValues() {
  }

  /**
   * The entry which covers every value below a path rather than one of them:
   * <code>shipping.*</code>. Written on its own it covers every value of the aggregate,
   * which is the one line a workflow sharing its whole aggregate needs.
   */
  public static final String EVERYTHING_BELOW = "*";

  /**
   * One declared value.
   *
   * @param path The attribute names, read against the aggregate from the first one on
   * @param everythingBelow Whether the entry ended in <code>*</code> and therefore
   *          covers every value below the path
   * @param substitute What to share where the path does not resolve, or
   *          <code>null</code> where nothing was named
   */
  public record Declaration(
                            List<String> path,
                            boolean everythingBelow,
                            String substitute) {

    /**
     * How the path is written in a message and in the configuration.
     *
     * @return The path as a BPMN expression reads it
     */
    public String describePath() {

      if (!everythingBelow) {
        return String.join(".", path);
      }
      return path.isEmpty()
          ? EVERYTHING_BELOW
          : "%s.%s".formatted(String.join(".", path), EVERYTHING_BELOW);

    }

  }

  /**
   * What was declared per workflow-aggregate class. The class is the key because the
   * adapters ask for the shared values with the aggregate in hand and without naming a
   * workflow. Two workflows sharing one aggregate class therefore share their
   * declarations, which is reported where it happens.
   */
  private final Map<Class<?>, List<Declaration>> byAggregateClass = new ConcurrentHashMap<>();

  /**
   * Reads the configured entries of one workflow.
   *
   * @param workflowAggregateClass The workflow-aggregate class of that workflow
   * @param entries The configured entries, each a path, a path ending in
   *          <code>.*</code>, a lone <code>*</code> for every value of the aggregate, or a
   *          path with <code>=</code> and the value to share where it does not resolve
   */
  public void register(
      final Class<?> workflowAggregateClass,
      final List<String> entries) {

    if ((workflowAggregateClass == null) || (entries == null) || entries.isEmpty()) {
      return;
    }
    final var declarations = byAggregateClass
        .computeIfAbsent(workflowAggregateClass, aggregateClass -> new LinkedList<>());
    synchronized (declarations) {
      entries
          .stream()
          .map(DeclaredAggregateValues::parse)
          .filter(java.util.Objects::nonNull)
          .filter(declaration -> declarations
              .stream()
              .noneMatch(known -> known.path().equals(declaration.path())))
          .forEach(declarations::add);
    }

  }

  /**
   * Parses one configured entry.
   *
   * @param entry The entry as it was written
   * @return The declaration, or <code>null</code> where the entry names no path
   */
  private static Declaration parse(
      final String entry) {

    if ((entry == null) || entry.isBlank()) {
      return null;
    }
    final var equals = entry.indexOf('=');
    final var written = equals < 0
        ? entry.trim()
        : entry.substring(0, equals).trim();
    final var substitute = equals < 0
        ? null
        : entry.substring(equals + 1).trim();
    final var segments = new LinkedList<String>();
    var everythingBelow = false;
    for (final var segment : written.split("\\.")) {
      if (segment.isBlank()) {
        continue;
      }
      if (segment.equals(EVERYTHING_BELOW)) {
        everythingBelow = true;
        continue;
      }
      segments.add(segment);
    }
    if (segments.isEmpty() && !everythingBelow) {
      return null;
    }
    // a lone '*' names no path and covers every value of the aggregate, which is what a
    // workflow sharing its whole aggregate says in one line instead of twenty
    return new Declaration(List.copyOf(segments), everythingBelow, substitute);

  }

  /**
   * Whether that path was declared for that aggregate, which is what lets a value of an
   * unportable type travel.
   *
   * @param workflowAggregateClass The workflow-aggregate class
   * @param path The path as the startup check names it, attribute names joined by dots
   * @return Whether an entry covers it
   */
  public boolean covers(
      final Class<?> workflowAggregateClass,
      final String path) {

    final var declarations = byAggregateClass.get(workflowAggregateClass);
    if (declarations == null) {
      return false;
    }
    final var segments = List.of(path.split("\\."));
    synchronized (declarations) {
      return declarations
          .stream()
          .anyMatch(declaration -> covers(declaration, segments));
    }

  }

  private static boolean covers(
      final Declaration declaration,
      final List<String> segments) {

    if (declaration.everythingBelow()) {
      return (segments.size() > declaration.path().size()) && segments
          .subList(0, declaration.path().size())
          .equals(declaration.path());
    }
    return segments.equals(declaration.path());

  }

  /**
   * Checks the declared paths of that aggregate against the values about to be shared,
   * and writes the substitutes where one was named.
   * <p>
   * Only a path of more than one segment can fail: a top-level attribute is always there,
   * whatever its value is.
   *
   * @param workflowAggregateClass The workflow-aggregate class
   * @param values The values about to be shared, nested maps for nested objects
   * @throws IllegalStateException Where a declared path does not resolve and no
   *           substitute was named, naming the path and the link which is
   *           <code>null</code>
   */
  public void completeOrRefuse(
      final Class<?> workflowAggregateClass,
      final Map<String, Object> values) {

    final var declarations = byAggregateClass.get(workflowAggregateClass);
    if ((declarations == null) || (values == null)) {
      return;
    }
    final List<Declaration> toApply;
    synchronized (declarations) {
      toApply = List.copyOf(declarations);
    }
    for (final var declaration : toApply) {
      if (declaration.path().size() < 2) {
        continue;
      }
      applyOne(workflowAggregateClass, declaration, values);
    }

  }

  private static void applyOne(
      final Class<?> workflowAggregateClass,
      final Declaration declaration,
      final Map<String, Object> values) {

    final var path = declaration.path();
    var level = values;
    for (var index = 0; index < (path.size() - 1); ++index) {
      final var segment = path.get(index);
      final var below = level.get(segment);
      if (below instanceof Map<?, ?> nested) {
        @SuppressWarnings("unchecked")
        final var typed = (Map<String, Object>) nested;
        level = typed;
        continue;
      }
      if (below != null) {
        // the value is there but carries no members: the startup check judged the types,
        // and a second verdict at a sync point would say the same thing later
        return;
      }
      if (declaration.substitute() == null) {
        throw new IllegalStateException(refusal(workflowAggregateClass, declaration, segment));
      }
      level = writeSubstitute(level, path, index, declaration.substitute());
      return;
    }
    if (!level.containsKey(path.getLast()) && (declaration.substitute() != null)) {
      level.put(path.getLast(), valueOf(declaration.substitute()));
    }

  }

  /**
   * Creates the missing levels below the link which is <code>null</code> and writes the
   * declared value at the end of the path.
   */
  private static Map<String, Object> writeSubstitute(
      final Map<String, Object> level,
      final List<String> path,
      final int missingAt,
      final String substitute) {

    var current = level;
    for (var index = missingAt; index < (path.size() - 1); ++index) {
      final Map<String, Object> created = new LinkedHashMap<>();
      current.put(path.get(index), created);
      current = created;
    }
    current.put(path.getLast(), valueOf(substitute));
    return current;

  }

  /**
   * What a declared substitute is worth. A boolean is written as one, because the reason
   * to declare a substitute at all is a gateway reading it; everything else travels as
   * the text it was written as.
   *
   * @param substitute The text the application wrote
   * @return The value to share
   */
  private static Object valueOf(
      final String substitute) {

    if (substitute.equalsIgnoreCase("true")) {
      return Boolean.TRUE;
    }
    if (substitute.equalsIgnoreCase("false")) {
      return Boolean.FALSE;
    }
    return substitute;

  }

  private static String refusal(
      final Class<?> workflowAggregateClass,
      final Declaration declaration,
      final String nullSegment) {

    return """
        The declared value '%s' of the workflow aggregate '%s' cannot be read: '%s' is null, so \
        there is nothing below it. The BPMS would hold no value for that path, and an expression \
        reading it would decide on a value nobody computed.

        There are two ways on. Make the existence of the section a boolean getter of its own on \
        the top level, and share the values of the section only while the section runs: an \
        embedded subprocess has its own scope, and a call activity gives the child process its \
        own variables. Or say what is shared while the path does not resolve:

          %s=<value>

        Declared instead of guessed. Writing false on its own would be the worst answer, because \
        nobody can tell it from a false somebody computed."""
        .formatted(
            declaration.describePath(),
            workflowAggregateClass.getName(),
            nullSegment,
            declaration.describePath());

  }

}
