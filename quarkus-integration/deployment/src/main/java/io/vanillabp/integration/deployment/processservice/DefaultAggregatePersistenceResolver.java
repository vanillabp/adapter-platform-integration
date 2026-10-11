package io.vanillabp.integration.deployment.processservice;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * Picks the persistence which VanillaBP provides for an aggregate, if the application did
 * not write an {@link AggregatePersistenceAware} for it. Quarkus has no single persistence
 * idiom. So the aggregate itself is asked what it is:
 * <ol>
 * <li>a Panache repository for the aggregate (Hibernate ORM or MongoDB) wins,</li>
 * <li>otherwise the aggregate being a Panache active record (Hibernate ORM or
 * MongoDB),</li>
 * <li>otherwise a Spring Data repository for the aggregate.</li>
 * </ol>
 * Nothing is guessed from the extensions present. An application either has one of these
 * artifacts for the aggregate, or it has none. If it has none, the build fails with the
 * message of {@code ProcessServiceBuildStepProcessor}.
 * <p>
 * Everything is decided on the Jandex index. So a wrong or unclear setup is reported at
 * build time and not at the first workflow.
 */
public final class DefaultAggregatePersistenceResolver {

  /* Hibernate ORM Panache */
  static final DotName PANACHE_ENTITY_BASE = DotName
      .createSimple("io.quarkus.hibernate.orm.panache.PanacheEntityBase");

  static final Set<DotName> PANACHE_REPOSITORIES = Set.of(
      DotName.createSimple("io.quarkus.hibernate.orm.panache.PanacheRepository"),
      DotName.createSimple("io.quarkus.hibernate.orm.panache.PanacheRepositoryBase"));

  /* MongoDB Panache */
  static final DotName PANACHE_MONGO_ENTITY_BASE = DotName
      .createSimple("io.quarkus.mongodb.panache.PanacheMongoEntityBase");

  static final Set<DotName> PANACHE_MONGO_REPOSITORIES = Set.of(
      DotName.createSimple("io.quarkus.mongodb.panache.PanacheMongoRepository"),
      DotName.createSimple("io.quarkus.mongodb.panache.PanacheMongoRepositoryBase"));

  /*
   * Spring Data: the repository interfaces an application extends. VanillaBP calls
   * CrudRepository. The others are listed because they extend it. The artifact which
   * declares them is not always part of the index. Only classes of the application and of
   * extensions which ship a Jandex index are. So the hierarchy cannot always be followed up
   * to CrudRepository.
   */
  static final Set<DotName> SPRING_DATA_REPOSITORIES = Set.of(
      DotName.createSimple("org.springframework.data.repository.CrudRepository"),
      DotName.createSimple("org.springframework.data.repository.ListCrudRepository"),
      DotName.createSimple("org.springframework.data.repository.PagingAndSortingRepository"),
      DotName.createSimple("org.springframework.data.jpa.repository.JpaRepository"));

  /**
   * The implementation for an aggregate managed by a Hibernate ORM Panache repository. It is
   * one of those which {@code vanillabp-quarkus-integration} ships.
   * <p>
   * The implementations are named as strings and not as classes, because this resolver runs
   * while the application is built. Each of them extends a type of the persistence framework
   * it serves. Loading it in an application which does not use that framework would fail
   * right here. And the generated bean only needs the name. Gizmo writes the superclass into
   * bytecode and does not load anything either.
   */
  public static final String PANACHE_REPOSITORY_PERSISTENCE = "io.vanillabp.integration.runtime.persistence.PanacheRepositoryAggregatePersistence";

  /** The implementation for an aggregate which is a Hibernate ORM Panache active record. */
  public static final String PANACHE_ACTIVE_RECORD_PERSISTENCE = "io.vanillabp.integration.runtime.persistence.PanacheActiveRecordAggregatePersistence";

  /** The implementation for an aggregate managed by a MongoDB Panache repository. */
  public static final String PANACHE_MONGO_REPOSITORY_PERSISTENCE = "io.vanillabp.integration.runtime.persistence.PanacheMongoRepositoryAggregatePersistence";

