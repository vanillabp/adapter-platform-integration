package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * The whole way on Spring Boot. Two reports of one workflow are planned while the dispatch
 * stands still. The extension is called once, with the younger state. The younger call
 * takes the row of the waiting entry, which keeps its id and its key. The payload of the
 * replaced entry is removed in the same transaction, because no dispatch had ever read it
 * (decision 68 in the repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class AYoungerCallReplacesTheWaitingOneTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>. These tests set that
   * to half a second, so this is three of those windows.
   * <p>
   * It is a guard, not a measurement of speed. A machine which leaves this JVM without a
   * turn only makes the wait longer. Afterwards the test asserts a count which did not
   * grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @Autowired
  private DataSource dataSource;

  /**
   * The outbox of the application, read through the transaction this test runs. The tests
   * below assert everything while the transaction which wrote it is still open. Only then
   * can a replacement be watched. So the reader must see what that transaction wrote.
   */
  private PhaseTwoOutboxReader outboxTable;

  @BeforeEach
  public void resetExtension() {

    extension.reset();
    outboxTable = PhaseTwoOutboxReader.ofTheVanillaBpOutbox(new TransactionAwareDataSourceProxy(dataSource));

  }

  private static byte[] payloadOf(
      final String content) {

    return content.getBytes(StandardCharsets.UTF_8);

  }

  private long countPayloadsOf(
      final String reference) {

    return outboxTable
        .payloads()
        .stream()
        .filter(payload -> reference.equals(payload.reference()))
        .count();

  }

  private long countEntriesOf(
      final String idempotencyKey) {

    return outboxTable
        .entries()
        .stream()
        .filter(entry -> idempotencyKey.equals(entry.idempotencyKey()))
        .count();

  }

  @Test
  @DisplayName("The younger report replaces the waiting one, and only it is dispatched")
  public void theYoungerReportReplacesTheWaitingOne() throws Exception {

    final var older = new AtomicReference<String>();
    final var younger = new AtomicReference<String>();
    final var key = new AtomicReference<String>();

    // both run in ONE transaction on purpose. Nothing is dispatched before it commits. So
    // the second call meets an entry which is surely still waiting
    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("replace-waiting");
      final var attached = processService.startWorkflow(newAggregate);

      final var first = SampleExtension
          .call("test-module", "dummy", attached.getId().toString(), "reported", payloadOf("{\"amount\":1}"));
      older.set(first.payloadReference());
      key.set(first.idempotencyKey().orElseThrow());
      assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(first));

      final var second = SampleExtension
          .call("test-module", "dummy", attached.getId().toString(), "reported", payloadOf("{\"amount\":2}"));
      younger.set(second.payloadReference());
      assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(second));

      // one entry under that key. The bytes of the replaced call are gone before this
      // transaction commits
      assertEquals(1L, countEntriesOf(key.get()));
      assertEquals(0L, countPayloadsOf(older.get()));
      assertEquals(1L, countPayloadsOf(younger.get()));
      return attached;
    });
    assertNotNull(aggregate);

    final var dispatched = extension.awaitDispatched(1, 10000);
    assertArrayEquals(payloadOf("{\"amount\":2}"), dispatched.getFirst().payload());
    assertEquals(younger.get(), dispatched.getFirst().payloadReference());

    // and the dispatched call is the only one there ever was
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(1, extension.getDispatched().size());

  }

  @Test
  @DisplayName("Without the word the older report stays and the younger one is dropped")
  public void withoutTheWordTheOlderReportStays() throws Exception {

    final var older = new AtomicReference<String>();

    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("no-replace-waiting");
      final var attached = processService.startWorkflow(newAggregate);

      final var first = SampleExtension
          .call("test-module", "dummy", attached.getId().toString(), "reported", payloadOf("{\"amount\":1}"));
      older.set(first.payloadReference());
      assertTrue(outbox.schedule(first));

      final var second = SampleExtension
          .call("test-module", "dummy", attached.getId().toString(), "reported", payloadOf("{\"amount\":2}"));
      assertFalse(outbox.schedule(second));
      // a schedule which was discarded leaves nothing behind
      assertEquals(0L, countPayloadsOf(second.payloadReference()));
      return attached;
    });
    assertNotNull(aggregate);

    final var dispatched = extension.awaitDispatched(1, 10000);
    assertArrayEquals(payloadOf("{\"amount\":1}"), dispatched.getFirst().payload());
    assertEquals(older.get(), dispatched.getFirst().payloadReference());

  }

  @Test
  @DisplayName("An entry a dispatch has taken is not replaced - the younger call becomes a second one")
  public void anEntryADispatchHasTakenIsNotReplaced() throws Exception {

    extension.holdNextDispatch();
    try {
      final var aggregate = transactionTemplate.execute(status -> {
        final var newAggregate = new Aggregate();
        newAggregate.setContent("replace-claimed");
        final var attached = processService.startWorkflow(newAggregate);
        assertTrue(
            outbox
                .scheduleReplacingWhatIsStillWaiting(
                    SampleExtension
                        .call(
                            "test-module",
                            "dummy",
                            attached.getId().toString(),
                            "reported",
                            payloadOf("{\"amount\":1}"))));
        return attached;
      });
      assertNotNull(aggregate);

      // the entry is claimed and its dispatch stands inside the handler
      extension.awaitHeldDispatchEntered(10000);

      final var scheduled = transactionTemplate
          .execute(status -> outbox
              .scheduleReplacingWhatIsStillWaiting(
                  SampleExtension
                      .call(
                          "test-module",
                          "dummy",
                          aggregate.getId().toString(),
                          "reported",
                          payloadOf("{\"amount\":2}"))));
      assertTrue(Boolean.TRUE.equals(scheduled));

      // two entries wait now: the one which is on its way and the one this call became
      assertTrue(outboxTable.entriesWaiting() >= 2);
    } finally {
      extension.releaseHeldDispatch();
    }

    // both reach the handler. The dispatcher decides which one comes first
    final var payloads = extension
        .awaitDispatched(2, 10000)
        .stream()
        .map(call -> new String(call.payload(), StandardCharsets.UTF_8))
        .toList();
    assertTrue(payloads.contains("{\"amount\":1}"), payloads.toString());
    assertTrue(payloads.contains("{\"amount\":2}"), payloads.toString());

  }

  @Test
  @DisplayName("A rolled-back replacement leaves neither entry nor payload behind")
  public void aRolledBackReplacementLeavesNothing() throws Exception {

    final var older = new AtomicReference<String>();
    final var younger = new AtomicReference<String>();
    final var key = new AtomicReference<String>();

    // for a replacement, the replaced entry must already be written. It is surely still
    // waiting only before the transaction which wrote it commits. So both calls run in the
    // transaction which rolls back. This proves that all three writes of a replacement
    // are part of the transaction: the entry, the younger payload, and the removal of the
    // replaced payload
    try {
      transactionTemplate.execute(status -> {
        final var newAggregate = new Aggregate();
        newAggregate.setContent("replace-rollback");
        final var attached = processService.startWorkflow(newAggregate);

        final var first = SampleExtension
            .call("test-module", "dummy", attached.getId().toString(), "reported", payloadOf("{\"amount\":1}"));
        older.set(first.payloadReference());
        key.set(first.idempotencyKey().orElseThrow());
        assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(first));

        final var second = SampleExtension
            .call("test-module", "dummy", attached.getId().toString(), "reported", payloadOf("{\"amount\":2}"));
        younger.set(second.payloadReference());
        assertTrue(outbox.scheduleReplacingWhatIsStillWaiting(second));

        throw new RuntimeException("test rollback");
      });
    } catch (final RuntimeException e) {
      assertEquals("test rollback", e.getMessage());
    }

    assertEquals(0L, countEntriesOf(key.get()));
    assertEquals(0L, countPayloadsOf(older.get()));
    assertEquals(0L, countPayloadsOf(younger.get()));

    // wait longer than the poll interval: nothing of that transaction may be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(extension.getDispatched().isEmpty());

  }

}
