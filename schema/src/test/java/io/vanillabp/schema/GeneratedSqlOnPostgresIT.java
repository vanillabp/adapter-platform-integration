package io.vanillabp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The SQL generated for PostgreSQL, applied to a real PostgreSQL the way a Flyway migration would
 * apply it: statement by statement, without Liquibase anywhere. This is the second of the two
 * databases this module promises, and the test which proves that a generated file is not only
 * syntactically plausible but accepted by the server.
 */
// the extension comes FIRST on purpose: JUnit registers declarative extensions in the
// order they are written, and the Testcontainers extension starts the container in its
// own beforeAll. Written the other way round, the Docker client had already logged 2208
// debug lines through an appender holding the real stdout before anything could capture
// them - a green build carrying the whole conversation with the Docker daemon.
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers
public class GeneratedSqlOnPostgresIT {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /**
   * The generated file as it lands in the artifact: what an application gets is the copy inside the
   * JAR, so that is what this test reads.
   */
  private static String generatedSql() throws Exception {

    final var path = Path
        .of(
            System.getProperty("vanillabp.schema.generated", "target/classes"),
            "vanillabp/schema/flyway/postgresql/V2.0.0__vanillabp_schema.sql");
    assertTrue(Files.exists(path), "generated file is missing: "
        + path.toAbsolutePath());
    return Files.readString(path);

  }

  private static Set<String> tablesOf(
      final Connection connection) throws Exception {

    final var tables = new LinkedHashSet<String>();
    try (var resultSet = connection
        .getMetaData()
        .getTables(null, "public", "%", new String[]{
            "TABLE"
        })) {
      while (resultSet.next()) {
        tables.add(resultSet.getString("TABLE_NAME").toUpperCase());
      }
    }
    return tables;

  }

  @Test
  @DisplayName("PostgreSQL accepts the generated SQL and ends up with both tables")
  public void postgresAcceptsTheGeneratedSql() throws Exception {

    try (var connection = DriverManager
        .getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

      // the way Flyway runs a migration: the statements of the file, in order. Comment lines are
      // dropped first - they carry apostrophes of prose, which a naive split would take for string
      // literals (Flyway's own parser knows the difference)
      final var withoutComments = generatedSql()
          .lines()
          .filter(line -> !line.stripLeading().startsWith("--"))
          .collect(java.util.stream.Collectors.joining("\n"));
      for (final var statement : withoutComments.split(";")) {
        final var sql = statement.strip();
        if (sql.isEmpty()) {
          continue;
        }
        try (var jdbcStatement = connection.createStatement()) {
          jdbcStatement.execute(sql);
        }
      }

      final var tables = tablesOf(connection);
      assertTrue(tables.contains("VANILLABP_PHASE_TWO_OUTBOX"), tables.toString());
      assertTrue(tables.contains("VANILLABP_TASK_DELIVERY"), tables.toString());

      // and the constraint the outbox relies on is really there
      try (var jdbcStatement = connection.createStatement()) {
        jdbcStatement
            .executeUpdate(
                """
                    INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
                    (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, IDEMPOTENCY_KEY, DEDUP_KEY, \
                    STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) VALUES \
                    ('1', 'module', 'Process', 'START', 'key-1', 'key-1', 'OPEN', now(), 0, now())""");
      }
      final var duplicate = org.junit.jupiter.api.Assertions
          .assertThrows(
              java.sql.SQLException.class,
              () -> {
                try (var jdbcStatement = connection.createStatement()) {
                  jdbcStatement
                      .executeUpdate(
                          """
                              INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
                              (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, IDEMPOTENCY_KEY, \
                              DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) VALUES \
                              ('2', 'module', 'Process', 'START', 'key-1', 'key-1', 'OPEN', now(), 0, now())""");
                }
              });
      assertEquals("23505", duplicate.getSQLState(), duplicate.getMessage());

    }

  }

}
