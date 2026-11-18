package io.vanillabp.integration.config;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.ReadableKeys;
import io.vanillabp.integration.adapter.migration.config.UnreadKeys;

/**
 * Ends the start for every key below <code>vanillabp</code> which no binding took over, see
 * {@link UnreadKeys}.
 * <p>
 * The core, every adapter and every extension bind their part of the tree with a
 * {@code @ConfigurationProperties} class whose prefix is <code>vanillabp</code> or below it. The
 * core binds its model, an adapter binds its overlay class, at every level where it reads a
 * key. Spring Boot binds each class by itself and ignores the keys which that class does not
 * know. So a key which NO class knows was ignored as well, without a word. Quarkus has always
 * ended the start in that case, because there the mappings of all parts together have to cover
 * every key.
 * <p>
 * The check works in two steps, and neither of them knows the keys of an adapter:
 * <ol>
 * <li>While Spring Boot binds, a bind handler writes down every key below
 * <code>vanillabp</code> which a class took over. It is registered as a
 * {@link ConfigurationPropertiesBindHandlerAdvisor}, so it takes part in the binding of every
 * class, the overlays of adapters and extensions included.</li>
 * <li>When every singleton exists, the check binds what is not bound yet, a lazy overlay for
 * example. Then it compares the keys of every property source with the keys taken over. What
 * is left nobody reads.</li>
 * </ol>
 * For the message it also builds the tree of all keys which the classes CAN read, from their
 * types. That is how it says where a key is read instead.
 * <p>
 * Only keys which a {@code @ConfigurationProperties} class binds count as read. A key which a
 * part reads with {@code Environment#getProperty}, {@code @Value} or a {@code Binder} of its
 * own ends the start. Such a part has to bind the key in its overlay class as well.
 * <p>
 * {@code DECISIONS.pending/2185.md} says why a key nobody reads ends the start.
 */
public final class UnreadKeysCheck implements ConfigurationPropertiesBindHandlerAdvisor, SmartInitializingSingleton {

  private static final ConfigurationPropertyName ROOT = ConfigurationPropertyName.of(MigrationAdapterProperties.PREFIX);

  private final ConfigurableListableBeanFactory beanFactory;

  private final Environment environment;

  private final Set<ConfigurationPropertyName> boundNames = ConcurrentHashMap.newKeySet();

  private final Set<ConfigurationPropertyName> attemptedNames = ConcurrentHashMap.newKeySet();

  private final AtomicBoolean checked = new AtomicBoolean();

  /**
   * Built once per application context by the platform's auto-configuration.
   *
   * @param beanFactory Where the configuration-properties classes are found
   * @param environment Where the keys of the configuration are found
   */
  public UnreadKeysCheck(
      final ConfigurableListableBeanFactory beanFactory,
      final Environment environment) {

    this.beanFactory = beanFactory;
    this.environment = environment;

  }

  @Override
  public BindHandler apply(
      final BindHandler bindHandler) {

    return new WritesDownTheBoundKeys(bindHandler);

  }

  /**
   * Runs the check once. The startup validation of the process services calls it before its
   * own checks, so a key which nobody reads is reported before what it may have caused.
   * Spring calls it again as a {@link SmartInitializingSingleton}, and then it does nothing.
   *
   * @throws IllegalStateException If a key below <code>vanillabp</code> is not read
   */
  @Override
  public void afterSingletonsInstantiated() {

    if (!checked.compareAndSet(false, true)) {
      return;
    }
    final var readable = new ReadableKeys();
    for (final var binding : bindingsBelowTheRoot()) {
      var position = readable.root();
      for (var index = 1; index < binding
          .prefix()
          .getNumberOfElements(); ++index) {
        position = position.named(binding
            .prefix()
            .getElement(index, ConfigurationPropertyName.Form.DASHED));
      }
      addKeysOf(position, ResolvableType.forClass(binding.type()), new HashSet<>(), true);
    }

    UnreadKeys
        .refusal(readable, keysNotTakenOver())
        .ifPresent(refusal -> {
          throw refusal;
        });

  }

