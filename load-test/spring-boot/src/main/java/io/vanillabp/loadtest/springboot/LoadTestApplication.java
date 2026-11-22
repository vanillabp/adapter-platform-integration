package io.vanillabp.loadtest.springboot;

import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.loadharness.ConnectionPoolProbe;
import io.vanillabp.loadharness.LoadAggregate;
import io.vanillabp.loadharness.LoadAggregates;
import io.vanillabp.loadharness.LoadApplication;
import io.vanillabp.loadharness.LoadDriver;
import io.vanillabp.loadharness.LoadProcess;
import io.vanillabp.loadharness.RoundSettings;
import io.vanillabp.spi.process.ProcessService;

/**
 * The Spring Boot application of the load run. It starts, runs one round when
 * <code>load.output</code> is set, writes the numbers and ends. Everything it measures is the
 * same on Quarkus. Only the few lines which reach the platform are written here.
 */
@SpringBootApplication
public class LoadTestApplication {

  /**
   * The id of the double, as <code>application.yaml</code> configures it.
   */
  static final String ADAPTER_ID = "load";

  /**
   * Starts the application, runs a round and ends.
   *
   * @param args The command line
   */
  public static void main(
      final String[] args) throws java.io.IOException {

    final var context = SpringApplication.run(LoadTestApplication.class, args);
    runRound(context);
    System.exit(SpringApplication.exit(context));

  }

  /**
   * Runs the round of a started application. It runs after the start and not inside it,
   * because VanillaBP dispatches its outbox only once the application is ready.
   *
   * @param context The started application
   * @return Whether a round ran, which it does only where <code>load.output</code> is set
   * @throws java.io.IOException If the numbers cannot be written
   */
  static boolean runRound(
      final ConfigurableApplicationContext context) throws java.io.IOException {

    final var environment = context.getEnvironment();
    return RoundSettings
        .from(key -> Optional.ofNullable(environment.getProperty(key)))
        .runAndWrite(context.getBean(LoadApplication.class), context.getBean(LoadDriver.class));

  }

  @Bean
  LoadAggregates loadAggregates() {

    return new LoadAggregates();

  }

  @Bean
  LoadDriver loadDriver(
      final LoadAggregates aggregates) {

    return new LoadDriver(aggregates);

  }

  @Bean
  LoadProcess loadProcess(
      final Environment environment) {

    return new LoadProcess(environment.getProperty("load.service-tasks", Integer.class, 3));

  }

  @Bean
  SimpleMeterRegistry loadMeterRegistry() {

    return new SimpleMeterRegistry();

  }

  @Bean
  LoadApplication loadApplication(
      final TransactionTemplate transactions,
      final ProcessService<LoadAggregate> processService,
      @Qualifier("DummyAdapter_DeploymentService_"
          + ADAPTER_ID) final DummyDeploymentService bpms,
      final MeterRegistry meters,
      final ObjectProvider<MeterBinder> binders,
      final DataSource dataSource) {

    // without the Actuator nobody hands the meter binders of VanillaBP a registry, so the
    // application does it here
    binders.orderedStream().forEach(binder -> binder.bindTo(meters));

    final ConnectionPoolProbe pool = dataSource instanceof HikariDataSource hikari
        ? new ConnectionPoolProbe() {

          @Override
          public int active() {

            final var bean = hikari.getHikariPoolMXBean();
            return bean == null
                ? 0
                : bean.getActiveConnections();

          }

          @Override
          public int pending() {

            final var bean = hikari.getHikariPoolMXBean();
            return bean == null
                ? 0
                : bean.getThreadsAwaitingConnection();

          }

        }
        : ConnectionPoolProbe.NONE;
    return new LoadApplication() {

      @Override
      public <T> T inTransaction(
          final Supplier<T> work) {

        return transactions.execute(status -> work.get());

      }

      @Override
      public ProcessService<LoadAggregate> processService() {

        return processService;

      }

      @Override
      public DummyDeploymentService bpms() {

        return bpms;

      }

      @Override
      public String workflowModuleId() {

        return "load";

      }

      @Override
      public MeterRegistry meters() {

        return meters;

      }

      @Override
      public ConnectionPoolProbe connectionPool() {

        return pool;

      }

    };

  }

}
