package io.vanillabp.integration.test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.inject.Singleton;

/**
 * Counts how often the application took a connection out of the pool. So a test can claim
 * that the application did not touch its database at all.
 * <p>
 * Every statement needs a connection, so counting connections makes the claim from below.
 * Zero connections taken means zero statements sent, whatever the code would have sent on
 * them. A test which measures TIME cannot make that claim. A poller which sleeps and a
 * poller which asks every second both look like "the workflow went through".
 * <p>
 * The count is static, because this is a bean of the application under test, and the test
 * which reads it lives outside.
 */
@Singleton
public class CountingPoolInterceptor implements AgroalPoolInterceptor {

  private static final AtomicLong ACQUIRED = new AtomicLong();

  @Override
  public void onConnectionAcquire(
      final Connection connection) {

    ACQUIRED.incrementAndGet();

  }

  /**
   * @return How many connections were taken since the last {@link #forgetWhatWasAcquired()}
   */
  public static long acquired() {

    return ACQUIRED.get();

  }

  public static void forgetWhatWasAcquired() {

    ACQUIRED.set(0);

  }

}
