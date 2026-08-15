package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.vanillabp.integration.deployment.processservice.DefaultAggregatePersistenceResolver;

/**
 * Story 69: which of VanillaBP's persistence implementations serves an aggregate is
 * decided at build time on the Jandex index. A repository for the aggregate wins,
 * then the aggregate being an active record, then a Spring Data repository - and two
 * repositories for one aggregate fail the build instead of being picked by chance.
 * <p>
 * The MongoDB flavours are not indexed here: their extension would activate the
 * MongoDB client for every test application of this module. They are covered
 * end-to-end by {@code default-persistence-mongo-tests}.
 */
public class DefaultAggregatePersistenceResolverTest {

  /* -------------------------------------------------- */
  /* Test types                                         */
  /* -------------------------------------------------- */

  public static class PlainAggregate {
  }

  public static class PlainAggregateRepository implements PanacheRepositoryBase<PlainAggregate, String> {
  }

  public static class SecondPlainAggregateRepository implements PanacheRepositoryBase<PlainAggregate, String> {
  }

  public static class LongIdAggregate {
  }

  public interface LongIdAggregateRepositoryApi extends PanacheRepository<LongIdAggregate> {
  }

  public static class LongIdAggregateRepository implements LongIdAggregateRepositoryApi {
  }

  public static class ActiveRecordAggregate extends PanacheEntityBase {
  }

  public static class ActiveRecordWithRepository extends PanacheEntityBase {
  }

  public static class ActiveRecordWithRepositoryRepository implements PanacheRepositoryBase<ActiveRecordWithRepository, String> {
  }

  public static class SpringDataAggregate {
  }

  public interface SpringDataAggregateRepository extends CrudRepository<SpringDataAggregate, String> {
  }

  public static class UnknownAggregate {
  }

  /* -------------------------------------------------- */
  /* Tests                                              */
  /* -------------------------------------------------- */

  @Test
  @DisplayName("A Panache repository for the aggregate is used, no matter which of its two interfaces it implements")
  public void panacheRepositoryIsFound() throws Exception {

    final var index = indexOf(
        PlainAggregate.class,
        PlainAggregateRepository.class,
        LongIdAggregate.class,
        LongIdAggregateRepositoryApi.class,
        LongIdAggregateRepository.class);

    final var forPlain = resolve(index, PlainAggregate.class);
    assertEquals(
        DefaultAggregatePersistenceResolver.PANACHE_REPOSITORY_PERSISTENCE,
        forPlain.implementationClass());
    assertEquals(dotName(PlainAggregateRepository.class), forPlain.repositoryClass());

    // the repository interface of the application is not a bean - the class is
    final var forLongId = resolve(index, LongIdAggregate.class);
    assertEquals(dotName(LongIdAggregateRepository.class), forLongId.repositoryClass());

  }

  @Test
  @DisplayName("Without a repository the aggregate itself is asked: a Panache active record persists itself")
  public void activeRecordIsFound() throws Exception {

    final var index = indexOf(ActiveRecordAggregate.class);

    final var resolved = resolve(index, ActiveRecordAggregate.class);
    assertEquals(
        DefaultAggregatePersistenceResolver.PANACHE_ACTIVE_RECORD_PERSISTENCE,
        resolved.implementationClass());
    assertEquals(null, resolved.repositoryClass());

  }

  @Test
  @DisplayName("A repository beats the active record of the same aggregate")
  public void repositoryBeatsActiveRecord() throws Exception {

    final var index = indexOf(ActiveRecordWithRepository.class, ActiveRecordWithRepositoryRepository.class);

    final var resolved = resolve(index, ActiveRecordWithRepository.class);
    assertEquals(
        DefaultAggregatePersistenceResolver.PANACHE_REPOSITORY_PERSISTENCE,
        resolved.implementationClass());

  }

  @Test
  @DisplayName("A Spring Data repository answers last - the repository interface itself is the bean")
  public void springDataRepositoryIsFound() throws Exception {

    final var index = indexOf(SpringDataAggregate.class, SpringDataAggregateRepository.class);

    final var resolved = resolve(index, SpringDataAggregate.class);
    assertEquals(
        DefaultAggregatePersistenceResolver.SPRING_DATA_PERSISTENCE,
        resolved.implementationClass());
    assertEquals(dotName(SpringDataAggregateRepository.class), resolved.repositoryClass());

  }

  @Test
  @DisplayName("An aggregate using none of the idioms is not served")
  public void unknownAggregateIsNotServed() throws Exception {

    final var index = indexOf(UnknownAggregate.class);

    assertTrue(DefaultAggregatePersistenceResolver
        .resolve(index, dotName(UnknownAggregate.class))
        .isEmpty());

  }

  @Test
  @DisplayName("Two repositories for one aggregate fail the build naming both")
  public void severalRepositoriesFailTheBuild() throws Exception {

    final var index = indexOf(
        PlainAggregate.class,
        PlainAggregateRepository.class,
        SecondPlainAggregateRepository.class);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> DefaultAggregatePersistenceResolver.resolve(index, dotName(PlainAggregate.class)));
    assertTrue(failure.getMessage().contains(PlainAggregateRepository.class.getName()), failure.getMessage());
    assertTrue(failure.getMessage().contains(SecondPlainAggregateRepository.class.getName()), failure.getMessage());

  }

  /* -------------------------------------------------- */
  /* Helpers                                            */
  /* -------------------------------------------------- */

  private static DefaultAggregatePersistenceResolver.DefaultPersistence resolve(
      final IndexView index,
      final Class<?> aggregateClass) {

    return DefaultAggregatePersistenceResolver
        .resolve(index, dotName(aggregateClass))
        .orElseThrow(() -> new AssertionError(
            "no persistence resolved for "
                + aggregateClass.getName()));

  }

  private static DotName dotName(
      final Class<?> clazz) {

    return DotName.createSimple(clazz.getName());

  }

  private static IndexView indexOf(
      final Class<?>... classes) throws Exception {

    final var indexer = new Indexer();
    for (final var clazz : classes) {
      indexer.indexClass(clazz);
    }
    return indexer.complete();

  }

}
