package io.vanillabp.loadharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The counting driver sees every statement, hands everything else to the real driver, and
 * gives each statement to the thread which ran it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CountingDriverTest {

  private static final String URL = "jdbc:counting:h2:mem:counting-driver;DB_CLOSE_DELAY=-1";

  private static Connection connect() throws SQLException {

    return DriverManager.getConnection(URL, "sa", "");

  }

  @Test
  @DisplayName("Every kind of statement counts once per execution")
  public void everyExecutionCounts() throws Exception {

    try (var connection = connect()) {
      final var counted = StatementCounter.count(() -> {
        try (var plain = connection.createStatement()) {
          plain.execute("CREATE TABLE IF NOT EXISTS COUNTED (ID INT)");
          try (var prepared = connection.prepareStatement("INSERT INTO COUNTED VALUES (?)")) {
            prepared.setInt(1, 1);
            prepared.executeUpdate();
            prepared.setInt(1, 2);
            prepared.addBatch();
            prepared.executeBatch();
          }
          try (var query = connection.prepareStatement("SELECT COUNT(*) FROM COUNTED"); var rows = query
              .executeQuery()) {
            assertTrue(rows.next());
            return rows.getLong(1);
          }
        } catch (SQLException e) {
          throw new IllegalStateException(e);
        }
      });
      assertEquals(2L, counted.result());
      assertEquals(4, counted.statements(), "create, insert, batch and select");
    }

  }

  @Test
  @DisplayName("A statement on another thread does not count for the caller")
  public void anotherThreadIsNotCounted() throws Exception {

    final var other = Executors.newSingleThreadExecutor();
    try (var connection = connect()) {
      final var counted = StatementCounter.count(() -> {
        try {
          other.submit(() -> {
            try (var statement = connection.createStatement()) {
              statement.execute("SELECT 1");
            }
            return null;
          }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
        return null;
      });
      assertEquals(0, counted.statements());
    } finally {
      other.shutdownNow();
    }

  }

  @Test
  @DisplayName("The metadata is the real database's, so VanillaBP sees what it runs on")
  public void theMetadataIsTheRealOne() throws Exception {

    try (var connection = connect()) {
      assertEquals("H2", connection.getMetaData().getDatabaseProductName());
    }
    final var driver = new CountingDriver();
    assertTrue(driver.acceptsURL(URL));
    assertFalse(driver.acceptsURL("jdbc:h2:mem:plain"));
    assertEquals("jdbc:h2:mem:x", CountingDriver.realUrl("jdbc:counting:h2:mem:x"));

  }

  @Test
  @DisplayName("A failing statement reports the exception of the real driver")
  public void theRealExceptionArrives() throws Exception {

    try (var connection = connect(); var statement = connection.createStatement()) {
      final var failure = org.junit.jupiter.api.Assertions
          .assertThrows(SQLException.class, () -> statement.execute("SELECT * FROM NO_SUCH_TABLE"));
      assertTrue(failure.getMessage().contains("NO_SUCH_TABLE"), failure.getMessage());
    }

  }

}
