package io.vanillabp.integration.adapter.migration.sync;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.adapter.migration.values.DeclaredAggregateValues;
import io.vanillabp.integration.adapter.migration.values.TextValueTypes;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import lombok.extern.slf4j.Slf4j;

/**
 * THE sync model: which attributes of a workflow aggregate are shared with the BPMS, and
 * what their values look like. It does not depend on any BPMS on purpose. The adapters
 * only decide their {@link AggregateSyncMode} default and what to do with the result.
 *
 * <h2>Inheritance: no surprises</h2>
 *
 * Every attribute <b>inherits the behavior of its owner</b> until the application says
 * otherwise:
 *
 * <ol>
 * <li>The outermost default is the ADAPTER's ({@link AggregateSyncMode}). It applies as
 * long as the aggregate has NO annotation at all.</li>
 * <li>The mode of a class is its own annotation ({@code @SyncWithBPMS} or
 * {@code @NoSyncWithBPMS}). If it has none, the mode is DERIVED from its attributes (see
 * below). The mode of the class overrides the adapter's default for all its
 * attributes.</li>
 * <li>An annotation on an attribute (field or getter) overrides it for that attribute AND
 * everything below it. The attributes of a nested object and the elements of a collection
 * inherit from the attribute they belong to.</li>
 * <li>A nested TYPE decides its own mode the same way: its own annotation, or else derived
 * from its attributes. This overrides what its members inherited through the attribute
 * which holds it. So a DTO may narrow what it exposes wherever it is used.</li>
 * </ol>
 *
 * So a DTO without annotations of its own, and without any to derive from, behaves exactly
 * like the attribute which holds it. It is never "fully shared" on its own account.
 *
 * <h2>Deriving the mode of a class from its attributes</h2>
 *
 * As soon as the FIRST sync annotation appears anywhere on a type, the application has
 * taken control. The adapter's default no longer applies to it. If only ATTRIBUTES are
 * annotated, the mode of the class is the OPPOSITE of what they say. That is what a
 * developer means who writes only one of the two annotations:
 *
 * <ul>
 * <li>Attributes marked {@code @SyncWithBPMS} mean {@code @NoSyncWithBPMS} on the class
 * (opt-in: share exactly the named attributes).</li>
 * <li>Attributes marked {@code @NoSyncWithBPMS} mean {@code @SyncWithBPMS} on the class
 * (opt-out: share everything except the named attributes).</li>
 * <li>BOTH kinds among the attributes of a class without its own annotation is AMBIGUOUS.
 * Nothing can tell which of them is the exception. This is a defect, reported with a
 * guiding message. {@link #validateSyncModel(Class)} raises it at STARTUP for every
 * registered workflow-aggregate class, and for every nested type reachable from it. So it
 * never shows up first at a sync point.</li>
 * </ul>
 *
 * <h2>Which attributes exist</h2>
 *
 * The readable JavaBean properties, which are the public getters including {@code isX()}.
 * The getters with a telling name which the wiki recommends are attributes like any other.
 * The annotation of a getter wins over the annotation of a field with the same name.
 * {@code getClass} and getters with arguments are ignored, and so are static and synthetic
 * members.
 *
 * <h2>Which values are produced</h2>
 *
 * <ul>
 * <li>{@code null}, primitives and their wrappers, {@link String}, {@link Number},
 * {@link Boolean} and {@link Character} are taken as they are.</li>
 * <li>Enums become their {@link Enum#name()}. A value type of the JDK becomes the text
 * which {@link TextValueTypes} names for it. For most of them that is their own
 * {@code toString()}.</li>
 * <li>Collections and arrays become {@link List}s of converted elements. Maps become maps
 * whose keys are converted to strings.</li>
 * <li>Any other object becomes a {@link Map} of its shared attributes.</li>
 * </ul>
 *
 * The result is made of plain JDK types on purpose. The variable serialization of every
 * BPMS, and the FEEL of Camunda 8, can handle them.
 *
 * <h2>Cycles</h2>
 *
 * Relations in both directions are the NORMAL case in an entity model. An order holds its
 * items, and every item points back to its order. So an object which is already on the
 * current path is not expanded again, because that would repeat the whole subtree once per
 * level. It is replaced by a reference to it: its type and identity. This is deliberately
 * not {@code toString()}, which recurses on exactly these graphs. Apart from that, nesting
 * is followed at most {@link #MAX_DEPTH} levels deep.
 * <p>
 * Decision 10 in the repository's DECISIONS.md says why the sync model exists at all, and
 * why the aggregate-id variable is not part of it.
 */
@Slf4j
public class AggregateSyncSupport implements WorkflowAggregateSync {

  /**
   * How deep nested objects are followed. This guards against cyclic object graphs. A
   * workflow aggregate is not a graph database.
   */
  public static final int MAX_DEPTH = 10;

  /**
   * The readable properties per class, resolved once. Reflection is not free, and sync
   * points are hot paths.
   */
  private final Map<Class<?>, List<Property>> propertiesByClass = new ConcurrentHashMap<>();

  /**
   * The base mode per class: its own annotation, or the mode derived from its attributes.
   * {@link Optional#empty()} means "inherits" (see the class comment).
   */
  private final Map<Class<?>, Optional<Boolean>> baseModeByClass = new ConcurrentHashMap<>();

  /**
   * One readable attribute of a class: its name, how to read it, and whether the
   * application annotated it.
   */
  private record Property(
                          String name,
                          Method getter,
                          Boolean synced) {

    Object read(
        final Object owner) {

      try {
        return getter.invoke(owner);
      } catch (final Exception e) {
        throw new IllegalStateException(
            "Could not read the attribute '%s' of '%s' while collecting the values shared with the BPMS!"
                .formatted(name, owner
                    .getClass()
                    .getName()), e);
      }

    }

  }

