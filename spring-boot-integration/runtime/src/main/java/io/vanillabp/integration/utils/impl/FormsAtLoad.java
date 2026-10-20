package io.vanillabp.integration.utils.impl;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Remembers in which form a workflow aggregate was loaded, for as long as the transaction
 * which loaded it runs. {@link SpringDataUtilBasedAggregatePersistenceSupport} asks before
 * it saves. If the form did not change, it does not write.
 * <p>
 * The forms are kept per object, by identity, and per transaction. They are a resource of
 * the transaction, the same way Spring keeps a database connection. So a form never reaches
 * another delivery: another thread has its own transaction, and a transaction which this one
 * suspends has its own forms while it runs. When the transaction ends, the forms are dropped.
 * Without an active transaction synchronization nothing is remembered, and every save
 * writes.
 */
final class FormsAtLoad {

  /**
   * The key of the forms in the resources of the transaction. Each persistence support has
   * its own key, so two supports do not see each other's forms.
   */
  private final Object resourceKey = new Object();

  /**
   * Remembers the form of the given aggregate in the running transaction.
   *
   * @param aggregate The aggregate as it was loaded or written
   * @param form The form it was written in, <code>null</code> to forget it
   */
  void remember(
      final Object aggregate,
      final Object form) {

    final var forms = formsOfTheRunningTransaction();
    if (forms == null) {
      return;
    }
    if (form == null) {
      forms.remove(aggregate);
    } else {
      forms.put(aggregate, form);
    }

  }

  /**
   * Whether the given aggregate is known in the running transaction and still has the form
   * it had then.
   *
   * @param aggregate The aggregate to save
   * @param form The form it would be written in now
   * @return <code>true</code> if writing it would not change anything
   */
  boolean isUnchanged(
      final Object aggregate,
      final Object form) {

    if ((form == null) || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return false;
    }
    @SuppressWarnings("unchecked")
    final var forms = (Map<Object, Object>) TransactionSynchronizationManager.getResource(resourceKey);
    return (forms != null) && Objects.equals(forms.get(aggregate), form);

  }

  @SuppressWarnings("unchecked")
  private Map<Object, Object> formsOfTheRunningTransaction() {

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return null;
    }
    final var known = (Map<Object, Object>) TransactionSynchronizationManager.getResource(resourceKey);
    if (known != null) {
      return known;
    }
    final var forms = new IdentityHashMap<Object, Object>();
    TransactionSynchronizationManager.bindResource(resourceKey, forms);
    TransactionSynchronizationManager.registerSynchronization(new BoundToTheTransaction(forms));
    return forms;

  }

  /**
   * Keeps the forms bound to their transaction: away while it is suspended, back when it
   * resumes, and gone when it completes.
   */
  private final class BoundToTheTransaction implements TransactionSynchronization {

    private final Map<Object, Object> forms;

    private BoundToTheTransaction(
        final Map<Object, Object> forms) {

      this.forms = forms;

    }

    @Override
    public void suspend() {

      TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);

    }

    @Override
    public void resume() {

      TransactionSynchronizationManager.bindResource(resourceKey, forms);

    }

    @Override
    public void afterCompletion(
        final int status) {

      TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);

    }

  }

}
