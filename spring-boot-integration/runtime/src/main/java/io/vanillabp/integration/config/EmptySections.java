package io.vanillabp.integration.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * Makes a section of the <code>vanillabp</code> tree which is written as <code>{}</code> keep
 * the keys which other property sources set below it.
 * <p>
 * <code>vanillabp.adapters.camunda7: {}</code> is the shortest way to configure an adapter id.
 * Spring Boot reads the empty map of a YAML file as a property with an empty value,
 * <code>vanillabp.adapters.camunda7=</code>. When Spring Boot binds an object, it first asks
 * every property source for a value at the object's own name, and only then for the keys
 * below it. So the empty value won, an empty section was bound, and every key of that id from
 * the command line, an environment variable or a second YAML document was lost. That covered
 * <code>type</code>, <code>deployment-failure</code> and the <code>resources-location</code> of a
 * workflow module. The startup did not say so.
 * <p>
 * A section never has a value of its own. So where keys exist below a section, the section is
 * bound with {@link Bindable.BindRestriction#NO_DIRECT_PROPERTY}. That tells Spring Boot to skip
 * the value at the section's name and to read the keys below it. Where nothing exists below the
 * name, the binding stays as it was. Spring Boot then binds an empty section for the empty
 * value, and the map of adapters still gets the id.
 * <p>
 * The rule holds for every type of VanillaBP below <code>vanillabp</code>: the core model,
 * and the overlay classes of adapters and extensions, because their types live in packages
 * below <code>io.vanillabp</code> as well. A VanillaBP type there is always a section. A single
 * value is a JDK type or an enum, and enums are left out. Every other binding of the
 * application stays as it is.
 */
public final class EmptySections {

  private static final String TYPES_OF_VANILLABP = "io.vanillabp.";

  private EmptySections() {

  }

  /**
   * The advisor which applies the rule to every {@code @ConfigurationProperties} class bound
   * below <code>vanillabp</code>. There the sections are values of maps, so only a bind handler
   * reaches them. The platform registers it as a bean, and Spring Boot uses every such bean.
   *
   * @return The advisor
   */
  public static ConfigurationPropertiesBindHandlerAdvisor advisor() {

    return SectionsKeepTheKeysBelowThem::new;

  }

  /**
   * The bind handler for a {@code Binder} which binds a part of the <code>vanillabp</code> tree
   * directly from the environment, for example before configuration-properties beans exist.
   * Pass it as the third argument of {@code Binder.bind}.
   *
   * @return The bind handler
   */
  public static BindHandler bindHandler() {

    return advisor().apply(BindHandler.DEFAULT);

  }

  private static boolean isSectionWithKeysBelowIt(
      final ConfigurationPropertyName name,
      final Bindable<?> target,
      final BindContext context) {

    if (name.isEmpty() || !MigrationAdapterProperties.PREFIX
        .equals(name.getElement(0, ConfigurationPropertyName.Form.UNIFORM))) {
      return false;
    }
    final var type = target
        .getType()
        .resolve();
    if (type == null || type.isEnum() || !type
        .getName()
        .startsWith(TYPES_OF_VANILLABP)) {
      return false;
    }
    for (final var source : context.getSources()) {
      if (source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
        return true;
      }
    }
    return false;

  }

  private static final class SectionsKeepTheKeysBelowThem extends AbstractBindHandler {

    private SectionsKeepTheKeysBelowThem(
        final BindHandler parent) {

      super(parent);

    }

    @Override
    public <T> Bindable<T> onStart(
        final ConfigurationPropertyName name,
        final Bindable<T> target,
        final BindContext context) {

      final var replacement = super.onStart(name, target, context);
      if (replacement == null || !isSectionWithKeysBelowIt(name, replacement, context)) {
        return replacement;
      }
      return replacement.withBindRestrictions(Bindable.BindRestriction.NO_DIRECT_PROPERTY);

    }

  }

}
