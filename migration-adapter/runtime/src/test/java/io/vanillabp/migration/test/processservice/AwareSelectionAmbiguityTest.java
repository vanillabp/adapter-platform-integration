package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;

/**
 * Story 70: an aware bean may name an interface all aggregates of a workflow module
 * implement, a bean naming the aggregate itself still wins, and a tie is reported instead
 * of being decided by the order the beans were found in.
 */
public class AwareSelectionAmbiguityTest {

  private interface HasWorkflowState {
  }

  private interface Auditable {
  }

  private static class OrderAggregate implements HasWorkflowState, Auditable {
  }

  private record Candidate(String name, Class<?> aggregateClass) {
  }

  private static final Function<Candidate, Class<?>> AGGREGATE_CLASS = Candidate::aggregateClass;

  @Test
  @DisplayName("An interface shared by the module's aggregates serves them all")
  public void interfaceServesEveryAggregateImplementingIt() {

    final var moduleWide = new Candidate("moduleWide", HasWorkflowState.class);

    final var selected = AwareSelection
        .mostSpecificDistinct(
            List.of(moduleWide),
            AGGREGATE_CLASS,
            OrderAggregate.class,
            tied -> new IllegalStateException("not expected"));

    assertSame(moduleWide, selected.orElseThrow());

  }

  @Test
  @DisplayName("A bean naming the aggregate itself beats one naming an interface of it")
  public void theAggregateItselfWins() {

    final var moduleWide = new Candidate("moduleWide", HasWorkflowState.class);
    final var aggregateSpecific = new Candidate("aggregateSpecific", OrderAggregate.class);

    final var selected = AwareSelection
        .mostSpecificDistinct(
            List.of(moduleWide, aggregateSpecific),
            AGGREGATE_CLASS,
            OrderAggregate.class,
            tied -> new IllegalStateException("not expected"));

    assertSame(aggregateSpecific, selected.orElseThrow());

  }

  @Test
  @DisplayName("Two beans at the same distance are reported, not decided silently")
  public void ambiguityIsReported() {

    final var first = new Candidate("byState", HasWorkflowState.class);
    final var second = new Candidate("byAudit", Auditable.class);

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> AwareSelection
            .mostSpecificDistinct(
                List.of(first, second),
                AGGREGATE_CLASS,
                OrderAggregate.class,
                tied -> new IllegalStateException(
                    "tied: "
                        + tied
                            .stream()
                            .map(Candidate::name)
                            .toList())));

    assertTrue(failure.getMessage().contains("byState"), failure.getMessage());
    assertTrue(failure.getMessage().contains("byAudit"), failure.getMessage());

  }

  @Test
  @DisplayName("A candidate not covering the aggregate at all is no candidate")
  public void nonMatchingCandidatesAreIgnored() {

    final var unrelated = new Candidate("unrelated", String.class);

    assertEquals(
        0,
        AwareSelection
            .mostSpecificDistinct(
                List.of(unrelated),
                AGGREGATE_CLASS,
                OrderAggregate.class,
                tied -> new IllegalStateException("not expected"))
            .stream()
            .count());

  }

}
