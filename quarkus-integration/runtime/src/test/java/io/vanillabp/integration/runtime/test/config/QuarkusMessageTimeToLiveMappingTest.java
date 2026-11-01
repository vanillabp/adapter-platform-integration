package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The <code>message-time-to-live</code> of every level arrives in the core, where the adapters
 * read it. It is bound by SmallRye from real keys here, because the message level is a map of
 * its own which no other key uses. Plain SmallRye reads a duration in ISO-8601 only. Quarkus
 * reads <code>10m</code> as well.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusMessageTimeToLiveMappingTest {

  @Test
  @DisplayName("The message time-to-live travels through every level, and the message wins")
  public void theTimeToLiveTravelsThroughEveryLevel() {

    final var core = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(mapped(Map.of(
        "vanillabp.adapters.c7.message-time-to-live", "PT1M",
        "vanillabp.workflow-modules.loans.adapters.c7.message-time-to-live", "PT2M",
        "vanillabp.workflow-modules.loans.workflows.LoanApproval.adapters.c7.message-time-to-live", "PT3M",
        "vanillabp.workflow-modules.loans.workflows.LoanApproval.messages.ContractSigned.adapters.c7.message-time-to-live",
        "PT4M")));

    assertEquals(Duration.ofMinutes(1), core.messageTimeToLive("payments", "Payment", "Paid", "c7"));
    assertEquals(Duration.ofMinutes(2), core.messageTimeToLive("loans", "OtherProcess", "ContractSigned", "c7"));
    assertEquals(Duration.ofMinutes(3), core.messageTimeToLive("loans", "LoanApproval", "OtherMessage", "c7"));
    assertEquals(Duration.ofMinutes(4), core.messageTimeToLive("loans", "LoanApproval", "ContractSigned", "c7"));
    assertNull(core.messageTimeToLive("loans", "LoanApproval", "ContractSigned", "c8"));

  }

  private static QuarkusMigrationAdapterProperties mapped(
      final Map<String, String> properties) {

    return new SmallRyeConfigBuilder()
        .withSources(new PropertiesConfigSource(properties, "test", 500))
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .build()
        .getConfigMapping(QuarkusMigrationAdapterProperties.class);

  }

}
