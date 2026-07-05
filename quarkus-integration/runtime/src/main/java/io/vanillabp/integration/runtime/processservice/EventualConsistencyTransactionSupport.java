package io.vanillabp.integration.runtime.processservice;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Supports eventual consistency of remote BPMSs by running probes right before a
 * transaction is committed and actions right after the transaction was committed
 * successfully.
 * <p>
 * Typical usage: before committing the local transaction, a probe checks whether the
 * remote BPMS is in the expected state (e.g. knows the workflow to be processed). If
 * any probe fails, the local transaction is rolled back. After a successful commit,
 * actions are executed to inform the remote BPMS (e.g. complete a task).
 * <p>
 * <strong>Design:</strong> This bean is {@link ApplicationScoped} and keeps its
 * per-transaction state in the {@link TransactionSynchronizationRegistry}
 * ({@link TransactionSynchronizationRegistry#getResource(Object)} /
 * {@link TransactionSynchronizationRegistry#putResource(Object, Object)}), so no
 * transaction-scoped bean lifecycle is needed. On the first
 * {@link #addProbeAndAction(Supplier, Supplier, Consumer, Supplier)} of a transaction an
 * interposed {@link Synchronization} is registered: probes run (and may mark the
 * transaction rollback-only) in {@link Synchronization#beforeCompletion()} — both is
 * allowed there — and after-commit actions run in
 * {@link Synchronization#afterCompletion(int)} if the transaction was committed.
 * <p>
 * A previous implementation used a <code>&#64;TransactionScoped</code> bean running the
 * probes in a <code>&#64;PreDestroy</code> callback. That cannot work in Quarkus: the
 * transaction-scoped context is destroyed in an <code>afterCompletion()</code>
 * synchronization (see <a href="https://github.com/quarkusio/quarkus/issues/36880">
 * Quarkus issue #36880</a>), so <code>&#64;PreDestroy</code> runs <i>after</i>
 * commit/rollback. At that point the transaction status is never
 * <code>STATUS_ACTIVE</code>, <code>setRollbackOnly()</code> would be too late and the
 * JTA specification forbids registering further synchronizations.
 */
@ApplicationScoped
@Slf4j
public class EventualConsistencyTransactionSupport {

  /**
   * A probe to be run before committing the transaction and an action to be run after
   * the transaction was committed successfully.
   */
  @Getter
  @RequiredArgsConstructor
  public static class ToDo {

    /**
     * The result of the probe, passed to the after-commit action.
     */
    Object context;

    final Supplier<Object> onCommitProbe;

    final Supplier<String> commitProbeDescription;

    final Consumer<Object> afterCommitAction;

    final Supplier<String> commitActionDescription;

  }

  /**
   * The key used to store this bean's per-transaction state in the
   * {@link TransactionSynchronizationRegistry}.
   */
  private static final String TODOS_KEY = EventualConsistencyTransactionSupport.class.getName()
      + ".toDos";

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  /**
   * Adds a probe to be run right before the current transaction is committed and an
   * action to be run right after the transaction was committed successfully. If any
   * probe fails, the transaction is marked rollback-only.
   *
   * @param onCommitProbe The probe; its result is passed to the after-commit action
   * @param commitProbeDescription A description of the probe used for logging
   * @param afterCommitAction The action run after a successful commit; may be null
   * @param commitActionDescription A description of the action used for logging
   */
  public void addProbeAndAction(
      final Supplier<Object> onCommitProbe,
      final Supplier<String> commitProbeDescription,
      final Consumer<Object> afterCommitAction,
      final Supplier<String> commitActionDescription) {

    if (txRegistry.getTransactionKey() == null) {
      throw new IllegalStateException(
          "No transaction active! Probes and after-commit actions can only be registered within a transaction.");
    }

    @SuppressWarnings("unchecked")
    var toDos = (List<ToDo>) txRegistry.getResource(TODOS_KEY);
    if (toDos == null) {
      final var newToDos = new LinkedList<ToDo>();
      txRegistry.putResource(TODOS_KEY, newToDos);
      // register the synchronization only once per transaction
      txRegistry.registerInterposedSynchronization(new Synchronization() {
        @Override
        public void beforeCompletion() {
          runProbes(newToDos);
        }

        @Override
        public void afterCompletion(
            final int status) {
          if (status == Status.STATUS_COMMITTED) {
            runAfterCommitActions(newToDos);
          }
        }
      });
      toDos = newToDos;
    }

    toDos.add(new ToDo(
        onCommitProbe, commitProbeDescription, afterCommitAction, commitActionDescription));

  }

  /**
   * Runs all probes collected for the transaction to be committed. If any probe fails,
   * the transaction is marked rollback-only.
   *
   * @param toDos The probes and actions collected for the current transaction
   */
  private void runProbes(
      final List<ToDo> toDos) {

    Supplier<String> commitProbeDescription = null;
    try {

      for (final ToDo toDo : toDos) {

        if (log.isTraceEnabled()) {
          log.trace("Doing pre-commit probe for '{}'", toDo.commitProbeDescription.get());
        }
        commitProbeDescription = toDo.commitProbeDescription;
        toDo.context = toDo.onCommitProbe.get();

      }

    } catch (Exception e) {

      log.error("Will rollback because pre-commit testing failed for '{}'",
          commitProbeDescription == null ? "unknown" : commitProbeDescription.get(), e);
      txRegistry.setRollbackOnly();

    }

  }

  /**
   * Runs all after-commit actions collected for the transaction committed. Since the
   * transaction was already committed, a failing action is only logged.
   *
   * @param toDos The probes and actions collected for the committed transaction
   */
  private void runAfterCommitActions(
      final List<ToDo> toDos) {

    for (final ToDo toDo : toDos) {

      if (toDo.afterCommitAction == null) {
        continue;
      }
      try {
        if (log.isTraceEnabled()) {
          log.trace("Doing after-commit action for '{}'", toDo.commitActionDescription.get());
        }
        toDo.afterCommitAction.accept(toDo.context);
      } catch (Exception e) {
        log.error("After-commit action failed for '{}'",
            toDo.commitActionDescription == null ? "unknown" : toDo.commitActionDescription.get(), e);
      }

    }

  }

}
