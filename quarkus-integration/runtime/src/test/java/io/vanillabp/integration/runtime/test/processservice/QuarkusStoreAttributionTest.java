package io.vanillabp.integration.runtime.test.processservice;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.runtime.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.runtime.outbox.JdbcPhaseTwoOutbox;
import io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutbox;
import io.vanillabp.integration.runtime.persistence.PanacheActiveRecordAggregatePersistence;
import io.vanillabp.integration.runtime.persistence.PanacheMongoActiveRecordAggregatePersistence;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.runtime.processservice.QuarkusPhaseTwoOutboxResolver;
import io.vanillabp.integration.runtime.processservice.QuarkusTaskDeliveryLogResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 84: an application with two persistences does not attribute its stores itself. Both
 * defaults are registered as soon as a data source and a MongoDB client are configured, and
 * which of them serves an aggregate is read off the persistence VanillaBP resolved for that
 * aggregate.
 * <p>
 * The doubles below extend the platform defaults instead of implementing the interfaces,
 * because that is what the resolvers recognise - and it is what the container hands them: a
 * client proxy is a subclass, too.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusStoreAttributionTest {

  private static class OrderAggregate {
  }

  private static class ShipmentAggregate {
  }

  /** An aggregate the application persists itself, so no technology can be read off it. */
  private static class LedgerAggregate {
  }

  private static class LedgerPersistence implements AggregatePersistenceAware<LedgerAggregate> {

    @Override
    public Class<LedgerAggregate> getAggregateClass() {
      return LedgerAggregate.class;
    }

  }

  private static final JdbcPhaseTwoOutbox JDBC_OUTBOX = new JdbcPhaseTwoOutbox() {

    @Override
    public boolean isAvailable() {
      return true;
    }

  };

  private static final MongoPhaseTwoOutbox MONGO_OUTBOX = new MongoPhaseTwoOutbox() {

    @Override
    public boolean isAvailable() {
      return true;
    }

  };

  private static final JdbcTaskDeliveryLog JDBC_LOG = new JdbcTaskDeliveryLog() {

    @Override
    public boolean isAvailable() {
      return true;
    }

  };

  private static final MongoTaskDeliveryLog MONGO_LOG = new MongoTaskDeliveryLog() {

    @Override
    public boolean isAvailable() {
      return true;
    }

  };

  /** The persistences of an application storing one aggregate per technology. */
  private static QuarkusPersistenceTechnology mixedPersistences() {

    return new QuarkusPersistenceTechnology(
        InstanceDouble.of(
            List.<AggregatePersistenceAware<?>>of(
                new PanacheActiveRecordAggregatePersistence<>(OrderAggregate.class),
                new PanacheMongoActiveRecordAggregatePersistence<>(ShipmentAggregate.class),
                new LedgerPersistence())));

  }

  private static QuarkusPhaseTwoOutboxResolver outboxResolver(
      final List<PhaseTwoOutboxAware<?>> awares,
      final List<PhaseTwoOutbox> outboxes) {

    return new QuarkusPhaseTwoOutboxResolver(
        InstanceDouble.of(awares), InstanceDouble.of(outboxes), mixedPersistences(), true, true);

  }

  private static QuarkusTaskDeliveryLogResolver deliveryLogResolver(
      final List<TaskDeliveryLogAware<?>> awares,
      final List<TaskDeliveryLog> logs) {

    return new QuarkusTaskDeliveryLogResolver(
        InstanceDouble.of(awares), InstanceDouble.of(logs), mixedPersistences(), true, true);

  }

  @Test
  @DisplayName("With both defaults each aggregate gets the store of its own persistence")
  public void eachAggregateGetsTheStoreOfItsPersistence() {

    final var outboxes = outboxResolver(List.of(), List.of(JDBC_OUTBOX, MONGO_OUTBOX));
    assertSame(JDBC_OUTBOX, outboxes.resolveFor(OrderAggregate.class));
    assertSame(MONGO_OUTBOX, outboxes.resolveFor(ShipmentAggregate.class));

    final var logs = deliveryLogResolver(List.of(), List.of(JDBC_LOG, MONGO_LOG));
    assertSame(JDBC_LOG, logs.resolveFor(OrderAggregate.class));
    assertSame(MONGO_LOG, logs.resolveFor(ShipmentAggregate.class));

  }

  @Test
  @DisplayName("An aware bean of the application still wins over the attribution")
  public void awareBeansWin() {

    final var dedicated = new PhaseTwoOutbox() {

      @Override
      public boolean schedule(
          final io.vanillabp.integration.spi.PhaseTwoCall call) {
        return true;
      }

    };
    final var aware = new PhaseTwoOutboxAware<OrderAggregate>() {

      @Override
      public Class<OrderAggregate> getAggregateClass() {
        return OrderAggregate.class;
      }

      @Override
      public PhaseTwoOutbox getPhaseTwoOutbox() {
        return dedicated;
      }

    };

    assertSame(
        dedicated,
        outboxResolver(List.of(aware), List.of(JDBC_OUTBOX, MONGO_OUTBOX))
            .resolveFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("A single default of the other technology ends the boot instead of losing entries")
  public void aMismatchingSingleDefaultIsRefused() {

    final var outboxFailure = assertThrowsExactly(
        IllegalStateException.class,
        () -> outboxResolver(List.of(), List.of(JDBC_OUTBOX)).resolveFor(ShipmentAggregate.class));
    assertTrue(outboxFailure.getMessage().contains("MONGO"), outboxFailure.getMessage());

    final var logFailure = assertThrowsExactly(
        IllegalStateException.class,
        () -> deliveryLogResolver(List.of(), List.of(MONGO_LOG)).resolveFor(OrderAggregate.class));
    assertTrue(logFailure.getMessage().contains("JPA"), logFailure.getMessage());

  }

  @Test
  @DisplayName("A single default of the aggregate's own technology is used")
  public void aMatchingSingleDefaultIsUsed() {

    assertSame(
        JDBC_OUTBOX,
        outboxResolver(List.of(), List.of(JDBC_OUTBOX)).resolveFor(OrderAggregate.class));
    assertSame(
        MONGO_LOG,
        deliveryLogResolver(List.of(), List.of(MONGO_LOG)).resolveFor(ShipmentAggregate.class));

  }

  @Test
  @DisplayName("A persistence of the application is attributed by the application, or not at all")
  public void anApplicationOwnedPersistenceKeepsItsMessage() {

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> outboxResolver(List.of(), List.of(JDBC_OUTBOX, MONGO_OUTBOX))
            .resolveFor(LedgerAggregate.class));
    assertTrue(failure.getMessage().contains("UNKNOWN"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains(PhaseTwoOutboxAware.class.getName()),
        failure.getMessage());

    // a single default is used for it, as before: nothing says it is the wrong one
    assertSame(
        JDBC_OUTBOX,
        outboxResolver(List.of(), List.of(JDBC_OUTBOX)).resolveFor(LedgerAggregate.class));

  }

  @Test
  @DisplayName("Without any store nothing is resolved, and that is not an error here")
  public void withoutAnyStoreNothingIsResolved() {

    org.junit.jupiter.api.Assertions
        .assertNull(outboxResolver(List.of(), List.of()).resolveFor(OrderAggregate.class));
    org.junit.jupiter.api.Assertions
        .assertNull(deliveryLogResolver(List.of(), List.of()).resolveFor(OrderAggregate.class));

  }

}
