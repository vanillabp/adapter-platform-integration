package io.vanillabp.integration.runtime.processservice;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@TransactionScoped
@Slf4j
public class EventualConsistencyTransactionSupport {

  @Getter
  @RequiredArgsConstructor
  public class ToDo {

    Object context;

    final Supplier<Object> onCommitProbe;

    final Supplier<String> commitProbeDescription;

    final Consumer<Object> afterCommitAction;

    final Supplier<String> commitActionDescription;

  }

  @Inject
  TransactionManager transactionManager;

  List<ToDo> toDos = new LinkedList<>();

  public void addProbeAndAction(
      final Supplier<Object> onCommitProbe,
      final Supplier<String> commitProbeDescription,
      final Consumer<Object> afterCommitAction,
      final Supplier<String> commitActionDescription) {

    toDos.add(new ToDo(
        onCommitProbe, commitProbeDescription, afterCommitAction, commitActionDescription));

  }

  @PreDestroy
  void onBeforeEndTransaction() throws Exception {

    // if transaction is rolled back, then we don't need to do anything
    if (transactionManager.getStatus() != Status.STATUS_ACTIVE) {
      return;
    }

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
          commitProbeDescription == null ? "unknown" : commitProbeDescription.get());
      transactionManager.setRollbackOnly();

    }

  }

}
