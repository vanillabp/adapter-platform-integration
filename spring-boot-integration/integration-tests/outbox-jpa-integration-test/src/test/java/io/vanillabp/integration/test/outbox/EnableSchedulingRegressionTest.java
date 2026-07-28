package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Regression test for the removed <code>vanillaBpOutboxTaskScheduler</code> beans:
 * the outbox dispatchers run on private executors, so an application using
 * <code>&#64;EnableScheduling</code> gets Spring Boot's own {@link TaskScheduler}
 * (bean <code>taskScheduler</code>) - VanillaBP must not register (and thereby
 * suppress or hijack) any {@link TaskScheduler} bean even with the outbox on the
 * classpath.
 */
@SpringBootTest(
    classes = {
        TestApplication.class, EnableSchedulingRegressionTest.SchedulingConfiguration.class
    },
    // own H2 database: this context's outbox poller must not steal dispatches from
    // the entries written by the other test contexts sharing the default database
    properties = "spring.datasource.url=jdbc:h2:mem:outbox-scheduling-it;DB_CLOSE_DELAY=-1")
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class EnableSchedulingRegressionTest {

  @Configuration
  @EnableScheduling
  static class SchedulingConfiguration {
  }

  @Autowired
  private ApplicationContext context;

  @Test
  @DisplayName("No VanillaBP TaskScheduler bean exists and Boot's own scheduling stays intact")
  public void noVanillaBpTaskSchedulerBean() {

    assertFalse(
        context.containsBean("vanillaBpOutboxTaskScheduler"),
        "VanillaBP must not register a TaskScheduler bean");

    // Spring Boot's task-scheduling auto-configuration has to provide its own
    // scheduler for @EnableScheduling since VanillaBP no longer occupies the type
    assertTrue(
        context.containsBean("taskScheduler"),
        "Spring Boot's own taskScheduler bean is expected with @EnableScheduling");
    assertFalse(context.getBeansOfType(TaskScheduler.class).isEmpty());

  }

}
