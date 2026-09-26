package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an adapter gets when it asks this platform where to report what it found at
 * startup.
 * <p>
 * It has to be the collection the checks of the core report into, or the application
 * would write two blocks: the promise of one block per start is what this holds.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheStartupReportOfAnAdapterTest {

  @Test
  @DisplayName("The bean an adapter asks for is where the core reports too")
  public void theBeanIsTheCollectionOfTheStart() {

    final var properties = new MigrationAdapterProperties();

    final var report = SpringBootMigrationAdapterAutoConfiguration.vanillaBpStartupReport(properties);

    assertSame(properties.startupFindings(), report, "one collection, so one block");

    report.warn(StartupTopic.CONFIGURATION, "camunda8 adapter 'cloud'", "The request timeout is half a second.");
    final var box = properties.startupFindings().theBox();
    assertTrue(box.contains("camunda8 adapter 'cloud'"), box);

  }

}
