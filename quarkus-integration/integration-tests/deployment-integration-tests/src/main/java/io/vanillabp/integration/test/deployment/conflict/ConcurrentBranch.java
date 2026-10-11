package io.vanillabp.integration.test.deployment.conflict;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

/**
 * The second writer of the version-conflict acceptance test. It changes the same
 * workflow aggregate in a transaction of ITS OWN and commits, while VanillaBP's
 * transaction is still open. This is the collision without a race. In real life a
 * parallel branch of a workflow does this. Here it happens one after the other, so the
 * test cannot fail by chance.
 */
@ApplicationScoped
public class ConcurrentBranch {

  @Inject
  EntityManager entityManager;

  /**
   * Changes the aggregate in a separate transaction, which is committed on return. So the
   * version of the row is higher than the one which VanillaBP's transaction read.
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
