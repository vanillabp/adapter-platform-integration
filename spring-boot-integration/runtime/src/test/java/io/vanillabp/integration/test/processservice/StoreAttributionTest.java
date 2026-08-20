package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.delivery.MongoTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.processservice.SpringPhaseTwoOutboxResolver;
import io.vanillabp.integration.processservice.SpringTaskDeliveryLogResolver;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which store a workflow aggregate's outbox entries and delivery records go to (stories
 * 70 and 84). Both have to be written in the transaction persisting the aggregate, so
 * guessing wrong is worse than not answering: a record committing separately breaks
 * exactly the atomicity it exists for.
 * <p>
 * What this pins is the case an application with its OWN persistence runs into - no
 * Spring Data repository, so no technology to attribute by. The message it gets has to
 * name the beans found, the aggregate and the way out, because nothing else tells the
 * developer what VanillaBP could not decide.
 */
@ExtendWith(SuppressOutputExtension.class)
public class StoreAttributionTest {

  /**
   * An aggregate persisted by the application itself - it has no Spring Data
   * repository, so no persistence technology can be read off it.
   */
  public static class CustomlyPersistedAggregate {

    String id;

  }

  /** A store of the application, carrying no release of its own. */
  public static class ApplicationDeliveryLog implements TaskDeliveryLog {

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {

      return Optional.empty();

    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {

      return true;

    }

  }

  private static final PhaseTwoOutbox SCHEDULING_OUTBOX = call -> true;

  private static AnnotationConfigApplicationContext withBothOutboxDefaults() {

    final var context = new AnnotationConfigApplicationContext();
    context
        .registerBean(
            GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME,
            PhaseTwoOutbox.class,
            () -> SCHEDULING_OUTBOX);
    context
        .registerBean(
            MongoPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME,
            PhaseTwoOutbox.class,
            () -> SCHEDULING_OUTBOX);
    return context;

  }

  private static AnnotationConfigApplicationContext withBothDeliveryLogDefaults() {

    final var context = new AnnotationConfigApplicationContext();
    context
        .registerBean(
            JdbcTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME,
            TaskDeliveryLog.class,
            () -> Mockito.mock(TaskDeliveryLog.class));
    context
        .registerBean(
            MongoTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME,
            TaskDeliveryLog.class,
            () -> Mockito.mock(TaskDeliveryLog.class));
    return context;

  }

  @Test
  @DisplayName("Without any outbox bean the resolver answers nothing instead of guessing")
  public void withoutAnyOutboxBeanTheResolverAnswersNothing() {

    try (var context = new AnnotationConfigApplicationContext()) {
      context.refresh();

      // the core turns this into its own message, listing the remedies of every
      // platform - the resolver must not anticipate it with a wrong store
      assertNull(new SpringPhaseTwoOutboxResolver(context).resolveFor(CustomlyPersistedAggregate.class));
    }

  }

  @Test
  @DisplayName("Two outbox defaults and an aggregate without repository fail naming both beans and the way out")
  public void twoOutboxDefaultsAndAnUndetectableAggregateFailGuiding() {

    try (var context = withBothOutboxDefaults()) {
      context.refresh();
      final var resolver = new SpringPhaseTwoOutboxResolver(context);

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> resolver.resolveFor(CustomlyPersistedAggregate.class));

      final var message = exception.getMessage();
      assertTrue(message.contains(GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME), message);
      assertTrue(message.contains(MongoPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME), message);
      assertTrue(message.contains(CustomlyPersistedAggregate.class.getName()), message);
      assertTrue(message.contains("UNKNOWN"), message);
      // the way out has to be nameable, not describable: the interface to implement
      // and the two properties switching the defaults
      assertTrue(message.contains("PhaseTwoOutboxAware"), message);
      assertTrue(message.contains("vanillabp.outbox.jdbc.enabled"), message);
      assertTrue(message.contains("vanillabp.outbox.mongo.enabled"), message);
    }

  }

  @Test
  @DisplayName("An outbox contributed for the aggregate wins over both platform defaults")
  public void anOutboxAwareBeanWinsOverTheDefaults() {

    final PhaseTwoOutbox applicationOutbox = call -> true;
    final var aware = new PhaseTwoOutboxAware<CustomlyPersistedAggregate>() {

      @Override
      public Class<CustomlyPersistedAggregate> getAggregateClass() {
        return CustomlyPersistedAggregate.class;
      }

      @Override
      public PhaseTwoOutbox getPhaseTwoOutbox() {
        return applicationOutbox;
      }

    };

    try (var context = withBothOutboxDefaults()) {
      context.registerBean("applicationOutboxAware", PhaseTwoOutboxAware.class, () -> aware);
      context.refresh();

      assertSame(
          applicationOutbox,
          new SpringPhaseTwoOutboxResolver(context).resolveFor(CustomlyPersistedAggregate.class));
    }

  }

  @Test
  @DisplayName("Two delivery-log defaults and an aggregate without repository fail naming both beans and the way out")
  public void twoDeliveryLogDefaultsAndAnUndetectableAggregateFailGuiding() {

    try (var context = withBothDeliveryLogDefaults()) {
      context.refresh();
      final var resolver = new SpringTaskDeliveryLogResolver(context);

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> resolver.resolveFor(CustomlyPersistedAggregate.class));

      final var message = exception.getMessage();
      assertTrue(message.contains(JdbcTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME), message);
      assertTrue(message.contains(MongoTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME), message);
      assertTrue(message.contains(CustomlyPersistedAggregate.class.getName()), message);
      assertTrue(message.contains("TaskDeliveryLogAware"), message);
    }

  }

  @Test
  @DisplayName("A delivery log contributed for the aggregate wins over both platform defaults")
  public void aDeliveryLogAwareBeanWinsOverTheDefaults() {

    final var applicationLog = Mockito.mock(TaskDeliveryLog.class);
    final var aware = new TaskDeliveryLogAware<CustomlyPersistedAggregate>() {

      @Override
      public Class<CustomlyPersistedAggregate> getAggregateClass() {
        return CustomlyPersistedAggregate.class;
      }

      @Override
      public TaskDeliveryLog getTaskDeliveryLog() {
        return applicationLog;
      }

    };

    try (var context = withBothDeliveryLogDefaults()) {
      context.registerBean("applicationDeliveryLogAware", TaskDeliveryLogAware.class, () -> aware);
      context.refresh();

      assertSame(
          applicationLog,
          new SpringTaskDeliveryLogResolver(context).resolveFor(CustomlyPersistedAggregate.class));
    }

  }

  @Test
  @DisplayName("A proxied store is reported by the class it proxies, not by the proxy")
  public void aProxiedStoreIsReportedByItsTargetClass() {

    // an application may put @Transactional on its own store; the proxy Spring builds
    // for it overrides EVERY method, the SPI defaults included - reflecting on the
    // proxy would report a release which does not exist
    final var proxied = (TaskDeliveryLog) new ProxyFactory(new ApplicationDeliveryLog()).getProxy();

    try (var context = new AnnotationConfigApplicationContext()) {
      context.refresh();

      assertEquals(
          ApplicationDeliveryLog.class,
          new SpringTaskDeliveryLogResolver(context).storeClassOf(proxied));
    }

  }

}
