package io.vanillabp.integration.test.outbox.conflict;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second writer of the version-conflict acceptance test. It changes the same workflow
 * aggregate in ITS OWN transaction and commits, while the transaction of VanillaBP is
 * still open. This is the collision without a race. In real life a parallel branch of a
 * workflow does this. Here it happens one after the other, so the test cannot flake.
 */
@Component
public class ConcurrentBranch {

  private final ConflictAggregateRepository repository;

  public ConcurrentBranch(
      final ConflictAggregateRepository repository) {

    this.repository = repository;

  }

  /**
   * Changes the aggregate in a separate transaction, which commits on return. So the
   * version of the row is higher than the version the transaction of VanillaBP read.
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
