package io.vanillabp.integration.test.outbox.conflict;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second writer of the version-conflict acceptance test (story 59): it changes the
 * same workflow aggregate in a transaction of ITS OWN and commits, while VanillaBP's
 * transaction is still open. That is the shape of the collision without a race - what
 * a parallel branch of a workflow does in real life, made sequential so the test
 * cannot flake.
 */
@Component
public class ConcurrentBranch {

  private final ConflictAggregateRepository repository;

  public ConcurrentBranch(
      final ConflictAggregateRepository repository) {

    this.repository = repository;

  }

  /**
   * Changes the aggregate in a separate transaction which is committed on return, so
   * the version of the row is higher than the one VanillaBP's transaction read.
   *
   * @param aggregateId The aggregate's ID
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void changeInOwnTransaction(
      final Long aggregateId) {

    final var aggregate = repository
        .findById(aggregateId)
        .orElseThrow();
    aggregate.setContent("changed by the other branch");
    repository.saveAndFlush(aggregate);

  }

}
