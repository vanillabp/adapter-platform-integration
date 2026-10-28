package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bson.Document;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.persistence.ActiveTaskAwarenessSource;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregate;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.MongoRepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.PhaseTwoRecorder;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Acceptance test on Quarkus with MongoDB Panache: the outbox dispatches an operation right
 * after the commit, and not when its poller wakes up on its own.
 * <p>
 * MongoDB Panache commits its MongoDB transaction in the <code>afterCompletion</code> of a
 * synchronization which it registers as interposed. The outbox used to wake its poller in an
 * interposed synchronization, too. It was registered later, and Narayana calls the
 * interposed synchronizations in the reverse order of their registration. So the poller woke
 * before the MongoDB commit. The poll runs on a thread of its own, so it raced the commit.
 * Where the poll was faster, it found nothing, and the entry waited for
 * <code>vanillabp.outbox.poll-interval</code>. Before the fix, the first start of this test
 * lost that race in each of three runs and was dispatched after 10.0 s. The later starts
 * mostly won it. So the first test shows the delay, and the second test pins the order
 * which the fix relies on. That order does not depend on any race.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoDispatchRightAfterTheCommitTest {

  private static final String DATABASE = "dispatch-after-commit-it";

  /**
   * How long the poller sleeps when nobody wakes it. It is the default, so a late dispatch
   * looks like the one an application sees.
   */
  private static final String POLL_INTERVAL = "PT10S";

  /**
   * The longest time from the commit to the dispatch which counts as "right after". A
   * dispatch which missed the wake-up comes after the poll interval, which is five times as
   * long. So a slow machine does not turn this test red, and a missed wake-up always does.
   */
  private static final long RIGHT_AFTER_THE_COMMIT_MILLIS = 2000;

  private static final int STARTS = 5;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(MongoRepositoryAggregate.class)
          .addClass(MongoRepositoryAggregateRepository.class)
          .addClass(MongoRepositoryWorkflowService.class)
          .addClass(PhaseTwoRecorder.class)
          .addClass(ActiveTaskAwarenessSource.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideConfigKey("quarkus.mongodb.devservices.image-name", ContainerImages.MONGODB)
      .overrideConfigKey("vanillabp.outbox.poll-interval", POLL_INTERVAL);

  @Inject
  MongoRepositoryWorkflowService workflowService;

  @Inject
  MongoRepositoryAggregateRepository repository;

  @Inject
  PhaseTwoRecorder recorder;

  @Inject
  MongoClient mongoClient;

  @Inject
  TransactionManager transactionManager;

  @Inject
  TransactionSynchronizationRegistry transactionRegistry;

  @Test
  @DisplayName("A start is dispatched right after its commit, not after the poll interval")
  public void aStartIsDispatchedRightAfterItsCommit() throws Exception {

    final var millisFromCommitToDispatch = new ArrayList<Long>();
    for (var i = 0; i < STARTS; ++i) {
      final var aggregateId = "right-after-the-commit-"
          + i;
      // The service method opens the transaction, so the commit is over when it returns.
      workflowService.startWorkflow(aggregateId);
      final var committedAt = System.nanoTime();
      assertTrue(
          recorder.awaitStartedWorkflow(aggregateId, 30000),
          "the start of '"
              + aggregateId
              + "' was never dispatched");
      millisFromCommitToDispatch.add((System.nanoTime() - committedAt) / 1_000_000);
    }

    assertTrue(
        millisFromCommitToDispatch
            .stream()
            .allMatch(millis -> millis < RIGHT_AFTER_THE_COMMIT_MILLIS),
        "every start has to be dispatched within "
            + RIGHT_AFTER_THE_COMMIT_MILLIS
            + " ms of its commit, but it took (ms): "
            + millisFromCommitToDispatch);

  }

  /**
   * Shows the order in which Narayana tells the synchronizations about the commit. One is
   * registered as interposed, after Panache registered its own. It runs before Panache
   * committed, so it does not see the aggregate. One is registered through the transaction,
   * and before the other one on purpose. It still runs last, and it sees the aggregate.
   */
  @Test
  @DisplayName("Only a synchronization registered through the transaction sees the commit of Panache")
  public void onlyAPlainSynchronizationSeesTheCommitOfPanache() {

    final var aggregateId = "order-of-synchronizations";
    final var seen = new CopyOnWriteArrayList<String>();

    QuarkusTransaction
        .requiringNew()
        .run(() -> {
          final var aggregate = new MongoRepositoryAggregate();
          aggregate.setId(aggregateId);
          aggregate.setStatus("written");
          // Panache starts its session here and registers its interposed synchronization.
          repository.persist(aggregate);
          try {
            transactionManager
                .getTransaction()
                .registerSynchronization(afterCommit("plain", aggregateId, seen));
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
          transactionRegistry.registerInterposedSynchronization(afterCommit("interposed", aggregateId, seen));
        });

    assertEquals(
        List.of("interposed: not visible", "plain: visible"),
        seen,
        "the plain synchronization runs after every interposed one, and Panache has committed by then");

  }

  /**
   * A synchronization which writes down, after the commit, whether a reader outside the
   * transaction sees the aggregate already.
   */
  private Synchronization afterCommit(
      final String name,
      final String aggregateId,
      final List<String> seen) {

    return new Synchronization() {

      @Override
      public void beforeCompletion() {
      }

      @Override
      public void afterCompletion(
          final int status) {

        if (status != Status.STATUS_COMMITTED) {
          seen.add(name
              + ": rolled back");
          return;
        }
        final var visible = mongoClient
            .getDatabase(DATABASE)
            .getCollection("MongoRepositoryAggregate")
            .countDocuments(new Document("_id", aggregateId)) == 1;
        seen.add(name + (visible ? ": visible" : ": not visible"));

      }

    };

  }

}