  /**
   * Where a finding about an aggregate class goes, so that the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * Built by the platform integration, once per application.
   * <p>
   * Nothing is read here. What an aggregate class shares is derived when the class first
   * turns up, and kept after that. A sync point is a hot path, and reflection is not
   * free.
   *
   * @param findings Where a finding about an aggregate class is left
   */
  public AggregateSyncSupport(
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this.findings = findings;

  }

  /**
   * What the application declared about the values of its aggregates. It is read from the
   * configuration while the application starts, and applied at every sync point. It is
   * never <code>null</code>, and empty where nothing was declared.
   */
  private final DeclaredAggregateValues declaredValues = new DeclaredAggregateValues();

  /**
   * The declarations of the application, so that the startup check can fill them and the
   * sync points can read them. There is one per application. It belongs to the sync model,
   * because that is where the values are built.
   *
   * @return The declarations, never <code>null</code>
   */
  public DeclaredAggregateValues getDeclaredValues() {

    return declaredValues;

  }

  @Override
  public Map<String, Object> syncedValues(
      final Object workflowAggregate,
      final AggregateSyncMode adapterDefault) {

    if (workflowAggregate == null) {
      return Map.of();
    }
    final var declared = baseModeOf(workflowAggregate.getClass());
    final var effective = declared != null
        ? declared
        : adapterDefault == AggregateSyncMode.FULL;
    final var values = valuesOf(
        workflowAggregate,
        effective,
        0,
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    // a value which the application declared has to be there. The declaration may say what
    // to share while the object which holds it is still null
    declaredValues.completeOrRefuse(workflowAggregate.getClass(), values);
    return values;

  }

  @Override
  public boolean isAggregateProperty(
      final Class<?> workflowAggregateClass,
      final String propertyName) {

    if ((workflowAggregateClass == null) || (propertyName == null)) {
      return false;
    }
    if (propertiesOf(workflowAggregateClass)
        .stream()
        .anyMatch(property -> property.name().equals(propertyName))) {
      return true;
    }
    // MIGRATION (to be removed together with the fallback of the Camunda 7 EL resolver):
    // what VanillaBP 1 resolved and the sync model does not is an attribute of the
    // aggregate as well. It is the case which has to be named loudest, because a model which
    // relies on such a name breaks silently. Neither of the two kinds can ever be shared, so
    // isSharedWithBpms answers false and the check reports them: a FIELD without a getter,
    // and an isX() method which returns something other than boolean (version 1 read those,
    // the JavaBean rule does not).
    return (findField(workflowAggregateClass, propertyName) != null) || (version1OnlyGetter(workflowAggregateClass,
        propertyName) != null);

  }

  /**
   * The <code>isX()</code> method of that name whose return type is NOT boolean. VanillaBP
   * 1 resolved it, and {@link #propertyNameOf(Method)} rejects it.
   *
   * @param workflowAggregateClass The workflow-aggregate class
   * @param propertyName The attribute's name
   * @return The method or <code>null</code>
   */
  private static Method version1OnlyGetter(
      final Class<?> workflowAggregateClass,
      final String propertyName) {

    if (propertyName.isEmpty()) {
      return null;
    }
    final var methodName = "is"
        + Character.toUpperCase(propertyName.charAt(0))
        + propertyName.substring(1);
    try {
      final var method = workflowAggregateClass.getMethod(methodName);
      return (method.getReturnType() == boolean.class) || (method.getReturnType() == Boolean.class)
          ? null
          : method;
    } catch (final NoSuchMethodException notThere) {
      return null;
    }

  }

  @Override
  public List<String> everythingSharedWithBpms(
      final Class<?> workflowAggregateClass,
      final String aggregateIdAttribute) {

    if (workflowAggregateClass == null) {
      return List.of();
    }
    // FULL is the default of every adapter. So an aggregate which annotates nothing shares
    // as a whole. validateSyncModel starts its walk from the same point
    if (!nothingIsHeldBack(workflowAggregateClass, 0, new java.util.HashSet<>())) {
      return List.of();
    }
    // nothing is held back, so every attribute travels. The ID travels anyway (see the
    // SPI). That is why an aggregate which has only the ID answers an empty list here
    return propertiesOf(workflowAggregateClass)
        .stream()
        .map(Property::name)
        .filter(name -> !name.equals(aggregateIdAttribute))
        .toList();

  }

  /**
   * EVERY value of that aggregate class which reaches the BPMS. Each is named by the path
   * which a BPMN expression reads it under, and by the type the attribute declares.
   * <p>
   * The walk follows the sync model the same way as {@link #syncedValues}. It stops where a
   * value has no members of its own. A number, a text, a boolean, an enum and the JDK types
   * which travel as text are leaves. The walk goes into everything else. A collection is
   * walked as its element type, because a model which navigates into it reads an element.
   * <p>
   * Where the declared types decide nothing, the walk reports the attribute itself as the
   * leaf. An {@code Object}, a {@link Map}, a raw collection, an interface and an abstract
   * type all hold whatever they were given. The source does not tell what travels below
   * them.
   *
   * @param workflowAggregateClass The workflow-aggregate class (may be <code>null</code>)
   * @param adapterDefault The adapter's default for aggregates without an annotation of
   *          their own
   * @return One entry per value which reaches the BPMS, never <code>null</code>
   */
  public List<SharedValue> valuesSharedWithBpms(
      final Class<?> workflowAggregateClass,
      final AggregateSyncMode adapterDefault) {

    if (workflowAggregateClass == null) {
      return List.of();
    }
    final var declared = baseModeOf(workflowAggregateClass);
    final var inherited = declared != null
        ? declared
        : adapterDefault == AggregateSyncMode.FULL;
    final var values = new LinkedList<SharedValue>();
    collectSharedValues(
        workflowAggregateClass,
        inherited,
        "",
        0,
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()),
        values);
    return List.copyOf(values);

  }

  /**
   * One value of a workflow aggregate which reaches the BPMS.
   *
   * @param path The path a BPMN expression reads it under, attribute names joined by dots
   * @param declaredType The type the attribute declares
   */
  public record SharedValue(
                            String path,
                            Class<?> declaredType) {
  }

