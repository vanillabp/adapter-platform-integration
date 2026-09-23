package io.vanillabp.integration.outbox.gruelbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application which builds the gruelbox store itself and leaves out the data source or
 * the name of gruelbox' table.
 * <p>
 * Such a store hands entries to gruelbox and can do nothing else: it cannot free the key of
 * an entry which was dispatched, cannot let a younger call take the place of a waiting one,
 * and answers the payload housekeeping that every payload is still named, which is the
 * answer that keeps the payload table growing for good. None of that shows up as an error
 * later on, so it is refused where the store is built.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxRefusesAStoreWithoutItsTableTest {

  private static final String TABLE = "TXNO_OUTBOX";

  @Test
  @DisplayName("A store without a data source is refused, naming what it would cost")
  public void aStoreWithoutADataSourceIsRefused() {

    final var refused = assertThrows(
        IllegalArgumentException.class,
        () -> new GruelboxPhaseTwoOutbox(Mockito.mock(TransactionOutbox.class), null, TABLE));

    final var message = refused.getMessage();
    assertTrue(message.contains("without a data source"), message);
    // what it would cost, in both of its shapes
    assertTrue(message.contains("discards the next operation"), message);
    assertTrue(message.contains("removes no payload"), message);
    // and the two ways to a complete store
    assertTrue(message.contains("Pass the data source"), message);
    assertTrue(message.contains("GruelboxPhaseTwoOutboxAutoConfiguration"), message);

  }

  @Test
  @DisplayName("A store without the table name is refused naming that half alone")
  public void aStoreWithoutTheTableNameIsRefused() {

    final var refused = assertThrows(
        IllegalArgumentException.class,
        () -> new GruelboxPhaseTwoOutbox(
            Mockito.mock(TransactionOutbox.class), Mockito.mock(DataSource.class), null));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("without the name of gruelbox' table"),
        "the message names what is missing, not both halves: "
            + message);

  }

  @Test
  @DisplayName("A store missing both halves names both of them")
  public void aStoreMissingBothHalvesNamesBoth() {

    final var refused = assertThrows(
        IllegalArgumentException.class,
        () -> new GruelboxPhaseTwoOutbox(Mockito.mock(TransactionOutbox.class), null, null));

    final var message = refused.getMessage();
    assertTrue(message.contains("without a data source and the name of gruelbox' table"), message);

  }

  @Test
  @DisplayName("A store which can read the table is built")
  public void aStoreWhichCanReadTheTableIsBuilt() {

    assertDoesNotThrow(
        () -> new GruelboxPhaseTwoOutbox(
            Mockito.mock(TransactionOutbox.class), Mockito.mock(DataSource.class), TABLE),
        "a payload store is the one part an application may leave out");

  }

}
