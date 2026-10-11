package io.vanillabp.integration.delivery;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.integration.adapter.migration.delivery.AggregateBoundWorkflowStarts;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * Auto-configuration of the default {@link TaskDeliveryLog} for aggregates persisted with
 * JPA or JDBC. It is active whenever a {@link DataSource} and a transaction manager exist.
 * It lives SIDE BY SIDE with the MongoDB default
 * ({@link MongoTaskDeliveryLogAutoConfiguration}). Each workflow aggregate is served by
 * the log which matches its persistence. The choice is made per aggregate, see
 * {@link io.vanillabp.integration.spi.TaskDeliveryLogAware}. So a delivery record always
 * rides the aggregate's own transaction, also in applications with mixed persistence.
 * <p>
 * The store settings are the ones of the outbox, but the retention is not. The two mean
 * different things. For a record, the retention decides whether a late redelivery runs
 * the business code again. For a dispatched outbox entry, it only decides how long support
 * can read it. <code>vanillabp.outbox.create-schema</code> decides whether VanillaBP
 * creates the table. <code>vanillabp.outbox.jdbc.delivery-table</code> gives it a name of
 * the application's own
 * ({@value io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore#DEFAULT_TABLE_NAME}
 * where nobody names it). <code>vanillabp.delivery.retention</code> says how long a record
 * is kept. It falls back to <code>vanillabp.outbox.retention</code>, where it lived before
 * the two were separated. <code>vanillabp.outbox.jdbc.enabled</code> switches the default
 * off for an application which brings its own {@link TaskDeliveryLog} bean.
 */
@AutoConfiguration(
    afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration", "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration", "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnBean({
    DataSource.class, PlatformTransactionManager.class
})
@ConditionalOnBooleanProperty(name = "vanillabp.outbox.jdbc.enabled", matchIfMissing = true)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
public class JdbcTaskDeliveryLogAutoConfiguration {

  /**
   * The name of the default JDBC delivery-log bean. When several log beans exist, the
   * resolver uses it to assign aggregates persisted with JPA to THE default.
   */
  public static final String DEFAULT_DELIVERY_LOG_BEAN_NAME = "vanillaBpJdbcTaskDeliveryLog";

  /**
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. Nothing in VanillaBP builds it.
   */
  public JdbcTaskDeliveryLogAutoConfiguration() {
  }

  /**
   * The delivery log of every workflow aggregate this application persists in its
   * relational database. Spring builds it where a {@link DataSource} and a transaction
   * manager exist and <code>vanillabp.outbox.jdbc.enabled</code> is not <code>false</code>.
   * The other default beside it serves an aggregate which lives in MongoDB.
   *
   * @param dataSource The data source holding the records
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree. It is asked for the
   *          retention of delivery records (<code>vanillabp.delivery.retention</code>,
   *          falling back to <code>vanillabp.outbox.retention</code>), and for the retention
   *          of the rows about started workflows
   * @param phaseTwoRouter Where the process services are collected. The sieve of the
   *          workflow-start rows asks it, and only where the application switched that
   *          sieve on. It is a provider and not the bean itself, because the router is built
   *          from the process-service beans. This log must not pull them in while it is
   *          created
   * @return The {@link TaskDeliveryLog} used for JPA/JDBC-persisted aggregates
   */
  @Bean(name = DEFAULT_DELIVERY_LOG_BEAN_NAME, destroyMethod = "stop")
  public JdbcTaskDeliveryLog vanillaBpJdbcTaskDeliveryLog(
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter) {

    final var tableName = JdbcTaskDeliveryStore.tableName(vanillaBpProperties.getOutbox());
    return new JdbcTaskDeliveryLog(
        dataSource, tableName, vanillaBpProperties.resolvedDeliveryRetention(), vanillaBpProperties
            .resolvedWorkflowStartRetention(), vanillaBpProperties
                .keepsWorkflowStartWhileAggregateExists()
                    ? new AggregateBoundWorkflowStarts(
                        () -> phaseTwoRouter.getIfAvailable(), tableName)
                    : null);

  }

  /**
   * Creates the table and starts the retention cleanup once all singletons exist. The DDL
   * must not run while beans are still being built. It would create the data source before
   * the application's own configuration is complete.
   *
   * @param deliveryLog The delivery log to start
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree
   * @return The startup hook
   */
  @Bean
  public SmartInitializingSingleton vanillaBpJdbcTaskDeliveryLogStartup(
      final JdbcTaskDeliveryLog deliveryLog,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return () -> deliveryLog.start(
        vanillaBpProperties
            .getOutbox()
            .isCreateSchema());

  }

}
