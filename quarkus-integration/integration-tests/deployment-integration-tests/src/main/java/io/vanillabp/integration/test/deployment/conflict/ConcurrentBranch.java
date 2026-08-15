package io.vanillabp.integration.test.deployment.conflict;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

/**
 * The second writer of the version-conflict acceptance test (story 59): it changes the
 * same workflow aggregate in a transaction of ITS OWN and commits, while VanillaBP's
 * transaction is still open. That is the shape of the collision without a race - what
 * a parallel branch of a workflow does in real life, made sequential so the test
 * cannot flake.
 */
@ApplicationScoped
public class ConcurrentBranch {

  @Inject
  EntityManager entityManager;

  /**
   * Changes the aggregate in a separate transaction which is committed on return, so
   * the version of the row is higher than the one VanillaBP's transaction read.
   *
   * @param aggregateId The aggregate's ID
   */
  @Transactional(TxType.REQUIRES_NEW)
  public void changeInOwnTransaction(
      final String aggregateId) {

    final var aggregate = entityManager.find(ConflictAggregate.class, aggregateId);
    aggregate.setContent("changed by the other branch");
    entityManager.flush();

  }

}
