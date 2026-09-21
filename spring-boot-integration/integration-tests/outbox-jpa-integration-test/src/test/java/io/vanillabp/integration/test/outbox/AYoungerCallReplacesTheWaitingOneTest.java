package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The whole way on Spring Boot: two reports of one workflow are planned while the
 * dispatch stands, and the extension is called once, with the younger state. The younger
 * call takes the row of the waiting entry, which keeps its id and its key, and the payload
 * of the entry it replaced is removed in the same transaction, because no dispatch had
 * ever read it (decision 68 in the repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class AYoungerCallReplacesTheWaitingOneTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>, which these tests
   * configure as half a second, so this is three of those windows.
   * <p>
   * It is a guard and not a measurement of speed: a machine which leaves this JVM without
   * a turn only makes the wait longer, and what is asserted afterwards is a count which
   * did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  private static final String COUNT_ENTRIES_OF_KEY = "select count(*) from VANILLABP_PHASE_TWO_OUTBOX where IDEMPOTENCY_KEY = ?";

  private static final String COUNT_UNPROCESSED_ENTRIES = "select count(*) from VANILLABP_PHASE_TWO_OUTBOX where STATUS = 'OPEN'";

  private static final String COUNT_PAYLOAD_OF_REFERENCE = "select count(*) from VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD "
      + "where REFERENCE = ?";

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  private static byte[] payloadOf(
      final String content) {

    return content.getBytes(StandardCharsets.UTF_8);

  }

  private long countPayloadsOf(
      final String reference) {

    return jdbcTemplate.queryForObject(COUNT_PAYLOAD_OF_REFERENCE, Long.class, reference);

  }

  @Test
  @DisplayName("The younger report replaces the waiting one, and only it is dispatched")
  public void theYoungerReportReplacesTheWaitingOne() throws Exception {

    final var older = new AtomicReference<String>();
    final var younger = new AtomicReference<String>();
    final var key = new AtomicReference<String>();

    // both ride ONE transaction on purpose: nothing is dispatched before it commits,
    // so the second call meets an entry which is certainly still waiting
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

      // one entry under that key, and the bytes of the replaced call are gone before
      // this transaction commits
      assertEquals(1L, jdbcTemplate.queryForObject(COUNT_ENTRIES_OF_KEY, Long.class, key.get()));
      assertEquals(0L, countPayloadsOf(older.get()));
      assertEquals(1L, countPayloadsOf(younger.get()));
      return attached;
    });
    assertNotNull(aggregate);

    final var dispatched = extension.awaitDispatched(1, 10000);
    assertArrayEquals(payloadOf("{\"amount\":2}"), dispatched.getFirst().payload());
    assertEquals(younger.get(), dispatched.getFirst().payloadReference());

    // and the one which was dispatched is the only one there ever was
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
      assertTrue(jdbcTemplate.queryForObject(COUNT_UNPROCESSED_ENTRIES, Long.class) >= 2);
    } finally {
      extension.releaseHeldDispatch();
    }

    // both reach the handler - which of them first is the dispatcher's business
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

    // the replaced entry has to be committed for this to be a replacement at all, and
    // the only moment it is certainly still waiting is before the transaction commits
    // which wrote it - so both calls ride the transaction which rolls back. What it
    // proves is that all three writes of a replacement enlist: the entry, the younger
    // payload, and the removal of the payload which was replaced
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

    assertEquals(0L, jdbcTemplate.queryForObject(COUNT_ENTRIES_OF_KEY, Long.class, key.get()));
    assertEquals(0L, countPayloadsOf(older.get()));
    assertEquals(0L, countPayloadsOf(younger.get()));

    // wait longer than the poll interval: nothing of that transaction may be dispatched
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(extension.getDispatched().isEmpty());

  }

}
