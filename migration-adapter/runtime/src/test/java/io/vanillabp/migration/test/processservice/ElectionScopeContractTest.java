package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.WorkflowLocator;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The election contract of {@code MigratableProcessService} (story 105): an adapter
 * answers the awareness probes ONLY for the workflows and tasks of its own scope.
 * <p>
 * The core cannot enforce that. Which adapters may be asked is its business, which
 * workflows an adapter owns is only the adapter's, so the contract is a duty of the
 * implementations and this is where it is written down as a test. Two stories arrived
 * at it the hard way: Camunda 8 (story 103) had two adapter ids on one cluster where
 * every key is global, and Camunda 7 (story 104) answers for any workflow module of
 * its engine carrying the same aggregate id. Both read an SPI which never said
 * otherwise.
 * <p>
 * Two adapters with DISJOINT scopes are what the tests below run through the real
 * {@link WorkflowLocator}: the first one holds nothing of what is asked for, the
 * second one holds it. What the contract buys is the first test; what breaking it
 * costs is the second, asserted rather than assumed, because a test which only shows
 * the good case says nothing about why the rule exists.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ElectionScopeContractTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * The workflow the tests ask about. It belongs to the SECOND adapter throughout.
   */
  private static final String AGGREGATE_OF_THE_SECOND = "42";

  /**
   * An adapter which answers for its own scope, which here is a set of workflow
   * aggregate ids - a test double needs nothing of the tenant, prefix or table prefix
   * a real BPMS keeps its scopes apart with, only the ability to say "not mine".
   * <p>
   * It reuses the SPI stubs of {@link WorkflowLocatorTest.ProbeAdapter} (same package,
   * same subject) and replaces the three probes with the scoped answer.
   */
  static class ScopedAdapter extends WorkflowLocatorTest.ProbeAdapter {

    private final Set<String> ownWorkflows;

    private final WorkflowAwareness answerForOwn;

    ScopedAdapter(
        final String adapterId,
        final Set<String> ownWorkflows) {

      this(adapterId, ownWorkflows, WorkflowAwareness.ACTIVE);

    }

    /**
     * @param answerForOwn What this adapter answers for a workflow it DOES hold -
     *          {@link WorkflowAwareness#BPMS_UNAVAILABLE} makes it the holder which
     *          cannot answer right now
     */
    ScopedAdapter(
        final String adapterId,
        final Set<String> ownWorkflows,
        final WorkflowAwareness answerForOwn) {

      super(adapterId, WorkflowAwareness.UNKNOWN_TO_BPMS);
      this.ownWorkflows = ownWorkflows;
      this.answerForOwn = answerForOwn;

    }

    WorkflowAwareness answerFor(
        final Object workflowAggregateId) {

      probes.incrementAndGet();
      return ownWorkflows.contains(String.valueOf(workflowAggregateId))
          ? answerForOwn
          : WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final Object workflowAggregateId,
        final String taskId) {
      return answerFor(workflowAggregateId);
    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final Object workflowAggregateId,
        final String taskId) {
      return answerFor(workflowAggregateId);
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final io.vanillabp.integration.spi.AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
      return answerFor(workflowAggregateId);
    }

  }

  /**
   * An adapter which breaks the contract: it claims every workflow it is asked about,
   * which is what an unscoped probe does on a backend it shares with another adapter.
   */
  static class ClaimsEverythingAdapter extends WorkflowLocatorTest.ProbeAdapter {

    ClaimsEverythingAdapter(
        final String adapterId) {

      super(adapterId, WorkflowAwareness.ACTIVE);

    }

    WorkflowAwareness claim() {

      probes.incrementAndGet();
      return WorkflowAwareness.ACTIVE;

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final Object workflowAggregateId,
        final String taskId) {
      return claim();
    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final Object workflowAggregateId,
        final String taskId) {
      return claim();
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final io.vanillabp.integration.spi.AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
      return claim();
    }

  }

  /**
   * The three probes the election uses, each as the caller passes it to the locator.
   */
  private static final List<ProbeUnderTest> PROBES = List
      .of(
          new ProbeUnderTest(
              "service task", adapter -> adapter.awarenessOfTask(AGGREGATE_OF_THE_SECOND, "task-1")),
          new ProbeUnderTest(
              "user task", adapter -> adapter.awarenessOfUserTask(AGGREGATE_OF_THE_SECOND, "user-task-1")),
          new ProbeUnderTest(
              "workflow", adapter -> adapter.awarenessOfWorkflow(null, AGGREGATE_OF_THE_SECOND)));

  /**
   * @param name What the probe locates, for the assertion messages
   * @param probe The probe as the operation hands it to the locator
   */
  private record ProbeUnderTest(
                                String name,
                                Function<MigratableProcessService<Object>, WorkflowAwareness> probe) {
  }

  @SafeVarargs
  private static WorkflowLocator.Location<Object> locate(
      final ProbeUnderTest probe,
      final MigratableProcessService<Object>... adapters) {

    return new WorkflowLocator(MODULE, PROCESS, null)
        .locate(
            List.of(adapters),
            probe.probe(),
            AGGREGATE_OF_THE_SECOND,
            "the %s of workflow aggregate '%s'".formatted(probe.name(), AGGREGATE_OF_THE_SECOND));

  }

  @Test
  @DisplayName("Adapters answering for their own scope let the walk reach the one holding the workflow")
  public void theWalkReachesTheHolder() {

    PROBES
        .forEach(probe -> {
          final var first = new ScopedAdapter("first", Set.of("7", "8"));
          final var second = new ScopedAdapter("second", Set.of(AGGREGATE_OF_THE_SECOND));

          final var location = locate(probe, first, second);

          assertEquals(
              WorkflowAwareness.ACTIVE,
              location.awareness(),
              () -> "the "
                  + probe.name()
                  + " was located");
          assertSame(
              second,
              location.adapter(),
              () -> "the adapter holding the workflow executes the operation on the "
                  + probe.name());
          assertEquals(
              1,
              first.probes.get(),
              () -> "the first-priority adapter answered once, for a workflow which is not its own ("
                  + probe.name()
                  + ")");
        });

  }

  @Test
  @DisplayName("An adapter claiming a foreign workflow wins the election - which is the defect the contract prevents")
  public void anAdapterClaimingEverythingWinsTheElection() {

    PROBES
        .forEach(probe -> {
          final var unscoped = new ClaimsEverythingAdapter("claims-everything");
          final var holder = new ScopedAdapter("holder", Set.of(AGGREGATE_OF_THE_SECOND));

          final var location = locate(probe, unscoped, holder);

          assertSame(
              unscoped,
              location.adapter(),
              () -> "the walk stops at the first ACTIVE, so an unscoped answer routes the "
                  + probe.name()
                  + " to an adapter which does not hold the workflow");
          assertNotSame(holder, location.adapter());
          assertEquals(
              0,
              holder.probes.get(),
              () -> "and the adapter which really holds it is never asked ("
                  + probe.name()
                  + ")");
        });

  }

  @Test
  @DisplayName("A holder which cannot answer right now is not replaced by another adapter's honest unknown")
  public void anUnavailableHolderIsNotReplaced() {

    final var first = new ScopedAdapter("first", Set.of("7"));
    final var holder = new ScopedAdapter(
        "holder", Set.of(AGGREGATE_OF_THE_SECOND), WorkflowAwareness.BPMS_UNAVAILABLE);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> locate(PROBES.getFirst(), first, holder));

    assertTrue(
        failure.getMessage().contains("holder"),
        () -> "the unavailable adapter is named rather than the operation being routed elsewhere: "
            + failure.getMessage());
    assertEquals(
        1 + WorkflowLocator.UNAVAILABLE_RETRIES,
        holder.probes.get(),
        "the holder is retried before the walk gives up");

  }

}
