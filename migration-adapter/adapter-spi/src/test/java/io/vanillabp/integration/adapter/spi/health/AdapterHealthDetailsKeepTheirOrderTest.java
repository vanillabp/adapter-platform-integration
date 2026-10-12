package io.vanillabp.integration.adapter.spi.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter writes its details in the order a reader of the health endpoint needs them:
 * first what was asked, then what answered, then the rest. This order used to get lost
 * when the details were closed. The endpoint then showed them in whatever order the map
 * had picked.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdapterHealthDetailsKeepTheirOrderTest {

  @Test
  @DisplayName("The details come back in the order the adapter added them")
  public void theDetailsKeepTheOrderTheyWereAddedIn() {

    final var details = AdapterHealth
        .detailsBuilder()
        .with("adapterId", "loan-approval-c8")
        .with("address", "https://zeebe.example.com:26500")
        .with("gatewayVersion", "8.8.1")
        .with("nothingToShow", null)
        .build();

    assertEquals(
        List.of("adapterId", "address", "gatewayVersion"),
        List.copyOf(details.keySet()));

  }

  @Test
  @DisplayName("What the endpoint is given cannot be changed afterwards")
  public void theDetailsCannotBeChangedAfterwards() {

    final var details = AdapterHealth
        .detailsBuilder()
        .with("address", "https://zeebe.example.com:26500")
        .build();

    assertThrows(UnsupportedOperationException.class, () -> details.put("address", "somewhere else"));

  }

}