  /**
   * Collects the leaves below one type. It passes the inherited mode down exactly the way
   * {@link #walk} does.
   *
   * @param owner The type whose attributes are read
   * @param inherited What an attribute without an annotation of its own does
   * @param prefix The path walked so far, empty at the aggregate itself
   * @param depth The current nesting depth
   * @param onThePath The types above this one, so a cyclic type graph ends
   * @param values Collects the leaves
   */
  private void collectSharedValues(
      final Class<?> owner,
      final boolean inherited,
      final String prefix,
      final int depth,
      final java.util.Set<Class<?>> onThePath,
      final List<SharedValue> values) {

    if (depth >= MAX_DEPTH) {
      // the values themselves are cut here (see convert), so nothing below travels
      return;
    }
    if (!onThePath.add(owner)) {
      return;
    }
    try {
      for (final var property : propertiesOf(owner)) {
        final var synced = property.synced() != null
            ? property.synced()
            : inherited;
        if (!synced) {
          continue;
        }
        final var path = prefix.isEmpty()
            ? property.name()
            : "%s.%s".formatted(prefix, property.name());
        final var declaredType = property.getter().getReturnType();
        if (travelsAsASingleValue(declaredType)) {
          values.add(new SharedValue(path, declaredType));
          continue;
        }
        final var behind = typeBehind(property.getter().getGenericReturnType());
        if ((behind == null) || travelsAsASingleValue(behind) || holdsWhateverItWasGiven(behind) || behind
            .isInterface() || Modifier.isAbstract(behind.getModifiers())) {
          // either the elements of a collection are a value of their own, or the declared
          // type decides nothing at all. Both end the walk here
          values
              .add(new SharedValue(path, behind != null
                  ? behind
                  : declaredType));
          continue;
        }
        final var ofType = baseModeOf(behind);
        collectSharedValues(
            behind,
            ofType != null
                ? ofType
                : synced,
            path,
            depth + 1,
            onThePath,
            values);
      }
    } finally {
      onThePath.remove(owner);
    }

  }

  /**
   * Whether the sync model of that type, and of every type reachable from its attributes,
   * keeps not a single attribute from the BPMS.
   *
   * @param clazz The type, reached on a path which shares everything above it
   * @param depth The current nesting depth
   * @param visited The types already walked (cyclic type graphs). A second look adds
   *          nothing, because the first one was on a path which shares everything too
   * @return Whether everything of it travels
   */
  private boolean nothingIsHeldBack(
      final Class<?> clazz,
      final int depth,
      final java.util.Set<Class<?>> visited) {

    Boolean ownMode;
    try {
      ownMode = baseModeOf(clazz);
    } catch (final IllegalStateException ambiguousSyncModel) {
      // an aggregate whose sync model cannot be interpreted ends the boot in
      // validateSyncModel, and a second report of the same defect helps nobody
      return false;
    }
    if (Boolean.FALSE.equals(ownMode)) {
      // the type shares only the attributes it names, so something stays behind
      return false;
    }
    if (!visited.add(clazz)) {
      return true;
    }
    if (depth >= MAX_DEPTH) {
      // the documented limit cuts the VALUES, not the sync model. This question is about
      // what the application annotated
      return true;
    }
    for (final var property : propertiesOf(clazz)) {
      if (Boolean.FALSE.equals(property.synced())) {
        return false;
      }
      final var everythingBelowTravels = attributeTypes(property.getter().getGenericReturnType())
          .distinct()
          .allMatch(attributeType -> nothingIsHeldBack(attributeType, depth + 1, visited));
      if (!everythingBelowTravels) {
        return false;
      }
    }
    return true;

  }

  @Override
  public boolean isSharedWithBpms(
      final Class<?> workflowAggregateClass,
      final String propertyName,
      final AggregateSyncMode adapterDefault) {

    if ((workflowAggregateClass == null) || (propertyName == null)) {
      return false;
    }
    final var declared = baseModeOf(workflowAggregateClass);
    final var inherited = declared != null
        ? declared
        : adapterDefault == AggregateSyncMode.FULL;
    return propertiesOf(workflowAggregateClass)
        .stream()
        .filter(property -> property.name().equals(propertyName))
        .findFirst()
        .map(property -> property.synced() != null
            ? property.synced()
            : inherited)
        .orElse(false);

  }

  @Override
  public PathVerdict whatAPathFinds(
      final Class<?> workflowAggregateClass,
      final List<String> path,
      final AggregateSyncMode adapterDefault) {

    return walked(workflowAggregateClass, path, adapterDefault).verdict();

  }

  @Override
  public Optional<Class<?>> whatTypeAPathEndsAt(
      final Class<?> workflowAggregateClass,
      final List<String> path,
      final AggregateSyncMode adapterDefault) {

    // the same walk answers both questions. So the type and the verdict can never disagree
    // about a path: there is a type exactly where the walk reached a value
    return Optional.ofNullable(walked(workflowAggregateClass, path, adapterDefault).declaredType());

  }

  /**
   * Walks the path once. It checks the arguments and the sync model which both questions
   * share.
   *
   * @param workflowAggregateClass The type the first segment is read against
   * @param path The segments
   * @param adapterDefault The adapter's default
   * @return What the walk found
   */
  private WalkResult walked(
      final Class<?> workflowAggregateClass,
      final List<String> path,
      final AggregateSyncMode adapterDefault) {

    if ((workflowAggregateClass == null) || (path == null) || path.isEmpty() || path
        .stream()
        .anyMatch(segment -> (segment == null) || segment.isBlank())) {
      return WalkResult.stoppedShort(PathVerdict.undecidable());
    }
    try {
      return walk(workflowAggregateClass, path, adapterDefault);
    } catch (final IllegalStateException ambiguousSyncModel) {
      // an aggregate whose sync model cannot be interpreted is refused while the
      // application boots (validateSyncModel). So nothing can act on a second report here,
      // and a check about expressions must not be the thing which fails
      log.debug(
          "Could not decide what the path '{}' of '{}' finds: {}",
          String.join(".", path),
          workflowAggregateClass.getName(),
          ambiguousSyncModel.getMessage());
      return WalkResult.stoppedShort(PathVerdict.undecidable());
    }

  }

