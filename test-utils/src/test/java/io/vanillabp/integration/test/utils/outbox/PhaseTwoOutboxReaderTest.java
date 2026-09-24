package io.vanillabp.integration.test.utils.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.GruelboxOutboxTable;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.OutboxTable;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.State;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.VanillaBpOutboxTable;

/**
 * The reader against a database in memory, once per outbox table it serves.
 * <p>
 * The tables are built here, in the shape the platform builds them, and they carry
 * names of this test. Which names the platform uses is not this test's question: the
 * reader takes them from the classes which declare them, and those are not on the
 * classpath of this module (see {@link PhaseTwoOutboxNames}).
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoOutboxReaderTest {

  private static final String OUTBOX_OF_THIS_TEST = "AN_OUTBOX";

  private static final String GRUELBOX_OF_THIS_TEST = "A_GRUELBOX_OUTBOX";

  private static final String PAYLOADS_OF_THIS_TEST = "THE_PAYLOADS";

  private static final String WAITING_STATE = "OPEN";

  private static final String DISPATCHED_STATE = "DONE";

  private static final String BLOCKED_STATE = "BLOCKED";

  /**
   * The two outbox tables VanillaBP writes into a relational database, each with what
   * this test needs to build one and to put an entry into it.
   */
  private enum Shape {

    VANILLABP(new VanillaBpOutboxTable(OUTBOX_OF_THIS_TEST, WAITING_STATE, DISPATCHED_STATE, BLOCKED_STATE)),

    GRUELBOX(new GruelboxOutboxTable(GRUELBOX_OF_THIS_TEST));

    private final OutboxTable table;

    Shape(
        final OutboxTable table) {

      this.table = table;

    }

    /**
     * Builds the outbox table with the columns the reader asks for.
     */
    private void createOutbox(
        final Connection connection) throws SQLException {

      final var ddl = this == VANILLABP
          ? """
              CREATE TABLE %s (\
              ID VARCHAR(36) PRIMARY KEY, \
              WORKFLOW_MODULE_ID VARCHAR(255), \
              BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
              OPERATION VARCHAR(255), \
              AGGREGATE_ID VARCHAR(1024), \
              IDEMPOTENCY_KEY VARCHAR(512), \
              DEDUP_KEY VARCHAR(512), \
              STATUS VARCHAR(16) NOT NULL, \
              CREATED_AT TIMESTAMP, \
              ATTEMPTS INT NOT NULL, \
              NEXT_ATTEMPT_AT TIMESTAMP, \
              LEASED_BY VARCHAR(255), \
              LEASED_UNTIL TIMESTAMP)"""
          : """
              CREATE TABLE %s (\
              id VARCHAR(36) PRIMARY KEY, \
              uniqueRequestId VARCHAR(250), \
              processed BOOLEAN NOT NULL, \
              blocked BOOLEAN NOT NULL, \
              attempts INT NOT NULL)""";
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(ddl.formatted(table.name()));
      }

    }

    /**
     * Writes one entry, the way the store of this shape writes it.
     *
     * @param connection The database in memory
     * @param id The entry's own id
     * @param bpmnProcessId The process the entry belongs to
     * @param state Where the entry stands
     * @param attempts How often a dispatch took it
     */
    private void writeEntry(
        final Connection connection,
        final String id,
        final String bpmnProcessId,
        final State state,
        final int attempts) throws SQLException {

      if (this == VANILLABP) {
        try (var insert = connection
            .prepareStatement(
                """
                    INSERT INTO %s (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, \
                    IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, ATTEMPTS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
                    .formatted(table.name()))) {
          insert.setString(1, id);
          insert.setString(2, "a-module");
          insert.setString(3, bpmnProcessId);
          insert.setString(4, "START_WORKFLOW");
          insert.setString(5, "an-aggregate");
          insert.setString(6, keyOf(id, bpmnProcessId));
          insert.setString(7, keyOf(id, bpmnProcessId));
          insert.setString(8, stateAsWritten(state));
          insert.setInt(9, attempts);
          insert.executeUpdate();
        }
        return;
      }
      try (var insert = connection
          .prepareStatement(
              """
                  INSERT INTO %s (id, uniqueRequestId, processed, blocked, attempts) \
                  VALUES (?, ?, ?, ?, ?)"""
                  .formatted(table.name()))) {
        insert.setString(1, id);
        // the key VanillaBP deduplicates by, which is where the gruelbox table carries
        // the process: operation, workflow module, process and aggregate, separated by
        // vertical bars
        insert.setString(2, keyOf(id, bpmnProcessId));
        insert.setBoolean(3, state == State.DISPATCHED);
        insert.setBoolean(4, state == State.BLOCKED);
        insert.setInt(5, attempts);
        insert.executeUpdate();
      }

    }

    /**
     * The key both stores deduplicate an entry by: operation, workflow module, process
     * and aggregate, separated by vertical bars. The entry's own id stands in for the
     * aggregate, so two entries of one process keep two keys.
     *
     * @param id The entry's own id
     * @param bpmnProcessId The process the entry belongs to
     * @return The key
     */
    private String keyOf(
        final String id,
        final String bpmnProcessId) {

      return "START_WORKFLOW|a-module|%s|%s".formatted(bpmnProcessId, id);

    }

    private String stateAsWritten(
        final State state) {

      return switch (state) {
        case WAITING -> WAITING_STATE;
        case DISPATCHED -> DISPATCHED_STATE;
        case BLOCKED -> BLOCKED_STATE;
      };

    }

  }

  private DataSource dataSource;

  @BeforeEach
  void aDatabaseOfItsOwn() {

    // the tables are not emptied between test methods, so every method gets a database
    // nobody else writes into
    final var database = new JdbcDataSource();
    database.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID()));
    dataSource = database;

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("An entry which waits for its dispatch is read as waiting, and it is counted")
  public void anEntryWhichWaitsIsReadAsWaiting(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAnEntry(shape, "the-entry", "AProcess", State.WAITING, 0);

    final var reader = readerOf(shape);

    assertEquals(1, reader.entries().size());
    assertEquals(State.WAITING, onlyEntryOf(reader).state());
    assertTrue(onlyEntryOf(reader).isWaiting());
    assertFalse(onlyEntryOf(reader).isBlocked());
    assertEquals(1, reader.entriesWaiting());

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("An entry which was put aside is blocked, and nothing counts it as waiting")
  public void anEntryWhichWasPutAsideIsBlocked(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAnEntry(shape, "the-entry", "AProcess", State.BLOCKED, 1);

    final var reader = readerOf(shape);

    assertEquals(State.BLOCKED, onlyEntryOf(reader).state());
    assertTrue(onlyEntryOf(reader).isBlocked());
    assertEquals(0, reader.entriesWaiting());

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("An entry which was dispatched stays in the table and waits no more")
  public void anEntryWhichWasDispatchedStaysInTheTable(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAnEntry(shape, "the-entry", "AProcess", State.DISPATCHED, 1);

    final var reader = readerOf(shape);

    assertEquals(1, reader.entries().size());
    assertTrue(onlyEntryOf(reader).wasDispatched());
    assertEquals(0, reader.entriesWaiting());

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("The attempts of an entry are read as the dispatcher counted them")
  public void theAttemptsOfAnEntryAreRead(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAnEntry(shape, "the-entry", "AProcess", State.WAITING, 3);

    assertEquals(3, onlyEntryOf(readerOf(shape)).attempts());

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("The entries of one process are found by the process, and no other entry is")
  public void theEntriesOfOneProcessAreFoundByTheProcess(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAnEntry(shape, "one", "AProcess", State.WAITING, 0);
    givenAnEntry(shape, "another", "AProcess", State.BLOCKED, 1);
    givenAnEntry(shape, "of-the-other-process", "AnotherProcess", State.WAITING, 0);

    final var entries = readerOf(shape).entriesOf("AProcess");

    assertEquals(2, entries.size());
    assertEquals(List.of("another", "one"), idsOf(entries));

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("Removing the entries of a process removes the payloads they left in the second table")
  public void removingTheEntriesOfAProcessRemovesItsPayloads(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAPayloadTable();
    givenAnEntry(shape, "the-entry", "AProcess", State.WAITING, 0);
    givenAnEntry(shape, "of-the-other-process", "AnotherProcess", State.WAITING, 0);
    givenAPayload("a-payload", "AProcess");
    givenAPayload("another-payload", "AnotherProcess");

    final var reader = readerOf(shape);
    reader.removeEntriesOf("AProcess");

    assertEquals(0, reader.entriesOf("AProcess").size());
    assertEquals(1, reader.entries().size());
    assertEquals(1, payloadsLeft(), "the payload of the process which stays has to stay with it");

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("Removing everything leaves both tables empty")
  public void removingEverythingLeavesBothTablesEmpty(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAPayloadTable();
    givenAnEntry(shape, "the-entry", "AProcess", State.WAITING, 0);
    givenAnEntry(shape, "of-the-other-process", "AnotherProcess", State.DISPATCHED, 1);
    givenAPayload("a-payload", "AProcess");
    givenAPayload("another-payload", "AnotherProcess");

    final var reader = readerOf(shape);
    reader.removeAllEntries();

    assertEquals(0, reader.entries().size());
    assertEquals(0, payloadsLeft());

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("An application whose calls carry no payload has no payload table, and cleaning up says nothing about it")
  public void anApplicationWithoutAPayloadTableIsCleanedUpAsWell(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);
    givenAnEntry(shape, "the-entry", "AProcess", State.WAITING, 0);

    final var reader = readerOf(shape);
    reader.removeEntriesOf("AProcess");
    reader.removeAllEntries();

    assertEquals(0, reader.entries().size());

  }

  @Test
  @DisplayName("What a call belongs to is read from the entry, and the gruelbox table answers what it can")
  public void whatACallBelongsToIsReadFromTheEntry() throws Exception {

    givenAnOutbox(Shape.VANILLABP);
    givenAnEntry(Shape.VANILLABP, "the-entry", "AProcess", State.WAITING, 0);
    givenAnOutbox(Shape.GRUELBOX);
    givenAnEntry(Shape.GRUELBOX, "the-entry", "AProcess", State.WAITING, 0);

    final var ofVanillaBp = onlyEntryOf(readerOf(Shape.VANILLABP));

    assertEquals("a-module", ofVanillaBp.workflowModuleId());
    assertEquals("AProcess", ofVanillaBp.bpmnProcessId());
    assertEquals("START_WORKFLOW", ofVanillaBp.operation());
    assertEquals("an-aggregate", ofVanillaBp.aggregateId());
    assertEquals("START_WORKFLOW|a-module|AProcess|the-entry", ofVanillaBp.idempotencyKey());

    final var ofGruelbox = onlyEntryOf(readerOf(Shape.GRUELBOX));

    assertNull(ofGruelbox.workflowModuleId(), "gruelbox serializes the call, so it holds no such column");
    assertNull(ofGruelbox.bpmnProcessId());
    assertNull(ofGruelbox.operation());
    assertNull(ofGruelbox.aggregateId());
    assertEquals("START_WORKFLOW|a-module|AProcess|the-entry", ofGruelbox.idempotencyKey());

  }

  @Test
  @DisplayName("The payloads waiting beside the entries are read with what they belong to")
  public void thePayloadsBesideTheEntriesAreRead() throws Exception {

    givenAnOutbox(Shape.VANILLABP);
    givenAPayloadTable();
    givenAPayload("a-payload", "AProcess");

    final var payloads = readerOf(Shape.VANILLABP).payloads();

    assertEquals(1, payloads.size());
    assertEquals("a-payload", payloads.getFirst().reference());
    assertEquals("a-module", payloads.getFirst().workflowModuleId());
    assertEquals("AProcess", payloads.getFirst().bpmnProcessId());
    assertEquals("START_WORKFLOW", payloads.getFirst().operation());

  }

  @Test
  @DisplayName("An application whose calls carry no payload reports no payloads either")
  public void anApplicationWithoutAPayloadTableReportsNoPayloads() throws Exception {

    givenAnOutbox(Shape.VANILLABP);

    assertEquals(List.of(), readerOf(Shape.VANILLABP).payloads());

  }

  @Test
  @DisplayName("An entry written by a test waits where it was put, and a test reads what it wrote")
  public void anEntryWrittenByATestWaitsWhereItWasPut() throws Exception {

    givenAnOutbox(Shape.VANILLABP);

    final var reader = readerOf(Shape.VANILLABP);
    reader
        .writeWaitingEntry(
            "written-by-the-test", "a-module", "AProcess", "START_WORKFLOW", "4711", Instant
                .now()
                .minusSeconds(600),
            Instant.now().plusSeconds(3600));

    final var entry = onlyEntryOf(reader);

    assertEquals("written-by-the-test", entry.id());
    assertTrue(entry.isWaiting());
    assertEquals(0, entry.attempts());
    assertEquals("4711", entry.aggregateId());

  }

  @Test
  @DisplayName("Every entry can be opened again, which is what an operator does to a blocked one")
  public void everyEntryCanBeOpenedAgain() throws Exception {

    givenAnOutbox(Shape.VANILLABP);
    givenAnEntry(Shape.VANILLABP, "the-entry", "AProcess", State.BLOCKED, 7);

    final var reader = readerOf(Shape.VANILLABP);
    reader.openEveryEntryAgain();

    assertTrue(onlyEntryOf(reader).isWaiting());
    assertEquals(0, onlyEntryOf(reader).attempts(), "a repaired entry starts its attempts over");

  }

  @Test
  @DisplayName("The entry of one key is made due without touching the entries beside it")
  public void theEntryOfOneKeyIsMadeDue() throws Exception {

    givenAnOutbox(Shape.VANILLABP);
    givenAnEntry(Shape.VANILLABP, "the-entry", "AProcess", State.WAITING, 1);

    final var reader = readerOf(Shape.VANILLABP);

    // what it does is a write, and what a test can read back is that it ran at all: the
    // moment of the next attempt is not one this reader reports
    reader.makeDueNow("START_WORKFLOW|a-module|AProcess|the-entry");

    assertEquals(1, reader.entries().size());

  }

  @Test
  @DisplayName("The gruelbox table says that a test cannot write it")
  public void theGruelboxTableSaysThatATestCannotWriteIt() throws Exception {

    givenAnOutbox(Shape.GRUELBOX);

    final var reader = readerOf(Shape.GRUELBOX);
    final var refused = assertThrows(UnsupportedOperationException.class, reader::openEveryEntryAgain);

    assertTrue(refused.getMessage().contains("gruelbox"), refused.getMessage());
    assertThrows(UnsupportedOperationException.class, () -> reader.makeDueNow("a-key"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> reader
            .writeWaitingEntry("an-entry", "a-module", "AProcess", "START_WORKFLOW", "4711", Instant.now(), Instant
                .now()));

  }

  @Test
  @DisplayName("A state the reader does not know is reported with the states it knows")
  public void aStateTheReaderDoesNotKnowIsReported() throws Exception {

    givenAnOutbox(Shape.VANILLABP);
    try (var connection = dataSource.getConnection(); var insert = connection
        .prepareStatement(
            "INSERT INTO %s (ID, BPMN_PROCESS_ID, STATUS, ATTEMPTS) VALUES ('an-entry', 'AProcess', 'SLEEPING', 0)"
                .formatted(OUTBOX_OF_THIS_TEST))) {
      // every other column is left out on purpose: what this case is about is the one
      // value the state column holds
      insert.executeUpdate();
    }

    final var reader = readerOf(Shape.VANILLABP);
    final var unknownState = assertThrows(IllegalStateException.class, reader::entries);

    assertTrue(unknownState.getMessage().contains("SLEEPING"), unknownState.getMessage());
    assertTrue(unknownState.getMessage().contains(WAITING_STATE), unknownState.getMessage());

  }

  @ParameterizedTest
  @EnumSource(Shape.class)
  @DisplayName("The outbox of an application is found by the table its database holds")
  public void theOutboxIsFoundByTheTableWhichIsThere(
      final Shape shape) throws Exception {

    givenAnOutbox(shape);

    final var tableFound = PhaseTwoOutboxReader
        .outboxTableOf(dataSource, OUTBOX_OF_THIS_TEST, Optional.of(GRUELBOX_OF_THIS_TEST));

    assertEquals(shape.table.name(), tableFound);

  }

  @Test
  @DisplayName("An application which cannot run the gruelbox outbox is served by the table it does write")
  public void anApplicationWhichCannotRunGruelboxIsServedByItsOwnTable() throws Exception {

    givenAnOutbox(Shape.VANILLABP);

    // what an adapter repository looks like: the VanillaBP class which configures the
    // gruelbox outbox is on its test classpath, the gruelbox library is not, so there is
    // no name of a gruelbox table to ask this database about
    final var tableFound = PhaseTwoOutboxReader.outboxTableOf(dataSource, OUTBOX_OF_THIS_TEST, Optional.empty());

    assertEquals(OUTBOX_OF_THIS_TEST, tableFound);

  }

  @Test
  @DisplayName("A database holding both tables asks the test which outbox its application runs")
  public void aDatabaseHoldingBothTablesAsksTheTest() throws Exception {

    givenAnOutbox(Shape.VANILLABP);
    givenAnOutbox(Shape.GRUELBOX);

    final var bothTables = assertThrows(
        IllegalStateException.class,
        () -> PhaseTwoOutboxReader
            .outboxTableOf(dataSource, OUTBOX_OF_THIS_TEST, Optional.of(GRUELBOX_OF_THIS_TEST)));

    assertTrue(bothTables.getMessage().contains(OUTBOX_OF_THIS_TEST), bothTables.getMessage());
    assertTrue(bothTables.getMessage().contains(GRUELBOX_OF_THIS_TEST), bothTables.getMessage());
    assertTrue(
        bothTables.getMessage().contains("ofTheGruelboxOutbox"),
        "the message has to say how the test answers the question: "
            + bothTables.getMessage());

  }

  @Test
  @DisplayName("A database holding neither table says which two were looked for")
  public void aDatabaseHoldingNeitherTableSaysWhichTwoWereLookedFor() {

    final var noTable = assertThrows(
        IllegalStateException.class,
        () -> PhaseTwoOutboxReader
            .outboxTableOf(dataSource, OUTBOX_OF_THIS_TEST, Optional.of(GRUELBOX_OF_THIS_TEST)));

    assertTrue(noTable.getMessage().contains(OUTBOX_OF_THIS_TEST), noTable.getMessage());
    assertTrue(noTable.getMessage().contains(GRUELBOX_OF_THIS_TEST), noTable.getMessage());

  }

  @Test
  @DisplayName("A database without a table says so although the gruelbox outbox was never an option")
  public void aDatabaseWithoutATableSaysSoWithoutAGruelboxName() {

    final var noTable = assertThrows(
        IllegalStateException.class,
        () -> PhaseTwoOutboxReader.outboxTableOf(dataSource, OUTBOX_OF_THIS_TEST, Optional.empty()));

    assertTrue(noTable.getMessage().contains(OUTBOX_OF_THIS_TEST), noTable.getMessage());
    assertTrue(
        noTable.getMessage().contains("gruelbox"),
        "the message has to say that the other outbox was out of the question: "
            + noTable.getMessage());

  }

  private PhaseTwoOutboxReader readerOf(
      final Shape shape) {

    return new PhaseTwoOutboxReader(dataSource, shape.table, PAYLOADS_OF_THIS_TEST);

  }

  private PhaseTwoOutboxReader.Entry onlyEntryOf(
      final PhaseTwoOutboxReader reader) {

    final var entries = reader.entries();
    assertEquals(1, entries.size(), "this case is about one entry");
    return entries.getFirst();

  }

  private List<String> idsOf(
      final List<PhaseTwoOutboxReader.Entry> entries) {

    return entries
        .stream()
        .map(PhaseTwoOutboxReader.Entry::id)
        .sorted()
        .toList();

  }

  private void givenAnOutbox(
      final Shape shape) throws SQLException {

    try (var connection = dataSource.getConnection()) {
      shape.createOutbox(connection);
    }

  }

  private void givenAnEntry(
      final Shape shape,
      final String id,
      final String bpmnProcessId,
      final State state,
      final int attempts) throws SQLException {

    try (var connection = dataSource.getConnection()) {
      shape.writeEntry(connection, id, bpmnProcessId, state, attempts);
    }

  }

  private void givenAPayloadTable() throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement
          .executeUpdate(
              """
                  CREATE TABLE %s (\
                  REFERENCE VARCHAR(36) PRIMARY KEY, \
                  WORKFLOW_MODULE_ID VARCHAR(255), \
                  BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
                  OPERATION VARCHAR(255), \
                  PAYLOAD BLOB NOT NULL)"""
                  .formatted(PAYLOADS_OF_THIS_TEST));
    }

  }

  private void givenAPayload(
      final String reference,
      final String bpmnProcessId) throws SQLException {

    try (var connection = dataSource.getConnection(); var insert = connection
        .prepareStatement(
            """
                INSERT INTO %s (REFERENCE, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, PAYLOAD) \
                VALUES (?, 'a-module', ?, 'START_WORKFLOW', ?)"""
                .formatted(PAYLOADS_OF_THIS_TEST))) {
      insert.setString(1, reference);
      insert.setString(2, bpmnProcessId);
      insert.setBytes(3, new byte[]{
          1, 2, 3
      });
      insert.executeUpdate();
    }

  }

  private long payloadsLeft() throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var results = statement
            .executeQuery("SELECT COUNT(*) FROM %s".formatted(PAYLOADS_OF_THIS_TEST))) {
      results.next();
      return results.getLong(1);
    }

  }

}