  /**
   * The {@code @ConfigurationProperties} beans which bind below <code>vanillabp</code>. Each is
   * created here if it does not exist yet, so it is bound.
   */
  private List<Binding> bindingsBelowTheRoot() {

    final var result = new ArrayList<Binding>();
    for (final var beanName : beanFactory.getBeanDefinitionNames()) {
      if (beanFactory
          .getBeanDefinition(beanName)
          .isAbstract()) {
        continue;
      }
      final var annotation = beanFactory.findAnnotationOnBean(beanName, ConfigurationProperties.class, false);
      if (annotation == null) {
        continue;
      }
      final var prefix = ConfigurationPropertyName.ofIfValid(StringUtils.hasText(annotation.prefix())
          ? annotation.prefix()
          : annotation.value());
      if ((prefix == null) || !(ROOT.equals(prefix) || ROOT.isAncestorOf(prefix))) {
        continue;
      }
      final var type = beanFactory.getType(beanName, false);
      if (type == null) {
        continue;
      }
      // a lazy bean is bound when it is created. Without this, its keys would count as
      // keys nobody reads
      beanFactory.getBean(beanName);
      result.add(new Binding(prefix, ClassUtils.getUserClass(type)));
    }
    return result;

  }

  /**
   * One class which binds below <code>vanillabp</code>.
   *
   * @param prefix The prefix of its annotation
   * @param type The class
   */
  private record Binding(
                         ConfigurationPropertyName prefix,
                         Class<?> type) {
  }

  /**
   * Adds the keys a type binds, the way Spring Boot's JavaBean binding reads them.
   *
   * @param position Where the type is bound
   * @param type The type
   * @param typesAbove The types on the way down, to stop at a type which contains itself
   * @param isRoot Whether this is the class with the annotation, which is always a section
   */
  private static void addKeysOf(
      final ReadableKeys.Position position,
      final ResolvableType type,
      final Set<Class<?>> typesAbove,
      final boolean isRoot) {

    final var rawType = type.resolve(Object.class);
    if (Map.class.isAssignableFrom(rawType)) {
      final var valueType = type
          .asMap()
          .getGeneric(1);
      if (isSection(valueType.resolve(Object.class))) {
        addKeysOf(position.anyKey(), valueType, typesAbove, false);
      } else {
        // a key of a map of values may contain dots, so any key below is read
        position.readWithAnythingBelow();
      }
      return;
    }
    if (rawType.isArray() || Collection.class.isAssignableFrom(rawType)) {
      final var elementType = rawType.isArray()
          ? type.getComponentType()
          : type
              .asCollection()
              .getGeneric(0);
      if (isSection(elementType.resolve(Object.class))) {
        // the index of a list element is not a step of the key, see UnreadKeys.UnreadKey
        addKeysOf(position, elementType, typesAbove, false);
      }
      position.readAsValue();
      return;
    }
    if (!isRoot && !isSection(rawType)) {
      position.readAsValue();
      return;
    }
    if (!typesAbove.add(rawType)) {
      return;
    }
    if (rawType.isRecord()) {
      for (final var component : rawType.getRecordComponents()) {
        addKeysOf(
            position.named(DataObjectPropertyName.toDashedForm(component.getName())),
            ResolvableType.forType(component.getGenericType()), typesAbove, false);
      }
    } else {
      for (final var property : BeanUtils.getPropertyDescriptors(rawType)) {
        final var propertyType = typeOf(property, rawType);
        if (propertyType != null) {
          addKeysOf(
              position.named(DataObjectPropertyName.toDashedForm(property.getName())), propertyType, typesAbove,
              false);
        }
      }
    }
    typesAbove.remove(rawType);

  }

