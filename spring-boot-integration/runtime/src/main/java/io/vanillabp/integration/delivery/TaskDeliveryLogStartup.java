package io.vanillabp.integration.delivery;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Makes a default delivery log ready once all singletons exist. It creates the table or the
 * indexes, or checks them where the application manages its schema itself. Then it starts
 * the cleanup.
 * <p>
 * The startup checks of the process services read the delivery log as well. They compare
 * the identity of each BPMS with the one written down at an earlier start. Spring calls the
 * hooks of all singletons in the order in which the beans were defined, and that order says
 * nothing about which hook needs which. So the checks run this hook first themselves. It
 * does its work only once, whoever calls it first.
 */
public final class TaskDeliveryLogStartup implements SmartInitializingSingleton {

  private final Runnable startup;

  private final AtomicBoolean started = new AtomicBoolean();

  /**
   * Built by the auto-configuration of a default delivery log.
   *
   * @param startup What makes the delivery log ready
   */
  public TaskDeliveryLogStartup(
      final Runnable startup) {

    this.startup = startup;

  }

  @Override
  public void afterSingletonsInstantiated() {

    if (started.compareAndSet(false, true)) {
      startup.run();
    }

  }

}