  /**
   * What one walk answered: what the path finds, and the declared type it ends at. That
   * type exists only where the walk reached a value which the BPMS holds.
   *
   * @param verdict What the path finds
   * @param declaredType The type of the last segment, <code>null</code> wherever the
   *          walk found no value
   */
  private record WalkResult(
                            PathVerdict verdict,
                            Class<?> declaredType) {

    /**
     * @param verdict Why the path finds no value
     * @return A walk with no type to report
     */
    static WalkResult stoppedShort(
        final PathVerdict verdict) {

      return new WalkResult(verdict, null);

    }

  }

  /**
   * Follows the path along the DECLARED types of its segments. It passes the inherited mode
   * down exactly the way {@link #valuesOf} and {@link #convert} do. The own mode of a nested
   * type overrides what the attribute which holds it passed down.
   *
   * @param workflowAggregateClass The type the first segment is read against
   * @param path The segments
   * @param adapterDefault The adapter's default
   * @return What the path finds, and the type it ends at
   */
  private WalkResult walk(
      final Class<?> workflowAggregateClass,
      final List<String> path,
      final AggregateSyncMode adapterDefault) {

    var owner = workflowAggregateClass;
    final var declared = baseModeOf(owner);
    var inherited = declared != null
        ? declared
        : adapterDefault == AggregateSyncMode.FULL;
    for (var index = 0; index < path.size(); ++index) {
      final var segment = path.get(index);
      if (index >= MAX_DEPTH) {
        // the values themselves are cut here (see convert). So whatever the declared types
        // say about this segment says nothing about what the BPMS holds
        return WalkResult.stoppedShort(PathVerdict.undecidable());
      }
      if (holdsWhateverItWasGiven(owner)) {
        return WalkResult.stoppedShort(PathVerdict.undecidable());
      }
      if (travelsAsASingleValue(owner)) {
        // asked BEFORE the abstract types are refused. 'Number' and 'CharSequence' are
        // abstract, but they still say everything about what reaches the BPMS
        return WalkResult.stoppedShort(PathVerdict.nothingBelow(segment, index, owner.getSimpleName()));
      }
      if (owner.isInterface() || Modifier.isAbstract(owner.getModifiers())) {
        // the implementation which the application assigned decides, and it may well have
        // the attribute which this type does not have
        return WalkResult.stoppedShort(PathVerdict.undecidable());
      }
      final var properties = propertiesOf(owner);
      if (properties.isEmpty()) {
        // no readable attribute at all, so convert shares the text of the value
        return WalkResult.stoppedShort(PathVerdict.nothingBelow(segment, index, owner.getSimpleName()));
      }
      final var property = properties
          .stream()
          .filter(candidate -> candidate.name().equals(segment))
          .findFirst();
      if (property.isEmpty()) {
        // what VanillaBP 1 resolved and the sync model does not IS an attribute, and one
        // which can never be shared: a field without a getter, and an isX() which returns
        // something other than boolean (see isAggregateProperty)
        return WalkResult
            .stoppedShort(
                (findField(owner, segment) != null) || (version1OnlyGetter(owner, segment) != null)
                    ? PathVerdict.notShared(segment, index, owner.getSimpleName())
                    : PathVerdict.noSuchAttribute(segment, index, owner.getSimpleName()));
      }
      final var synced = property.get().synced() != null
          ? property.get().synced()
          : inherited;
      if (!synced) {
        return WalkResult.stoppedShort(PathVerdict.notShared(segment, index, owner.getSimpleName()));
      }
      if (index == (path.size() - 1)) {
        // the type the attribute DECLARES, not the element type for a further segment. A
        // model which reads this path reads the attribute itself
        return new WalkResult(PathVerdict.aSharedValue(), property.get().getter().getReturnType());
      }
      final var next = typeBehind(property.get().getter().getGenericReturnType());
      if (next == null) {
        return WalkResult.stoppedShort(PathVerdict.undecidable());
      }
      owner = next;
      final var ofType = baseModeOf(owner);
      inherited = ofType != null
          ? ofType
          : synced;
    }
    // unreachable: the last segment answers inside the loop
    return WalkResult.stoppedShort(PathVerdict.undecidable());

  }

  /**
   * Whether the declared type holds whatever it was given: an {@link Object}, a
   * {@link Map} which answers whatever key it happens to hold, or a collection whose
   * elements the walk could not resolve. None of them can rule out an attribute.
   *
   * @param owner The declared type
   * @return Whether the walk has to stay silent
   */
  private static boolean holdsWhateverItWasGiven(
      final Class<?> owner) {

    return (owner == Object.class) || Map.class.isAssignableFrom(owner) || Collection.class
        .isAssignableFrom(owner);

  }

  /**
   * Whether a value of that declared type reaches the BPMS as ONE value and not as a
   * structure of its own. Those are the numbers, texts, booleans and characters which
   * {@link #convert} passes through, the enums which it turns into their name, and the JDK
   * value types which it turns into their text. Nothing below such a value exists in the
   * BPMS. That is why <code>order.dueDate.year</code> reads nothing where
   * <code>dueDate</code> is a {@code LocalDate}.
   *
   * @param owner The declared type
   * @return Whether the value carries no members
   */
  private static boolean travelsAsASingleValue(
      final Class<?> owner) {

    return owner.isPrimitive() || owner.isEnum() || CharSequence.class
        .isAssignableFrom(owner) || Number.class
            .isAssignableFrom(owner) || (owner == Boolean.class) || (owner == Character.class) || travelsAsText(
                owner);

  }

