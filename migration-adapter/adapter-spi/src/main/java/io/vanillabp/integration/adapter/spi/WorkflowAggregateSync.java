package io.vanillabp.integration.adapter.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a workflow aggregate into the values shared with the BPMS. It follows
 * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's own default
 * ({@link AggregateSyncMode}). The core implements it ONCE, because the model does not
 * depend on a BPMS, and the platform integration hands it to every adapter.
 * <p>
 * Every adapter pushes the values as process variables at every sync point (instance
 * creation, task completion, message correlation, user-task completion). This includes
 * an embedded BPMS. An engine evaluates its models against its own variables. So a model
 * which read anything else would work on one BPMS and fail on the next. Camunda 7 once
 * read the aggregate live, and it does not any more.
 * <p>
 * <b>The workflow aggregate's ID is never part of these values.</b> How a BPMS
 * identifies the workflow is up to the adapter (Camunda 7: the business key;
 * Camunda 8 / Process-Engine-API: a process variable named after the aggregate's
 * ID property, see {@code AggregatePersistenceAware#getAggregateIdName()}). That
 * variable is technical and is ALWAYS set, whatever the sync model says. Otherwise an
 * aggregate annotated {@code @NoSyncWithBPMS} could not be addressed. Tests hold both
 * halves. The first is held by {@code WorkflowTaskRegistryTest} of the migration
 * adapter. The second is held by {@code PeaSharedValuesTest} and
 * {@code Camunda8SharedValuesTest} of the adapters which store the ID in a variable.
 * <p>
 * What decides which values of an aggregate leave for the BPMS, and why the variable named after
 * the id attribute travels regardless, is decision 10 in the repository's DECISIONS.md.
 */
public interface WorkflowAggregateSync {

  /**
   * The values of the given workflow aggregate shared with the BPMS.
   *
   * @param workflowAggregate The workflow aggregate (may be <code>null</code>)
   * @param adapterDefault The adapter's default for aggregates carrying no
   *          annotation of their own
   * @return The values by attribute name. Maybe empty, never <code>null</code>.
   *         Nested objects are maps, collections are lists
   */
  Map<String, Object> syncedValues(
      Object workflowAggregate,
      AggregateSyncMode adapterDefault);

  /**
   * Checks the sync model of one workflow-aggregate class AND of every type reachable
   * from its attributes. As long as an aggregate carries no annotation at all, the
   * adapter decides. But as soon as the application annotates something, the intent has
   * to be clear. Attributes annotated BOTH ways cannot be understood if the class does
   * not state its own mode.
   * <p>
   * It also refuses a shared attribute whose type reaches the BPMS as a text without any
   * real value. A {@code java.util.Calendar} is such a type. It travels as the debug form
   * of its implementation, which no model reads and no operator understands.
   * <p>
   * The PLATFORM INTEGRATION calls it at startup, once per registered workflow-aggregate
   * class. Adapters do not call it. A defect must stop the start, and not show up at the
   * first sync point. Some types are reachable only at runtime (e.g. a subclass assigned
   * to an attribute of a supertype, or types nested deeper than the model follows). They
   * still fail with the same message when they are shared for the first time.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @throws IllegalStateException Naming every defect found: an ambiguous class with
   *           its conflicting attributes, an attribute the BPMS may not be given, and
   *           the fix for each of them
   */
  void validateSyncModel(
      Class<?> workflowAggregateClass);

  /**
   * Whether the given name is a readable attribute of the workflow-aggregate class. It is
   * asked about a name which a BPMN model reads. So an adapter can tell "the application
   * clearly meant its aggregate" from "this is a variable of the model".
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param propertyName The name read by the model
   * @return Whether the class has such a readable attribute
   */
  default boolean isAggregateProperty(
      final Class<?> workflowAggregateClass,
      final String propertyName) {

    return false;

  }

  /**
   * The attributes a workflow aggregate hands to the BPMS if its sync model holds NOTHING
   * back. That means no {@code @NoSyncWithBPMS} anywhere in the graph of types reachable
   * from it, and no type in that graph which shares only the attributes it names.
   * <p>
   * The answer is empty wherever something IS held back. That is the ordinary case of an
   * application which said what its models need. It is also empty if the aggregate has
   * nothing but its ID. That attribute reaches the BPMS whatever the sync model says (see
   * the type comment). So an aggregate which has only its ID gives nothing away by sharing
   * everything.
   * <p>
   * The platform integration asks this at startup, for every workflow it registers. It
   * refuses to start if the answer is not empty and the workflow did not allow it. An
   * application which does nothing shares everything. On a remote BPMS, that means every
   * attribute leaves the application.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param aggregateIdAttribute The name of the aggregate's ID attribute, or
   *          <code>null</code> where the persistence does not name one
   * @return The attributes of the aggregate itself, in the order they are shared in.
   *         Empty if anything at all is held back. The default implementation answers
   *         nothing
   */
  default List<String> everythingSharedWithBpms(
      final Class<?> workflowAggregateClass,
      final String aggregateIdAttribute) {

    return List.of();

  }

  /**
   * Whether that attribute is SHARED with the BPMS. This decides whether an expression
   * which reads it finds a value, or always <code>null</code>.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param propertyName The attribute's name
   * @param adapterDefault The adapter's default for aggregates carrying no annotation
   *          of their own
   * @return Whether the attribute is shared
   */
  default boolean isSharedWithBpms(
      final Class<?> workflowAggregateClass,
      final String propertyName,
      final AggregateSyncMode adapterDefault) {

    return true;

  }

  /**
   * What an expression which reads a PATH of attributes finds in the BPMS. For example
   * <code>order.customer.vip</code>, not just the single name {@link #isSharedWithBpms}
   * answers about.
   * <p>
   * The values a BPMS holds are a nested structure of plain types, so a model may go into
   * them. What the single-name question answers about the first segment is true of every
   * segment below it. If the sync model keeps back one segment, the whole expression reads
   * <code>null</code>. This walks the DECLARED types of the segments and says where the
   * path stops finding anything.
   * <p>
   * <b>It does not answer wherever the declared type cannot decide.</b> That is a
   * {@link Map}, a raw or wildcard collection, an interface or an abstract type, and
   * anything below the nesting limit at which the values themselves are cut. Then the
   * answer is {@link PathVerdict.Kind#UNDECIDABLE}, because a warning about a model which
   * works is worse than a missing one. The walk does not judge method calls either. What
   * <code>order.total.doubleValue()</code> resolves to depends on the runtime class which
   * the serialization of the BPMS produced. A declared type cannot tell that.
   * <p>
   * Why the core answers this and where it stays silent is decision 37 in the repository's
   * DECISIONS.md.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param path The segments of the path, the first one read against the aggregate
   *          itself (may be <code>null</code> or empty)
   * @param adapterDefault The adapter's default for aggregates carrying no annotation
   *          of their own
   * @return What the path finds, never <code>null</code>. The default implementation
   *         decides nothing
   */
  default PathVerdict whatAPathFinds(
      final Class<?> workflowAggregateClass,
      final List<String> path,
      final AggregateSyncMode adapterDefault) {

    return PathVerdict.undecidable();

  }

  /**
   * The DECLARED type a path of attributes ends at, asked about the same path
   * {@link #whatAPathFinds} answers about.
   * <p>
   * Sometimes an adapter has to know what a shared value IS, not only that it is shared. A
   * BPMS stores what it gets, and its storage format may not bring every Java type back
   * unchanged. Only the adapter knows what its BPMS can store. So the core answers the
   * type and judges nothing about it.
   * <p>
   * There is an answer only if the path reaches a value the BPMS holds. Wherever
   * {@link #whatAPathFinds} stops before such a value, there is nothing to answer. A value
   * the sync model keeps back never reaches the BPMS, so nothing of it has to survive the
   * way there and back. The same holds wherever the declared types cannot decide. So an
   * empty answer means "this cannot be judged". It never means "this was checked and found
   * broken", just as the path question says it.
   * <p>
   * The answer is the type the attribute declares. The BPMS may hand back another type,
   * and that is exactly what an adapter wants to find out with this.
   * <p>
   * How the walk reads a path, and where it stays silent, is decision 37 in the
   * repository's DECISIONS.md.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be
   *          <code>null</code>)
   * @param path The segments of the path, the first one read against the aggregate
   *          itself (may be <code>null</code> or empty)
   * @param adapterDefault The adapter's default for aggregates carrying no annotation
   *          of their own
   * @return The declared type the path ends at, empty wherever the path finds no value
   *         the BPMS holds. The default implementation answers nothing
   */
  default Optional<Class<?>> whatTypeAPathEndsAt(
      final Class<?> workflowAggregateClass,
      final List<String> path,
      final AggregateSyncMode adapterDefault) {

    return Optional.empty();

  }

  /**
   * What a walk along a path of attributes found, and at which segment.
   *
   * @param kind What the path finds
   * @param segment The segment the path stops at, <code>null</code> if it does not
   *          stop
   * @param segmentIndex The position of that segment in the path, counted from zero, and
   *          <code>-1</code> if the path does not stop
   * @param segmentOwner The simple name of the declared type the segment was read
   *          against, so a message can say whose attribute is missing.
   *          <code>null</code> if the path does not stop
   */
  record PathVerdict(
                     Kind kind,
                     String segment,
                     int segmentIndex,
                     String segmentOwner) {

    /**
     * What a path finds.
     */
    public enum Kind {

      /**
       * Every segment is an attribute the BPMS is given, so the expression reads a
       * value.
       */
      SHARED_VALUE,

      /**
       * The declared type the segment was read against has no readable attribute of
       * that name, so the BPMS holds no such member.
       */
      NO_SUCH_ATTRIBUTE,

      /**
       * The segment IS a readable attribute and the sync model keeps it back.
       */
      NOT_SHARED,

      /**
       * The segment before this one travels as a single value, a number or a text. So
       * there is nothing below it to read. An enum arrives as its name, so it counts as a
       * text.
       */
      NOTHING_BELOW,

      /**
       * The declared types cannot decide, so nothing is claimed.
       */
      UNDECIDABLE

    }

    /**
     * Whether this verdict is worth reporting. The three kinds which stop at a segment
     * are. {@link Kind#UNDECIDABLE} is not, because the declared types said nothing there,
     * and a warning about a model which works is worse than no warning at all.
     *
     * @return Whether the path stops before a value the BPMS holds. A check reports
     *         that
     */
    public boolean pathIsCut() {

      return (kind == Kind.NO_SUCH_ATTRIBUTE) || (kind == Kind.NOT_SHARED) || (kind == Kind.NOTHING_BELOW);

    }

    /**
     * The verdict of a path which does not stop, so it names no segment.
     *
     * @return The path reaches a value the BPMS holds
     */
    public static PathVerdict aSharedValue() {

      return new PathVerdict(Kind.SHARED_VALUE, null, -1, null);

    }

    /**
     * The verdict wherever the declared types cannot decide, for example for a map or an
     * interface. It names no segment either. Nothing was found and nothing is claimed.
     *
     * @return The declared types cannot decide
     */
    public static PathVerdict undecidable() {

      return new PathVerdict(Kind.UNDECIDABLE, null, -1, null);

    }

    /**
     * The verdict of a path whose segment is no attribute of the type it was read against.
     * A message needs the three values to point at the wrong segment.
     *
     * @param segment The segment which is no attribute
     * @param segmentIndex Its position in the path
     * @param segmentOwner The simple name of the type it was read against
     * @return The path stops because that type has no such attribute
     */
    public static PathVerdict noSuchAttribute(
        final String segment,
        final int segmentIndex,
        final String segmentOwner) {

      return new PathVerdict(Kind.NO_SUCH_ATTRIBUTE, segment, segmentIndex, segmentOwner);

    }

    /**
     * The verdict of a path whose segment IS an attribute, but the sync model keeps it
     * back. So the expression reads <code>null</code>, although the aggregate holds a value.
     *
     * @param segment The unshared attribute
     * @param segmentIndex Its position in the path
     * @param segmentOwner The simple name of the type it was read against
     * @return The path stops because that attribute is not shared
     */
    public static PathVerdict notShared(
        final String segment,
        final int segmentIndex,
        final String segmentOwner) {

      return new PathVerdict(Kind.NOT_SHARED, segment, segmentIndex, segmentOwner);

    }

    /**
     * The verdict of a path which reads into a value without members. The segment above it
     * travels as a single number or text, so there is nothing below to read.
     *
     * @param segment The segment which finds nothing
     * @param segmentIndex Its position in the path
     * @param segmentOwner The simple name of the type it was read against. That type
     *          travels as a single value
     * @return The path stops because the value above it carries no members
     */
    public static PathVerdict nothingBelow(
        final String segment,
        final int segmentIndex,
        final String segmentOwner) {

      return new PathVerdict(Kind.NOTHING_BELOW, segment, segmentIndex, segmentOwner);

    }

  }

}