  /**
   * The type of a property which Spring Boot binds: one with a setter, or one with a getter
   * whose section or map Spring Boot fills in place.
   *
   * @return The type, or <code>null</code> if Spring Boot does not bind the property
   */
  private static ResolvableType typeOf(
      final PropertyDescriptor property,
      final Class<?> owner) {

    if (property.getWriteMethod() != null) {
      return ResolvableType.forMethodParameter(property.getWriteMethod(), 0, owner);
    }
    if (property.getReadMethod() == null || "class".equals(property.getName())) {
      return null;
    }
    final var type = ResolvableType.forMethodReturnType(property.getReadMethod(), owner);
    final var rawType = type.resolve(Object.class);
    return (isSection(rawType) || Map.class.isAssignableFrom(rawType) || Collection.class.isAssignableFrom(rawType))
        ? type
        : null;

  }

  /**
   * Whether Spring Boot binds a type as a section with keys below it, not as one value. A
   * type of the JDK, of Spring or of Jakarta is a value, and so are an enum and a primitive.
   * Everything else is a class of the part which binds it, and that is a section. For the
   * classes of VanillaBP this is the promise decision 138 in the repository's DECISIONS.md
   * relies on.
   */
  private static boolean isSection(
      final Class<?> type) {

    if (type.isPrimitive() || type.isEnum() || type.isArray() || type == Object.class) {
      return false;
    }
    final var name = type.getName();
    return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.") || name
        .startsWith("org.springframework."));

  }

  /**
   * The keys below <code>vanillabp</code> of every property source which no binding took
   * over. A key written in two sources counts once, with the origin of the stronger source.
   */
  private List<UnreadKeys.UnreadKey> keysNotTakenOver() {

    final var boundAsEnvironmentVariables = new HashSet<ConfigurationPropertyName>();
    boundNames.forEach(name -> {
      final var legacyName = legacyEnvironmentNameOf(name);
      if (legacyName != null) {
        boundAsEnvironmentVariables.add(legacyName);
      }
    });

    final var found = new LinkedHashMap<ConfigurationPropertyName, ConfigurationProperty>();
    for (final var source : ConfigurationPropertySources.get(environment)) {
      if (!(source instanceof IterableConfigurationPropertySource iterable)) {
        continue;
      }
      for (final var name : iterable) {
        if (!ROOT.isAncestorOf(name) || found.containsKey(name) || boundNames
            .contains(name) || boundAsEnvironmentVariables.contains(name) || isOverriddenListElement(name)) {
          continue;
        }
        found.put(name, iterable.getConfigurationProperty(name));
      }
    }

    final var result = new ArrayList<UnreadKeys.UnreadKey>(found.size());
    found.forEach((
        name,
        property) -> result
            .add(new UnreadKeys.UnreadKey(printed(name), steps(name), originOf(property, printed(name)))));
    return result;

  }

  /**
   * Where a key was written, for the message: the file and the line, the environment variable,
   * or the name of the property source and how the key is spelled there.
   */
  private static String originOf(
      final ConfigurationProperty property,
      final String printedKey) {

    final var origin = property == null
        ? null
        : property.getOrigin();
    if (origin == null) {
      return null;
    }
    if ((origin instanceof PropertySourceOrigin fromAPropertySource) && (fromAPropertySource.getOrigin() != null)) {
      // the file and the line of a configuration file
      return fromAPropertySource
          .getOrigin()
          .toString();
    }
    if (origin instanceof PropertySourceOrigin fromAPropertySource) {
      // its own text repeats the key, which the message prints anyway. Only a name spelled
      // differently is worth printing, the name of an environment variable for example
      final var source = fromAPropertySource
          .getPropertySource()
          .getName();
      final var writtenAs = fromAPropertySource.getPropertyName();
      return printedKey.equals(writtenAs)
          ? "property source '%s'".formatted(source)
          : "property source '%s', written as '%s'".formatted(source, writtenAs);
    }
    return origin.toString();

  }

  /**
   * The name under which Spring Boot also finds a key in the environment variables: every
   * dash is a dot there. So <code>VANILLABP_ADAPTERS_C7_TENANT_ID</code> sets
   * <code>tenant-id</code>, although its name reads <code>tenant.id</code>.
   */
  private static ConfigurationPropertyName legacyEnvironmentNameOf(
      final ConfigurationPropertyName name) {

    final var steps = new ArrayList<String>(name.getNumberOfElements());
    for (var index = 0; index < name.getNumberOfElements(); ++index) {
      if (isIndexed(name, index)) {
        return null;
      }
      steps.add(name
          .getElement(index, ConfigurationPropertyName.Form.DASHED)
          .replace('-', '.'));
    }
    return ConfigurationPropertyName.ofIfValid(String.join(".", steps));

  }

  /**
   * Whether the key is an element of a list which a stronger source replaced as a whole. A
   * list is taken over from one source only. So the elements of a weaker source are not
   * bound, but they were overridden, not forgotten. The rule is the one of Spring Boot's
   * {@code NoUnboundElementsBindHandler}.
   */
  private boolean isOverriddenListElement(
      final ConfigurationPropertyName name) {

    final var lastIndex = name.getNumberOfElements() - 1;
    if (name.isLastElementIndexed()) {
      return boundNames.contains(name.chop(lastIndex));
    }
    for (var index = 0; index < name.getNumberOfElements(); ++index) {
      if (name.isNumericIndex(index)) {
        final var list = name.chop(index);
        if (index + 1 >= name.getNumberOfElements()) {
          return false;
        }
        final var firstElement = list.append("[0]");
        return boundNames.contains(firstElement) && attemptedNames.contains(firstElement.append(name
            .getElement(index + 1, ConfigurationPropertyName.Form.UNIFORM)));
      }
    }
    return false;

  }

  /** The key as it was written, with its case, for the message. */
  private static String printed(
      final ConfigurationPropertyName name) {

    final var result = new StringBuilder();
    for (var index = 0; index < name.getNumberOfElements(); ++index) {
      final var element = name.getElement(index, ConfigurationPropertyName.Form.ORIGINAL);
      if (isIndexed(name, index)) {
        result.append('[').append(element).append(']');
      } else {
        if (!result.isEmpty()) {
          result.append('.');
        }
        result.append(element);
      }
    }
    return result.toString();

  }

  /**
   * Whether a step has to be written in brackets: the index of a list element, or a key of a
   * map which contains a dot. Spring Boot does not say so publicly. Another key in brackets is
   * the same key as without them.
   */
  private static boolean isIndexed(
      final ConfigurationPropertyName name,
      final int index) {

    return name.isNumericIndex(index) || name
        .getElement(index, ConfigurationPropertyName.Form.ORIGINAL)
        .contains(".");

  }

  /** The steps of a key, without the index of a list element. */
  private static List<String> steps(
      final ConfigurationPropertyName name) {

    final var result = new ArrayList<String>(name.getNumberOfElements());
    for (var index = 0; index < name.getNumberOfElements(); ++index) {
      if (!name.isNumericIndex(index)) {
        result.add(name.getElement(index, ConfigurationPropertyName.Form.ORIGINAL));
      }
    }
    return result;

  }

  /**
   * Writes down every key below <code>vanillabp</code> which a class takes over, and every
   * key the binding looked for.
   */
  private final class WritesDownTheBoundKeys extends AbstractBindHandler {

    private WritesDownTheBoundKeys(
        final BindHandler parent) {

      super(parent);

    }

    @Override
    public <T> Bindable<T> onStart(
        final ConfigurationPropertyName name,
        final Bindable<T> target,
        final BindContext context) {

      if (ROOT.isAncestorOf(name)) {
        attemptedNames.add(name);
      }
      return super.onStart(name, target, context);

    }

    @Override
    public Object onSuccess(
        final ConfigurationPropertyName name,
        final Bindable<?> target,
        final BindContext context,
        final Object result) {

      if (ROOT.equals(name) || ROOT.isAncestorOf(name)) {
        boundNames.add(name);
      }
      return super.onSuccess(name, target, context, result);

    }

  }

}
