package io.vanillabp.integration.adapter.migration.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every key below <code>vanillabp</code> which some part of the application reads, as a tree
 * of positions. The core, each adapter and each extension add the keys they read. The platform
 * builds the tree from what they bind, and nobody else has to know their keys.
 * <p>
 * A position is one step of a key. It can hold four things, and one position may hold
 * several of them, because two parts may read the same step in different ways:
 * <ul>
 * <li>named positions below it, such as <code>tenant-id</code> below an adapter section,</li>
 * <li>one position for any key, which is the id of a map: an adapter id, a workflow module id,
 * a BPMN process id, a task id,</li>
 * <li>a single value, which may also be a list of values,</li>
 * <li>anything below it. That is a map of plain texts, whose keys are the business of the
 * part which reads them. The settings of an extension are such a map.</li>
 * </ul>
 * Names are compared the way both platforms bind them: case, dashes and underscores do not
 * matter. So <code>tenantId</code>, <code>tenant-id</code> and the <code>TENANTID</code> of an
 * environment variable are one name.
 * <p>
 * {@link UnreadKeys} uses the tree to say where a key nobody read is read instead.
 */
public final class ReadableKeys {

  private final Position root = new Position(MigrationAdapterProperties.PREFIX, null);

  /**
   * An empty tree. The platform adds the keys of each part which binds below
   * <code>vanillabp</code>.
   */
  public ReadableKeys() {

  }

  /**
   * The position of <code>vanillabp</code> itself.
   *
   * @return The root of the tree
   */
  public Position root() {

    return root;

  }

  /**
   * The name of a step in the form in which two spellings of the same key are equal.
   *
   * @param name A step as written
   * @return The name in lower case, without dashes, underscores and anything else which is
   *         not a letter or a digit
   */
  static String comparable(
      final String name) {

    final var result = new StringBuilder(name.length());
    for (final var character : name.toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(character)) {
        result.append(character);
      }
    }
    return result.toString();

  }

  /**
   * One step of the keys which are read.
   */
  public static final class Position {

    /** The step as the part which reads it spells it, or "*" for the id of a map. */
    private final String name;

    private final Position parent;

    private final Map<String, Position> named = new TreeMap<>();

    private Position anyKey;

    private boolean value;

    private boolean anythingBelow;

    private Position(
        final String name,
        final Position parent) {

      this.name = name;
      this.parent = parent;

    }

    /**
     * The named position below this one. A part which reads the same step as another one
     * gets the same position.
     *
     * @param stepName The step, as the part which reads it spells it
     * @return The position below, created if no part read it so far
     */
    public Position named(
        final String stepName) {

      return named.computeIfAbsent(comparable(stepName), ignored -> new Position(stepName, this));

    }

    /**
     * The position below this one which stands for any key. This position is a map.
     *
     * @return The position below, created if no part read it so far
     */
    public Position anyKey() {

      if (anyKey == null) {
        anyKey = new Position("*", this);
      }
      return anyKey;

    }

    /**
     * Says that a part reads this position as a single value or as a list of values.
     */
    public void readAsValue() {

      value = true;

    }

    /**
     * Says that a part reads everything below this position, whatever the keys are.
     */
    public void readWithAnythingBelow() {

      anythingBelow = true;

    }

    /**
     * Whether some part reads a single value or a list here.
     *
     * @return <code>true</code> for a value
     */
    boolean isValue() {

      return value;

    }

    /**
     * Whether some part reads everything below here.
     *
     * @return <code>true</code> where any key below is read
     */
    boolean readsAnythingBelow() {

      return anythingBelow;

    }

    /**
     * Whether this position stands for the id of a map.
     *
     * @return <code>true</code> for an id
     */
    boolean isAnyKey() {

      return (parent != null) && (parent.anyKey == this);

    }

    /**
     * The step as the part which reads it spells it.
     *
     * @return The name, "*" for an id
     */
    String name() {

      return name;

    }

    /**
     * The position above.
     *
     * @return The parent, <code>null</code> for <code>vanillabp</code>
     */
    Position parent() {

      return parent;

    }

    /**
     * The named position below, if a part reads it.
     *
     * @param stepName The step, in any spelling
     * @return The position, or <code>null</code>
     */
    Position namedOrNull(
        final String stepName) {

      return named.get(comparable(stepName));

    }

    /**
     * The position for any key below, if this is a map.
     *
     * @return The position, or <code>null</code>
     */
    Position anyKeyOrNull() {

      return anyKey;

    }

    /**
     * The named positions below, sorted by their names.
     *
     * @return The positions, never <code>null</code>
     */
    Collection<Position> namedBelow() {

      return named.values();

    }

    /**
     * Every position below this one, this one first.
     *
     * @return The positions, never <code>null</code>
     */
    List<Position> withEverythingBelow() {

      final var result = new ArrayList<Position>();
      collect(result);
      return result;

    }

    private void collect(
        final List<Position> result) {

      result.add(this);
      named
          .values()
          .forEach(position -> position.collect(result));
      if (anyKey != null) {
        anyKey.collect(result);
      }

    }

    /**
     * The steps from <code>vanillabp</code> down to this position, <code>vanillabp</code> first.
     *
     * @return The positions, never empty
     */
    List<Position> path() {

      final var result = new ArrayList<Position>();
      for (var position = this; position != null; position = position.parent) {
        result.addFirst(position);
      }
      return result;

    }

  }

}
