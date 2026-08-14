package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.PerAdapterAwarenessSource;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.RecordingWorkflowAdapterCache;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The cache-override SPI (story 25): an application-provided
 * {@code WorkflowAdapterCache} bean replaces VanillaBP's in-memory
 * {@code @DefaultBean} - the election consults AND populates the application's
 * bean (this is how cluster setups share elections via their own cache
 * infrastructure) - and its lookups are counted like every other cache's.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CacheOverrideTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("cache-override.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(PerAdapterAwarenessSource.class)
          .addClass(RecordingWorkflowAdapterCache.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingWorkflowAdapterCache cache;

  @Inject
  PerAdapterAwarenessSource awareness;

  @Inject
  WorkflowAdapterCacheStatistics statistics;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("An application-provided cache bean replaces the in-memory default")
  public void applicationProvidedCacheReplacesTheDefault() throws Exception {

    awareness.answerFor("test", WorkflowAwareness.ACTIVE);

    userTransaction.begin();
    final Aggregate aggregate;
    try {
      aggregate = workflowService.startWorkflow("cache-override");
      workflowService.completeTask(aggregate, "task-cache");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // the election consulted AND populated the application's bean
    assertFalse(cache.getGets().isEmpty(), "the election must consult the application's cache");
    assertEquals(1, cache.getPuts().size(), "one successful election, one put");
    assertTrue(
        cache
            .getPuts()
            .getFirst()
            .endsWith("|"
                + aggregate.getId()
                + "->test"),
        "the successful election must be stored in the application's cache but got: "
            + cache.getPuts());

    // its lookups are counted like every other cache's (story 58) - a metric which
    // disappeared once an application plugs in its own cache would surprise exactly
    // the operator who needs it
    assertTrue(
        (statistics.getHits() + statistics.getMisses()) > 0,
        "the lookups of an application-provided cache are counted, too");
    assertTrue(
        statistics.getSize().isEmpty(),
        "but only VanillaBP's in-memory default knows its size");
    assertEquals(
        0,
        statistics.getEvictions(),
        "and an application's cache manages its own bounds");

  }

}
