package io.vanillabp.integration.test.apptx;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The application's own log of processed task deliveries.
 */
@ApplicationScoped
public class AppTxDeliveryLog implements TaskDeliveryLog {

  private final Map<String, TaskDelivery> deliveries = new ConcurrentHashMap<>();

  public int size() {

    return deliveries.size();

  }

  public void clear() {

    deliveries.clear();

  }

  @Override
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    return Optional.ofNullable(deliveries.get(deliveryKey));

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    return deliveries.putIfAbsent(delivery.deliveryKey(), delivery) == null;

  }

}
