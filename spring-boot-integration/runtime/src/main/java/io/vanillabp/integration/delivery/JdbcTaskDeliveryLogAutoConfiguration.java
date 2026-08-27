package io.vanillabp.integration.delivery;

import javax.sql.DataSource;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * Auto-configuration of the default {@link TaskDeliveryLog} for JPA/JDBC-based aggregate
 * persistence. Active whenever a {@link DataSource} and a transaction manager exist - it
 * COEXISTS with the MongoDB default ({@link MongoTaskDeliveryLogAutoConfiguration}):
 * each workflow aggregate is served by the log matching its persistence (selection per
 * aggregate, see {@link io.vanillabp.integration.spi.TaskDeliveryLogAware}), so a
 * delivery record always rides the aggregate's own transaction even in mixed-persistence
 * applications.
 * <p>
 * The store settings are the outbox' ones, although the retention means something
 * different here - for a record it decides whether a late redelivery runs the business
 * code again, while for a dispatched outbox entry it only decides how long support can
 * read it:
 * <code>vanillabp.outbox.create-schema</code> decides whether VanillaBP creates the
 * table {@value io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore#DEFAULT_TABLE_NAME},
 * <code>vanillabp.outbox.retention</code> how long a record is kept, and
 * <code>vanillabp.outbox.jdbc.enabled</code> switches the default off for an application
 * bringing its own {@link TaskDeliveryLog} bean.
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
   * The name of the default JDBC delivery-log bean - used by the resolver to attribute
   * JPA-persisted aggregates to THE default when several log beans exist.
   */
  public static final String DEFAULT_DELIVERY_LOG_BEAN_NAME = "vanillaBpJdbcTaskDeliveryLog";

  /**
   * @param dataSource The data source holding the records
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section
   * @return The {@link TaskDeliveryLog} used for JPA/JDBC-persisted aggregates
   */
  @Bean(name = DEFAULT_DELIVERY_LOG_BEAN_NAME, destroyMethod = "stop")
  public JdbcTaskDeliveryLog vanillaBpJdbcTaskDeliveryLog(
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new JdbcTaskDeliveryLog(
        dataSource, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME, vanillaBpProperties
            .getOutbox()
            .getRetention());

  }

  /**
   * Creates the table and starts the retention cleanup once all singletons exist - the
   * DDL must not run mid-bean-construction (it would materialize the data source before
   * the application's own configuration is complete).
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