  /** The implementation for an aggregate which is a MongoDB Panache active record. */
  public static final String PANACHE_MONGO_ACTIVE_RECORD_PERSISTENCE = "io.vanillabp.integration.runtime.persistence.PanacheMongoActiveRecordAggregatePersistence";

  /** The implementation for an aggregate managed by a Spring Data repository. */
  public static final String SPRING_DATA_PERSISTENCE = "io.vanillabp.integration.runtime.persistence.SpringDataAggregatePersistence";

  private DefaultAggregatePersistenceResolver() {
  }

  /**
   * The persistence chosen for an aggregate.
   *
   * @param implementationClass The VanillaBP implementation to be used
   * @param repositoryClass The repository bean the implementation delegates to, or
   *        <code>null</code> for the active-record implementations
   * @param idiom Human-readable name of the idiom, used in log and error messages
   */
  public record DefaultPersistence(
                                   String implementationClass,
                                   DotName repositoryClass,
                                   String idiom) {
  }

  /**
   * The persistence for the given aggregate, picked in the order which the class describes.
   * An aggregate may be claimed twice within one of those steps. That is two repositories of
   * one kind, or a Hibernate ORM and a MongoDB artifact of the same shape. Then the build
   * ends here, with a message which names both.
   *
   * @param index The index to look up classes in (the combined index, so classes of
   *        extensions shipping a Jandex index are visible, too)
   * @param aggregateType The workflow aggregate's class
   * @return The persistence to be used or {@link Optional#empty()} if the aggregate
   *         uses none of the known idioms
   */
  public static Optional<DefaultPersistence> resolve(
      final IndexView index,
      final DotName aggregateType) {

    final var panacheRepository = repositoryFor(index, aggregateType, PANACHE_REPOSITORIES, false);
    final var mongoRepository = repositoryFor(index, aggregateType, PANACHE_MONGO_REPOSITORIES, false);
    if (panacheRepository.isPresent() && mongoRepository.isPresent()) {
      throw new IllegalStateException(
          """
              The workflow aggregate '%s' has both a Hibernate ORM Panache repository ('%s') and a \
              MongoDB Panache repository ('%s')! VanillaBP cannot tell which one owns the aggregate - \
              keep one of them or provide your own io.vanillabp.integration.spi.AggregatePersistenceAware \
              for this aggregate."""
              .formatted(aggregateType, panacheRepository.get(), mongoRepository.get()));
    }
    if (panacheRepository.isPresent()) {
      return Optional.of(new DefaultPersistence(
          PANACHE_REPOSITORY_PERSISTENCE, panacheRepository.get(), "Hibernate ORM Panache repository"));
    }
    if (mongoRepository.isPresent()) {
      return Optional.of(new DefaultPersistence(
          PANACHE_MONGO_REPOSITORY_PERSISTENCE, mongoRepository.get(), "MongoDB Panache repository"));
    }

    final var isPanacheEntity = isSubclassOf(index, aggregateType, PANACHE_ENTITY_BASE);
    final var isMongoEntity = isSubclassOf(index, aggregateType, PANACHE_MONGO_ENTITY_BASE);
    if (isPanacheEntity && isMongoEntity) {
      throw new IllegalStateException(
          """
              The workflow aggregate '%s' extends both PanacheEntityBase and PanacheMongoEntityBase! \
              Provide your own io.vanillabp.integration.spi.AggregatePersistenceAware for this aggregate."""
              .formatted(aggregateType));
    }
    if (isPanacheEntity) {
      return Optional.of(new DefaultPersistence(
          PANACHE_ACTIVE_RECORD_PERSISTENCE, null, "Hibernate ORM Panache active record"));
    }
    if (isMongoEntity) {
      return Optional.of(new DefaultPersistence(
          PANACHE_MONGO_ACTIVE_RECORD_PERSISTENCE, null, "MongoDB Panache active record"));
    }

    return repositoryFor(index, aggregateType, SPRING_DATA_REPOSITORIES, true)
        .map(repository -> new DefaultPersistence(
            SPRING_DATA_PERSISTENCE, repository, "Spring Data repository"));

  }

