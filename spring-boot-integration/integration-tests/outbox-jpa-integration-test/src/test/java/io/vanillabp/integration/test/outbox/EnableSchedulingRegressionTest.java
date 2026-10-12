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
 * Regression test for the removed <code>vanillaBpOutboxTaskScheduler</code> beans. The
 * outbox dispatchers run on private executors. So an application which uses
 * <code>&#64;EnableScheduling</code> gets Spring Boot's own {@link TaskScheduler} (bean
 * <code>taskScheduler</code>). VanillaBP must not register any {@link TaskScheduler}
 * bean, even with the outbox on the classpath. Such a bean would replace or take over
 * the scheduler of the application.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = {
        TestApplication.class, EnableSchedulingRegressionTest.SchedulingConfiguration.class
    },
    // its own H2 database: the outbox poller of this context must not take the entries
    // which the other test contexts write into the shared default database
    properties = "spring.datasource.url=jdbc:h2:mem:outbox-scheduling-it;DB_CLOSE_DELAY=-1")
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

    // VanillaBP no longer takes the type. So the task-scheduling auto-configuration of
    // Spring Boot must provide its own scheduler for @EnableScheduling
    assertTrue(
        context.containsBean("taskScheduler"),
        "Spring Boot's own taskScheduler bean is expected with @EnableScheduling");
    assertFalse(context.getBeansOfType(TaskScheduler.class).isEmpty());

  }

}