  /**
   * The declared type against which the NEXT segment is read. That is the attribute's own
   * type, or the element type of a collection or an array, because a model which navigates
   * into one reads an element. It is <code>null</code> wherever the type arguments do not
   * say what the elements are.
   *
   * @param type The attribute's generic type
   * @return The type to continue with, or <code>null</code>
   */
  private static Class<?> typeBehind(
      final java.lang.reflect.Type type) {

    if (type instanceof Class<?> clazz) {
      if (clazz.isArray()) {
        return typeBehind(clazz.getComponentType());
      }
      // a raw collection: nothing says what its elements are
      return Collection.class.isAssignableFrom(clazz)
          ? null
          : clazz;
    }
    if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
      final var raw = typeBehind(parameterized.getRawType());
      if ((raw != null) && !Collection.class.isAssignableFrom(raw)) {
        return raw;
      }
      final var arguments = parameterized.getActualTypeArguments();
      return arguments.length == 1
          ? typeBehind(arguments[0])
          : null;
    }
    if (type instanceof java.lang.reflect.GenericArrayType genericArray) {
      return typeBehind(genericArray.getGenericComponentType());
    }
    // a wildcard or a type variable names no type the walk could read
    return null;

  }

  @Override
  public void validateSyncModel(
      final Class<?> workflowAggregateClass) {

    if (workflowAggregateClass == null) {
      return;
    }
    // a set, because a type which is reachable over more than one path is looked at once
    // per path, and the same defect must not be said twice
    final var defects = new java.util.LinkedHashSet<String>();
    // the walk starts out sharing. FULL is the default of every adapter, so an aggregate
    // which annotates nothing is shared as a whole (see AggregateSyncMode)
    validateType(workflowAggregateClass, true, 0, new java.util.HashMap<>(), defects);
    if (defects.isEmpty()) {
      return;
    }
    throw new IllegalStateException(String.join("\n", defects));

  }

  /**
   * Derives the mode of one type and of every type reachable from its attributes. It
   * collects the defects instead of throwing on the first one, so that one boot reports
   * every gap it can find.
   *
   * @param clazz The type to validate
   * @param sharedUnlessAnnotated Whether the attributes of this type reach the BPMS as
   *          long as they have no annotation of their own
   * @param depth The current nesting depth
   * @param visited The types already validated, each with whether that happened on a path
   *          which shares them (cyclic type graphs). A type first reached through an
   *          attribute which nobody shares is looked at again once a shared path leads to
   *          it. What is refused depends on whether the attribute travels
   * @param defects Collects the guiding messages
   */
  private void validateType(
      final Class<?> clazz,
      final boolean sharedUnlessAnnotated,
      final int depth,
      final Map<Class<?>, Boolean> visited,
      final java.util.Set<String> defects) {

    Boolean ownMode = null;
    IllegalStateException modeCannotBeDerived = null;
    try {
      ownMode = baseModeOf(clazz);
    } catch (final IllegalStateException e) {
      modeCannotBeDerived = e;
    }
    // a type states its own mode where it has one. Where it has none, it inherits from
    // the attribute which holds it. valuesOf walks the same chain at a sync point
    final var shared = ownMode != null
        ? ownMode
        : sharedUnlessAnnotated;
    final var validatedBefore = visited.get(clazz);
    if (validatedBefore == null) {
      if (modeCannotBeDerived != null) {
        defects.add(modeCannotBeDerived.getMessage());
      }
      reportAttributesNothingReadsBack(clazz);
    } else if (validatedBefore || !shared) {
      // a second look adds nothing. Either the shared path was walked already, or this
      // path shares no more than the path which was walked
      return;
    }
    visited.put(clazz, shared);
    refuseAttributesWhoseTextIsNoValue(clazz, shared, defects);
    if (depth >= MAX_DEPTH) {
      // the documented limit: nested types are followed at most MAX_DEPTH levels deep. A
      // type which is only reached deeper, or only at runtime (for example a subclass
      // assigned to an attribute of a supertype), still fails loudly and clearly at the
      // first sync point, with the very same message.
      log.debug(
          "Stopped validating the sync model at depth {} (class '{}') - nested types are followed "
              + "at most {} levels deep",
          depth,
          clazz.getName(),
          MAX_DEPTH);
      return;
    }
    for (final var property : propertiesOf(clazz)) {
      // a nested type inherits from the attribute which holds it. So the walk carries down
      // what that attribute says about sharing
      final var sharedBelow = property.synced() != null
          ? property.synced()
          : shared;
      attributeTypes(property.getter().getGenericReturnType())
          .distinct()
          .forEach(attributeType -> validateType(attributeType, sharedBelow, depth + 1, visited, defects));
    }

  }

  /**
   * Refuses an attribute which is shared with the BPMS although its text is no value at
   * all. A {@link java.util.Calendar} travels as the debug form of its implementation,
   * several hundred characters which name every field.
   * <p>
   * The boot fails here. The attribute is not left out quietly. An attribute which stopped
   * being a process variable would make a model read null and take a branch which nobody
   * chose. So the application either declares a type which travels, or says that this
   * attribute does not travel.
   * <p>
   * Unlike the warning of {@link #reportAttributesNothingReadsBack(Class)}, this asks
   * whether the attribute really is shared, along the chain which a sync point walks. A
   * boot which fails must not rest on a guess. The only thing left to guess is the
   * adapter's default, which is FULL for every adapter there is.
   *
   * @param clazz The type whose attributes are looked at
   * @param sharedUnlessAnnotated Whether its attributes reach the BPMS as long as they have
   *          no annotation of their own
   * @param defects Collects the guiding messages
   */
  private void refuseAttributesWhoseTextIsNoValue(
      final Class<?> clazz,
      final boolean sharedUnlessAnnotated,
      final java.util.Set<String> defects) {

    for (final var property : propertiesOf(clazz)) {
      final var shared = property.synced() != null
          ? property.synced()
          : sharedUnlessAnnotated;
      if (!shared) {
        continue;
      }
      typesOfAnAttribute(property.getter().getGenericReturnType())
          .filter(TextValueTypes::isRefusedOnTheWayOut)
          .distinct()
          .forEach(type -> defects
              .add(
                  """
                      The attribute '%s' of '%s' is a '%s'. %s The application does not boot with \
                      such an attribute, because sharing it is refused and leaving it out would \
                      make a model read null and take a branch nobody chose. Annotate the \
                      attribute @NoSyncWithBPMS where no model needs it."""
                      .formatted(
                          property.name(),
                          clazz.getName(),
                          type.getName(),
                          TextValueTypes.adviceFor(type))));
    }

  }

  /**
   * Names the attributes whose type reaches the BPMS as a text which nothing reads back,
   * while the application boots. Such an attribute works on the way out and fails on the
   * way in. The way in is a task handler. So without this, the application learns about it
   * when a model first maps the value into a handler.
   * <p>
   * This is a warning, not a defect. The attribute may never be shared at all. The
   * adapter's default decides that, and it is not known here. And an aggregate whose values
   * only ever travel outwards is a normal application. An attribute which the application
   * marked {@code @NoSyncWithBPMS} is left out, because it will certainly never travel.
   * <p>
   * A type which is refused on the way out is not named here.
   * {@link #refuseAttributesWhoseTextIsNoValue(Class, boolean, java.util.Set)} covers that
   * case, and it does so with a failed boot.
   *
   * @param clazz The type whose attributes are looked at
   */
  private void reportAttributesNothingReadsBack(
      final Class<?> clazz) {

    for (final var property : propertiesOf(clazz)) {
      if (Boolean.FALSE.equals(property.synced())) {
        continue;
      }
      typesOfAnAttribute(property.getter().getGenericReturnType())
          .filter(AggregateSyncSupport::isTextNothingReadsBack)
          .filter(type -> !TextValueTypes.isRefusedOnTheWayOut(type))
          .distinct()
          .forEach(type -> findings
              .warn(
                  io.vanillabp.integration.spi.startup.StartupTopic.CODE,
                  "attribute '%s' of '%s'".formatted(property.name(), clazz.getName()),
                  """
                      This attribute is a '%s'. Nothing reads its text back, so a @TaskParam and \
                      an attribute of an aggregate the BPMS starts fail when they are declared as \
                      one. %s"""
                      .formatted(type.getName(), TextValueTypes.adviceFor(type))));
    }

  }

  /**
   * Whether values of that declared type travel as text, and no type of
   * {@link TextValueTypes} reads that text back. The plain values are not such a case,
   * because a number, a text, a boolean, a character and an enum travel as themselves. Nor
   * is a collection or a map, because their elements are asked about separately. Nor is a
   * type of the application's own, because it travels as a structure and is walked into.
   *
   * @param type The declared type of an attribute, or of an element of one
   * @return Whether an attribute of that type is worth a word at startup
   */
  private static boolean isTextNothingReadsBack(
      final Class<?> type) {

    if (type.isPrimitive() || Enum.class.isAssignableFrom(type) || (type == Object.class)) {
      return false;
    }
    if (CharSequence.class.isAssignableFrom(type) || Number.class
        .isAssignableFrom(type) || (type == Boolean.class) || (type == Character.class)) {
      return false;
    }
    if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
      return false;
    }
    return travelsAsText(type) && (TextValueTypes.ofDeclaredType(type) == null);

  }

  /**
   * Every type which an attribute names: the declared type itself, the component type of
   * an array, and the type arguments of a generic type. Unlike {@link #attributeTypes} this
   * keeps the types which travel as text, because those are the ones asked about here.
   *
   * @param type The attribute's generic type
   * @return The types named
   */
  private static java.util.stream.Stream<Class<?>> typesOfAnAttribute(
      final java.lang.reflect.Type type) {

    if (type instanceof Class<?> clazz) {
      return clazz.isArray()
          ? typesOfAnAttribute(clazz.getComponentType())
          : java.util.stream.Stream.of(clazz);
    }
    if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
      return java.util.stream.Stream
          .concat(
              typesOfAnAttribute(parameterized.getRawType()),
              java.util.Arrays
                  .stream(parameterized.getActualTypeArguments())
                  .flatMap(AggregateSyncSupport::typesOfAnAttribute));
    }
    if (type instanceof java.lang.reflect.GenericArrayType genericArray) {
      return typesOfAnAttribute(genericArray.getGenericComponentType());
    }
    // a wildcard or a type variable names no type to look at
    return java.util.stream.Stream.empty();

  }

  /**
   * The types of which an attribute may hold values: the declared type itself, plus the
   * type arguments of a generic type (a {@code List<Item>} holds {@code Item}s). Types whose
   * values are never followed (JDK value types, primitives, enums) are left out.
   *
   * @param type The attribute's generic type
   * @return The types to validate
   */
  private static java.util.stream.Stream<Class<?>> attributeTypes(
      final java.lang.reflect.Type type) {

    if (type instanceof Class<?> clazz) {
      if (clazz.isArray()) {
        return attributeTypes(clazz.getComponentType());
      }
      return clazz.isPrimitive() || clazz.isEnum() || travelsAsText(clazz)
          ? java.util.stream.Stream.empty()
          : java.util.stream.Stream.of(clazz);
    }
    if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
      return java.util.stream.Stream
          .concat(
              attributeTypes(parameterized.getRawType()),
              java.util.Arrays
                  .stream(parameterized.getActualTypeArguments())
                  .flatMap(AggregateSyncSupport::attributeTypes));
    }
    if (type instanceof java.lang.reflect.GenericArrayType genericArray) {
      return attributeTypes(genericArray.getGenericComponentType());
    }
    // wildcards and type variables have no annotations of their own. The type which is
    // really used at runtime is validated at the first sync point
    return java.util.stream.Stream.empty();

  }

  /**
   * The base mode of a type: its own annotation, or else the mode DERIVED from its
   * attributes (see the class comment), or else <code>null</code>, which means
   * "inherits".
   *
   * @param clazz The type
   * @return {@code true}/{@code false} or <code>null</code> if it inherits
   * @throws IllegalStateException If the type's attributes are annotated both ways and the
   *           class does not state its own mode (with a guiding message)
   */
  private Boolean baseModeOf(
      final Class<?> clazz) {

    return baseModeByClass
        .computeIfAbsent(clazz, this::determineBaseMode)
        .orElse(null);

  }

  private Optional<Boolean> determineBaseMode(
      final Class<?> clazz) {

    final var declared = annotationOf(clazz);
    if (declared != null) {
      return Optional.of(declared);
    }
    final var synced = annotatedAttributes(clazz, Boolean.TRUE);
    final var notSynced = annotatedAttributes(clazz, Boolean.FALSE);
    if (!synced.isEmpty() && !notSynced.isEmpty()) {
      throw new IllegalStateException(
          """
              The class '%s' has attributes annotated with @SyncWithBPMS (%s) AND attributes \
              annotated with @NoSyncWithBPMS (%s), but does not state its own mode - whether the \
              remaining attributes are shared with the BPMS cannot be derived! Annotate the CLASS \
              explicitly:
                @NoSyncWithBPMS shares ONLY the @SyncWithBPMS attributes (opt-in),
                @SyncWithBPMS shares EVERYTHING EXCEPT the @NoSyncWithBPMS attributes (opt-out)."""
              .formatted(
                  clazz.getName(),
                  String.join(", ", synced),
                  String.join(", ", notSynced)));
    }
    if (!synced.isEmpty()) {
      // opt-in: naming what IS shared means the rest is not
      return Optional.of(Boolean.FALSE);
    }
    if (!notSynced.isEmpty()) {
      // opt-out: naming what is NOT shared means the rest is
      return Optional.of(Boolean.TRUE);
    }
    // no annotation anywhere: the adapter decides for the root, and the attribute which
    // holds a nested type decides for that type
    return Optional.empty();

  }

  private List<String> annotatedAttributes(
      final Class<?> clazz,
      final Boolean annotation) {

    return propertiesOf(clazz)
        .stream()
        .filter(property -> annotation.equals(property.synced()))
        .map(property -> "'%s'".formatted(property.name()))
        .toList();

  }

  /**
   * The shared attributes of one object.
   *
   * @param owner The object
   * @param inherited Whether its attributes are shared unless annotated otherwise
   * @param depth The current nesting depth
   * @param ancestors The objects on the current path (cycle detection)
   */
  private Map<String, Object> valuesOf(
      final Object owner,
      final boolean inherited,
      final int depth,
      final java.util.Set<Object> ancestors) {

    final var values = new LinkedHashMap<String, Object>();
    ancestors.add(owner);
    try {
      for (final var property : propertiesOf(owner.getClass())) {
        final var synced = property.synced() != null
            ? property.synced()
            : inherited;
        if (!synced) {
          continue;
        }
        values.put(property.name(), convert(property.read(owner), synced, depth + 1, ancestors));
      }
    } finally {
      // the set holds the objects of the CURRENT path only. The same object in two sibling
      // branches is shared twice, and a cycle is cut
      ancestors.remove(owner);
    }
    return values;

  }

  /**
   * Converts one value into a form which a BPMS can take (see the class comment).
   *
   * @param value The value
   * @param inherited What nested attributes inherit
   * @param depth The current nesting depth
   * @param ancestors The objects on the current path (cycle detection)
   */
  private Object convert(
      final Object value,
      final boolean inherited,
      final int depth,
      final java.util.Set<Object> ancestors) {

    if ((value == null) || (value instanceof String) || (value instanceof Number) || (value instanceof Boolean) || (value instanceof Character)) {
      return value;
    }
    if (value instanceof Enum<?> enumValue) {
      return enumValue.name();
    }
    if (value instanceof Collection<?> collection) {
      return collection
          .stream()
          .map(element -> convert(element, inherited, depth, ancestors))
          .toList();
    }
    if (value.getClass().isArray()) {
      final var elements = new LinkedList<Object>();
      final var length = java.lang.reflect.Array.getLength(value);
      for (var index = 0; index < length; ++index) {
        elements.add(convert(java.lang.reflect.Array.get(value, index), inherited, depth, ancestors));
      }
      return List.copyOf(elements);
    }
    if (value instanceof Map<?, ?> map) {
      final var converted = new LinkedHashMap<String, Object>();
      map.forEach((
          key,
          mapValue) -> converted.put(String.valueOf(key), convert(mapValue, inherited, depth, ancestors)));
      return converted;
    }

    final var howItTravels = TextValueTypes.ofValue(value.getClass());
    if (howItTravels != null) {
      // a type carried both ways. The text is the one the way back reads. It is found by
      // what the value IS, not by the package of its class: a zone is a
      // java.time.ZoneRegion, and a time zone is a sun.util.calendar.ZoneInfo
      return howItTravels.write().apply(value);
    }

    if (travelsAsText(value.getClass())) {
      // every other type of the JDK (java.net.URI, java.util.Locale, ...). It does have
      // readable getters, but those are implementation details. Following them into a
      // package which the runtime exports to nobody ends the sync point. A BPMS, and a BPMN
      // expression, can work with the text anyway
      return String.valueOf(value);
    }

    if (ancestors.contains(value)) {
      // a CYCLE. The relations of ordinary entities in both directions (order -> item ->
      // order) are the normal case, not an exotic one. Following them would copy the whole
      // subtree once per level until MAX_DEPTH, so the cycle is cut right here.
      log.debug(
          "Cut a cycle while collecting the values shared with the BPMS: '{}' is already part of "
              + "the current path (depth {})",
          value
              .getClass()
              .getName(),
          depth);
      return referenceTo(value);
    }

    if (depth >= MAX_DEPTH) {
      // a very deep object graph: stop following it, and share a stand-in instead of
      // going down forever
      log.debug(
          "Stopped collecting values shared with the BPMS at depth {} (class '{}') - "
              + "nested objects are followed at most {} levels deep",
          depth,
          value
              .getClass()
              .getName(),
          MAX_DEPTH);
      return referenceTo(value);
    }

    final var properties = propertiesOf(value.getClass());
    if (properties.isEmpty()) {
      // no readable attributes (for example java.time types, UUID, types like BigDecimal).
      // Every BPMS understands the string form
      return String.valueOf(value);
    }
    // the own mode of a nested TYPE (its annotation, or the one derived from its
    // attributes) overrides what its members inherited through the attribute which holds
    // it
    final var ofType = baseModeOf(value.getClass());
    return valuesOf(value, ofType != null
        ? ofType
        : inherited, depth, ancestors);

  }

  /**
   * The stand-in for an object which is NOT followed, because a cycle was cut or the
   * nesting limit was reached. It is the type and identity of the object. It is deliberately
   * not {@code toString()}. The object graphs which this guards against are exactly the
   * ones whose generated {@code toString()} recurses (Lombok on relations in both
   * directions). A task completion must not fail because of a value which is only good for
   * a log.
   *
   * @param value The object not followed
   * @return A stable, harmless representation
   */
  private static String referenceTo(
      final Object value) {

    return "%s@%s".formatted(
        value
            .getClass()
            .getName(),
        Integer.toHexString(System.identityHashCode(value)));

  }

  /**
   * Whether values of the given type reach the BPMS as ONE text and not as a structure of
   * their own (see {@link #convert}). Collections, maps and arrays are handled before this
   * check.
   * <p>
   * There are two questions, because a class can fail either of them. A type which
   * {@link TextValueTypes} carries counts, whatever its name. That is how a subclass which
   * the runtime hands out is recognised. Everything else which the JDK itself wrote is text
   * too. Its getters are implementation details. And a package like
   * <code>sun.util.calendar</code> is exported to nobody, so reading the getters ended the
   * sync point with an <code>InaccessibleObjectException</code>. A type named
   * <code>java.*</code> or <code>javax.*</code> counts as well. That keeps a
   * <code>javax</code> type of a library on the class path behaving as before.
   */
  private static boolean travelsAsText(
      final Class<?> clazz) {

    if (TextValueTypes.ofValue(clazz) != null) {
      return true;
    }
    final var packageName = clazz.getPackageName();
    if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
      return true;
    }
    final var module = clazz.getModule();
    return module.isNamed() && (module.getName().startsWith("java.") || module
        .getName()
        .startsWith("jdk."));

  }

  /**
   * @param annotated A class, field or getter
   * @return {@code true}/{@code false} if the application annotated it,
   *         <code>null</code> if it inherits
   */
  private static Boolean annotationOf(
      final java.lang.reflect.AnnotatedElement annotated) {

    final var synced = annotated.isAnnotationPresent(SyncWithBPMS.class);
    final var notSynced = annotated.isAnnotationPresent(NoSyncWithBPMS.class);
    if (synced && notSynced) {
      throw new IllegalStateException(
          ("'%s' is annotated with both @SyncWithBPMS and @NoSyncWithBPMS! Decide whether its value "
              + "is shared with the BPMS - if it is meant to be shared only sometimes, model that as "
              + "an own (intention-revealing) getter.")
              .formatted(annotated));
    }
    if (synced) {
      return Boolean.TRUE;
    }
    if (notSynced) {
      return Boolean.FALSE;
    }
    return null;

  }

  /**
   * The readable properties of a class including their annotations, cached.
   */
  private List<Property> propertiesOf(
      final Class<?> clazz) {

    return propertiesByClass.computeIfAbsent(clazz, AggregateSyncSupport::determineProperties);

  }

  private static List<Property> determineProperties(
      final Class<?> clazz) {

    final var properties = new LinkedList<Property>();
    for (final var method : clazz.getMethods()) {
      final var name = propertyNameOf(method);
      if (name == null) {
        continue;
      }
      method.setAccessible(true);
      properties.add(new Property(name, method, annotationOf(fieldOrGetter(clazz, name, method))));
    }
    properties.sort(java.util.Comparator.comparing(Property::name));
    return List.copyOf(properties);

  }

  /**
   * The element with the application's annotation. The GETTER wins, because a getter with a
   * telling name is annotated there. Otherwise it is the field of the same name, if there
   * is one.
   */
  private static java.lang.reflect.AnnotatedElement fieldOrGetter(
      final Class<?> clazz,
      final String propertyName,
      final Method getter) {

    if (getter.isAnnotationPresent(SyncWithBPMS.class) || getter.isAnnotationPresent(NoSyncWithBPMS.class)) {
      return getter;
    }
    final var field = findField(clazz, propertyName);
    return field != null
        ? field
        : getter;

  }

  private static Field findField(
      final Class<?> clazz,
      final String propertyName) {

    var current = clazz;
    while ((current != null) && (current != Object.class)) {
      try {
        return current.getDeclaredField(propertyName);
      } catch (final NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    return null;

  }

  /**
   * @return The JavaBean property name of a readable getter or <code>null</code>
   */
  private static String propertyNameOf(
      final Method method) {

    if (method.getParameterCount() > 0) {
      return null;
    }
    if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
      return null;
    }
    if (method.getDeclaringClass() == Object.class) {
      return null;
    }
    final var name = method.getName();
    if (name.equals("getClass")) {
      return null;
    }
    if (name.startsWith("get") && (name.length() > 3)) {
      return Introspector.decapitalize(name.substring(3));
    }
    if (name.startsWith("is") && (name
        .length() > 2) && ((method.getReturnType() == boolean.class) || (method.getReturnType() == Boolean.class))) {
      return Introspector.decapitalize(name.substring(2));
    }
    return null;

  }

}