  /**
   * The repository bean managing the given aggregate.
   *
   * @param interfacesWanted Are the repositories interfaces (Spring Data, the
   *        implementation is generated by Quarkus) or classes (Panache)?
   * @return The repository or {@link Optional#empty()} if there is none; several
   *         repositories for one aggregate fail the build
   */
  private static Optional<DotName> repositoryFor(
      final IndexView index,
      final DotName aggregateType,
      final Set<DotName> repositoryInterfaces,
      final boolean interfacesWanted) {

    final var candidates = new ArrayList<DotName>();
    for (final var candidate : index.getKnownClasses()) {
      if (candidate.isInterface() != interfacesWanted) {
        continue;
      }
      if (!interfacesWanted && Modifier.isAbstract(candidate.flags())) {
        continue;
      }
      if (repositoryInterfaces.contains(candidate.name())) {
        continue;
      }
      firstTypeArgumentOf(index, candidate, repositoryInterfaces)
          .filter(aggregateType::equals)
          .ifPresent(entity -> candidates.add(candidate.name()));
    }
    if (candidates.size() > 1) {
      throw new IllegalStateException(
          """
              Several repositories manage the workflow aggregate '%s': %s. VanillaBP cannot tell which \
              one to use - keep one of them or provide your own \
              io.vanillabp.integration.spi.AggregatePersistenceAware for this aggregate."""
              .formatted(aggregateType, candidates));
    }
    return candidates
        .stream()
        .findFirst();

  }

  /**
   * The entity type which a class or interface names when it extends one of the given
   * repository interfaces, e.g. {@code Foo} for
   * {@code class FooRepository implements PanacheRepository<Foo>}. Superclasses and
   * super-interfaces are followed as far as the index knows them.
   */
  private static Optional<DotName> firstTypeArgumentOf(
      final IndexView index,
      final ClassInfo classInfo,
      final Set<DotName> repositoryInterfaces) {

    final var visited = new HashSet<DotName>();
    final var toBeInspected = new LinkedList<ClassInfo>();
    toBeInspected.add(classInfo);
    while (!toBeInspected.isEmpty()) {
      final var current = toBeInspected.removeFirst();
      if (!visited.add(current.name())) {
        continue;
      }
      final var supertypes = new LinkedHashSet<Type>(current.interfaceTypes());
      if (current.superClassType() != null) {
        supertypes.add(current.superClassType());
      }
      for (final var supertype : supertypes) {
        if (repositoryInterfaces.contains(supertype.name()) && (supertype.kind() == Type.Kind.PARAMETERIZED_TYPE)) {
          final List<Type> arguments = supertype
              .asParameterizedType()
              .arguments();
          if (!arguments.isEmpty() && (arguments.getFirst().kind() == Type.Kind.CLASS)) {
            return Optional.of(arguments
                .getFirst()
                .name());
          }
        }
        final var supertypeInfo = index.getClassByName(supertype.name());
        if (supertypeInfo != null) {
          toBeInspected.add(supertypeInfo);
        }
      }
    }
    return Optional.empty();

  }

  /**
   * Whether the aggregate extends the given base class. The superclass chain is followed as
   * far as the index knows it.
   */
  private static boolean isSubclassOf(
      final IndexView index,
      final DotName aggregateType,
      final DotName baseClass) {

    final var visited = new HashSet<DotName>();
    var current = index.getClassByName(aggregateType);
    while ((current != null) && visited.add(current.name())) {
      final var superType = current.superClassType();
      if (superType == null) {
        return false;
      }
      if (superType.name().equals(baseClass)) {
        return true;
      }
      current = index.getClassByName(superType.name());
    }
    return false;

  }

}
