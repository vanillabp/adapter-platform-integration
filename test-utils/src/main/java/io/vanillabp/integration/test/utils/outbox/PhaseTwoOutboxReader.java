package io.vanillabp.integration.test.utils.outbox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * What a test wants to know about the phase-two outbox, read from the database of the
 * application under test.
 * <p>
 * A test asks for entries, their state and their attempts. It does not ask for columns,
 * and it never writes a table name down: the names come from the platform classes which
 * declare them (see {@link PhaseTwoOutboxNames}), so a rename of the platform is
 * followed in one file instead of in every repository which tests against a BPMS.
 * <p>
 * VanillaBP writes two outbox tables into a relational database, and this reader serves
 * both. A Spring Boot application with JPA runs the outbox of the gruelbox library; a
 * Quarkus application, and a Spring Boot application without JPA, run the table
 * VanillaBP writes itself. The two keep the state of an entry in different columns,
 * which is why the reader reports it as {@link State} rather than as the value a column
 * holds.
 * <p>
 * The reader uses a connection of its own, outside the transaction of the test. So it
 * reads what is committed, which is what a test about an outbox asks about anyway: an
 * entry becomes visible when the transaction which wrote it commits.
 * <p>
 * Removing entries removes the payloads beside them. An entry which carries a payload
 * keeps it in a table of its own, and a test which took the entry away without the
 * payload would leave a row nothing points at any more (see
 * {@code PhaseTwoOutboxReaderTest#removingTheEntriesOfAProcessRemovesItsPayloads}).
 */
public final class PhaseTwoOutboxReader {

  /**
   * Where an entry stands. Both outbox tables know these three states, each in a
   * spelling of its own, and a test reads them in this one.
   */
  public enum State {

    /**
     * The entry is waiting for its dispatch, or for the next attempt of it.
     */
    WAITING,

    /**
     * The entry was dispatched. It stays in the table until the retention passes.
     */
    DISPATCHED,

    /**
     * The entry was put aside after too many failed attempts, or after one attempt
     * whose failure would not be fixed by repeating it. Nothing attempts it again, a
     * person has to.
     */
    BLOCKED

  }

  /**
   * One entry of the outbox, with what a test asks about it.
   *
   * @param id The entry's own id, as the outbox table holds it
   * @param state Where the entry stands
   * @param attempts How often a dispatch took the entry - counted when the dispatch
   *          claims it, so an entry being dispatched right now already carries one
   */
  public record Entry(String id, State state, int attempts) {

    /**
     * Tells whether the entry still has its dispatch before it.
     *
     * @return Whether the entry is waiting for its dispatch
     */
    public boolean isWaiting() {

      return state == State.WAITING;

    }

    /**
     * Tells whether the outbox gave up on the entry.
     *
     * @return Whether the entry was put aside and waits for a person
     */
    public boolean isBlocked() {

      return state == State.BLOCKED;

    }

    /**
     * Tells whether the entry reached the BPMS.
     *
     * @return Whether the entry was dispatched
     */
    public boolean wasDispatched() {

      return state == State.DISPATCHED;

    }

  }

  /**
   * One of the two outbox tables, asked the questions which differ between them.
   */
  sealed interface OutboxTable permits VanillaBpOutboxTable, GruelboxOutboxTable {

    /**
     * @return The table this outbox writes its entries into
     */
    String name();

    /**
     * @return The statement reading every entry, in the columns {@link #entryOf}
     *         expects
     */
    String selectEntries();

    /**
     * @return What narrows a statement down to the entries of one BPMN process,
     *         including the leading <code>WHERE</code> and one parameter
     */
    String whereTheProcessIs();

    /**
     * @param bpmnProcessId The process asked about
     * @return What to bind to the parameter of {@link #whereTheProcessIs()}
     */
    String whatMatchesTheProcess(
        String bpmnProcessId);

    /**
     * @param results The row read by {@link #selectEntries()}
     * @return What the row says about the entry
     */
    Entry entryOf(
        ResultSet results) throws SQLException;

  }

  /**
   * The table VanillaBP writes itself. It holds the state in one column and the BPMN
   * process in another, so both questions are answered by the columns they are about.
   *
   * @param name The table
   * @param waiting What the state column holds while the entry waits
   * @param dispatched What it holds once the entry was dispatched
   * @param blocked What it holds once the entry was put aside
   */
  record VanillaBpOutboxTable(String name, String waiting, String dispatched, String blocked) implements OutboxTable {

    @Override
    public String selectEntries() {

      return "SELECT ID, STATUS, ATTEMPTS FROM %s".formatted(name);

    }

    @Override
    public String whereTheProcessIs() {

      return " WHERE BPMN_PROCESS_ID = ?";

    }

    @Override
    public String whatMatchesTheProcess(
        final String bpmnProcessId) {

      return bpmnProcessId;

    }

    @Override
    public Entry entryOf(
        final ResultSet results) throws SQLException {

      final var state = results.getString(2);
      return new Entry(results.getString(1), stateOf(state), results.getInt(3));

    }

    /**
     * @param state What the state column holds
     * @return The state a test reads
     * @throws IllegalStateException If the column holds something this reader does not
     *         know - which is a state the platform added without telling this class
     */
    private State stateOf(
        final String state) {

      if (waiting.equals(state)) {
        return State.WAITING;
      }
      if (dispatched.equals(state)) {
        return State.DISPATCHED;
      }
      if (blocked.equals(state)) {
        return State.BLOCKED;
      }
      throw new IllegalStateException(
          """
              The outbox table '%s' holds an entry in the state '%s', which this reader does not \
              know! It knows '%s', '%s' and '%s'."""
              .formatted(name, state, waiting, dispatched, blocked));

    }

  }

  /**
   * The table of the gruelbox library, which a Spring Boot application with JPA runs.
   * It keeps the state in two flags rather than in one column, and it does not hold the
   * BPMN process in a column at all: gruelbox stores a call as a serialized invocation.
   * So the entries of one process are found by the key VanillaBP deduplicates them by,
   * which carries the process between two vertical bars.
   * <p>
   * Two kinds of entry are therefore not found by their process, and a test which needs
   * them reads {@link PhaseTwoOutboxReader#entries()} instead. An operation which is
   * never deduplicated, such as a broadcast signal, has no key at all. And a key too
   * long for the column is stored as a hash of itself, which no longer reads as the
   * process it was built from.
   *
   * @param name The table
   */
  record GruelboxOutboxTable(String name) implements OutboxTable {

    @Override
    public String selectEntries() {

      return "SELECT id, processed, blocked, attempts FROM %s".formatted(name);

    }

    @Override
    public String whereTheProcessIs() {

      return " WHERE uniqueRequestId LIKE ? ESCAPE '\\'";

    }

    @Override
    public String whatMatchesTheProcess(
        final String bpmnProcessId) {

      final var escaped = bpmnProcessId
          .replace("\\", "\\\\")
          .replace("%", "\\%")
          .replace("_", "\\_");
      return "%%|%s|%%".formatted(escaped);

    }

    @Override
    public Entry entryOf(
        final ResultSet results) throws SQLException {

      final State state;
      if (results.getBoolean(3)) {
        state = State.BLOCKED;
      } else if (results.getBoolean(2)) {
        state = State.DISPATCHED;
      } else {
        state = State.WAITING;
      }
      return new Entry(results.getString(1), state, results.getInt(4));

    }

  }

  private final DataSource dataSource;

  private final OutboxTable outbox;

  private final String payloadTable;

  /**
   * @param dataSource The database of the application under test
   * @param outbox The table its outbox writes
   * @param payloadTable The table the payloads lie in
   */
  PhaseTwoOutboxReader(
      final DataSource dataSource,
      final OutboxTable outbox,
      final String payloadTable) {

    this.dataSource = dataSource;
    this.outbox = outbox;
    this.payloadTable = payloadTable;

  }

  /**
   * The reader for an application whose outbox is the one of the gruelbox library,
   * which is what a Spring Boot application with JPA runs.
   *
   * @param dataSource The database of the application under test
   * @return The reader
   */
  public static PhaseTwoOutboxReader ofTheGruelboxOutbox(
      final DataSource dataSource) {

    return new PhaseTwoOutboxReader(
        dataSource, gruelboxOutboxTable(), PhaseTwoOutboxNames.payloadTable());

  }

  /**
   * The reader for an application whose outbox is the table VanillaBP writes itself,
   * which is what Quarkus applications and Spring Boot applications without JPA run.
   *
   * @param dataSource The database of the application under test
   * @return The reader
   */
  public static PhaseTwoOutboxReader ofTheVanillaBpOutbox(
      final DataSource dataSource) {

    return new PhaseTwoOutboxReader(
        dataSource, vanillaBpOutboxTable(), PhaseTwoOutboxNames.payloadTable());

  }

  /**
   * The reader for the outbox this database holds, for a test which does not want to
   * say which one its application runs. An application which cannot run the gruelbox
   * outbox at all is served by the table VanillaBP writes itself, without being asked.
   *
   * @param dataSource The database of the application under test
   * @return The reader
   * @throws IllegalStateException If the database holds both tables or neither of them
   */
  public static PhaseTwoOutboxReader of(
      final DataSource dataSource) {

    final var vanillaBpTable = PhaseTwoOutboxNames.vanillaBpOutboxTable();
    final var tableInThisDatabase = outboxTableOf(
        dataSource,
        vanillaBpTable,
        PhaseTwoOutboxNames.gruelboxOutboxTableIfThisApplicationCanRunIt());
    // the name is the whole answer: the gruelbox table is the only other one this
    // reader looks for
    return tableInThisDatabase.equals(vanillaBpTable)
        ? ofTheVanillaBpOutbox(dataSource)
        : ofTheGruelboxOutbox(dataSource);

  }

  /**
   * Picks the outbox by the table which is there and answers with the name of that
   * table. Two table names are all this question needs, so nothing else is fetched from
   * the platform before it is answered - what the reader needs to read the table it
   * found comes afterwards, from the factory method for that outbox. A name nobody
   * needs must not be the reason a test fails in its setup.
   * <p>
   * Both tables in one database is a question this cannot answer: an application which
   * creates the whole VanillaBP schema has the table of VanillaBP's own outbox even
   * while it runs gruelbox.
   *
   * @param dataSource The database of the application under test
   * @param vanillaBpTable The table VanillaBP writes itself
   * @param gruelboxTable The table gruelbox writes, empty where the application under
   *          test cannot run the gruelbox outbox at all
   * @return The name of the table this database holds
   * @throws IllegalStateException If the database holds both tables or neither of them
   */
  static String outboxTableOf(
      final DataSource dataSource,
      final String vanillaBpTable,
      final Optional<String> gruelboxTable) {

    final var vanillaBpIsThere = tableExists(dataSource, vanillaBpTable);
    final var gruelboxWhichIsThere = gruelboxTable.filter(table -> tableExists(dataSource, table));
    if (vanillaBpIsThere && gruelboxWhichIsThere.isPresent()) {
      throw new IllegalStateException(
          """
              This database holds '%s' as well as '%s', so which outbox the application runs \
              cannot be read from it! Say which one it is: ofTheVanillaBpOutbox(dataSource) or \
              ofTheGruelboxOutbox(dataSource)."""
              .formatted(vanillaBpTable, gruelboxWhichIsThere.get()));
    }
    if (vanillaBpIsThere) {
      return vanillaBpTable;
    }
    if (gruelboxWhichIsThere.isPresent()) {
      return gruelboxWhichIsThere.get();
    }
    throw new IllegalStateException(
        """
            This database holds neither '%s' nor %s! Either the application under test writes \
            its phase-two outbox somewhere else, or its schema was not created yet."""
            .formatted(
                vanillaBpTable,
                gruelboxTable
                    .map("'%s'"::formatted)
                    .orElse("a table of the gruelbox outbox, which this application cannot run")));

  }

  /**
   * Names the table this reader reads, so a failing test can say where it looked.
   *
   * @return The table
   */
  public String outboxTableName() {

    return outbox.name();

  }

  /**
   * Every entry of the outbox, dispatched ones included: an entry stays in the table
   * until the retention passes.
   *
   * @return The entries, in no particular order
   */
  public List<Entry> entries() {

    return read(outbox.selectEntries(), null);

  }

  /**
   * The entries of one BPMN process.
   *
   * @param bpmnProcessId The process asked about
   * @return Its entries, in no particular order
   */
  public List<Entry> entriesOf(
      final String bpmnProcessId) {

    return read(
        outbox.selectEntries() + outbox.whereTheProcessIs(),
        outbox.whatMatchesTheProcess(bpmnProcessId));

  }

  /**
   * How many entries wait for their dispatch. A blocked entry is not one of them: it
   * waits for a person rather than for the next attempt.
   *
   * @return The number of waiting entries
   */
  public long entriesWaiting() {

    // counted over the entries rather than by a statement of its own: a test database
    // holds a handful of rows, and one statement less is one statement to keep in step
    // with two tables
    return entries()
        .stream()
        .filter(Entry::isWaiting)
        .count();

  }

  /**
   * Removes the entries of one BPMN process, and the payloads they name.
   * <p>
   * The payloads go by the process they were written for, which is a column of their
   * own table. In the gruelbox table an entry without a key is not found by its
   * process, so a call which is never deduplicated, such as a broadcast signal, keeps
   * its entry here while its payload goes. A test which plans such a call calls
   * {@link #removeAllEntries()} instead.
   *
   * @param bpmnProcessId The process whose entries go
   */
  public void removeEntriesOf(
      final String bpmnProcessId) {

    execute(
        "DELETE FROM %s".formatted(outbox.name()) + outbox.whereTheProcessIs(),
        outbox.whatMatchesTheProcess(bpmnProcessId));
    removePayloads(" WHERE BPMN_PROCESS_ID = ?", bpmnProcessId);

  }

  /**
   * Removes every entry and every payload, which is what a test leaves behind for the
   * next one.
   */
  public void removeAllEntries() {

    execute("DELETE FROM %s".formatted(outbox.name()), null);
    removePayloads("", null);

  }

  /**
   * Removes payload rows. An application whose calls never carry a payload has no such
   * table, and then there is nothing to remove either.
   *
   * @param where What narrows the delete down, empty for all rows
   * @param binding What to bind to the parameter of the condition, <code>null</code>
   *          where it has none
   */
  private void removePayloads(
      final String where,
      final String binding) {

    if (!tableExists(dataSource, payloadTable)) {
      return;
    }
    execute("DELETE FROM %s".formatted(payloadTable) + where, binding);

  }

  /**
   * @param statement The select to run
   * @param binding What to bind to its one parameter, <code>null</code> where it has
   *          none
   * @return What the outbox table says about the entries read
   */
  private List<Entry> read(
      final String statement,
      final String binding) {

    try (var connection = dataSource.getConnection(); var select = connection.prepareStatement(statement)) {
      if (binding != null) {
        select.setString(1, binding);
      }
      try (var results = select.executeQuery()) {
        final var entries = new ArrayList<Entry>();
        while (results.next()) {
          entries.add(outbox.entryOf(results));
        }
        return entries;
      }
    } catch (final SQLException cannotRead) {
      throw new IllegalStateException(
          "Could not read the phase-two outbox table '%s'!".formatted(outbox.name()), cannotRead);
    }

  }

  /**
   * @param statement The statement to run
   * @param binding What to bind to its one parameter, <code>null</code> where it has
   *          none
   */
  private void execute(
      final String statement,
      final String binding) {

    try (var connection = dataSource.getConnection(); var update = connection.prepareStatement(statement)) {
      if (binding != null) {
        update.setString(1, binding);
      }
      update.executeUpdate();
    } catch (final SQLException cannotWrite) {
      throw new IllegalStateException(
          "Could not run '%s' on the database of the application under test!".formatted(statement), cannotWrite);
    }

  }

  /**
   * Whether the database holds a table of that name. The question is asked as a select
   * rather than through the metadata of the driver, because a metadata lookup has to
   * spell the name the way the database stores it, and every database stores it
   * differently.
   *
   * @param dataSource The database of the application under test
   * @param tableName The table asked about
   * @return Whether it is there
   */
  private static boolean tableExists(
      final DataSource dataSource,
      final String tableName) {

    try (Connection connection = dataSource.getConnection(); var select = connection.createStatement()) {
      select
          .executeQuery("SELECT 1 FROM %s WHERE 1 = 0".formatted(tableName))
          .close();
      return true;
    } catch (final SQLException noSuchTable) {
      return false;
    }

  }

  private static OutboxTable vanillaBpOutboxTable() {

    return new VanillaBpOutboxTable(
        PhaseTwoOutboxNames.vanillaBpOutboxTable(), PhaseTwoOutboxNames.waitingState(), PhaseTwoOutboxNames
            .dispatchedState(), PhaseTwoOutboxNames.blockedState());

  }

  private static OutboxTable gruelboxOutboxTable() {

    return new GruelboxOutboxTable(PhaseTwoOutboxNames.gruelboxOutboxTable());

  }

}
