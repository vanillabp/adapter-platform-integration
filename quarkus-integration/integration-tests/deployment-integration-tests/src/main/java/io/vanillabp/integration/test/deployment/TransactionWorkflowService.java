package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * The workflow service of the transaction-contract acceptance test (story 40b). None of
 * the handlers reaching the nested transactional bean carries a transaction annotation
 * itself: they are the case the startup check cannot cover and the runtime check has to
 * catch.
 * <p>
 * {@link #acceptedAnnotation} carries the annotation a VanillaBP 1 application brings
 * along, which has to boot and to work.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TransactionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TransactionProcess"))
public class TransactionWorkflowService {

  @Inject
  NestedTransactionalBean nestedTransactionalBean;

  /**
   * The handler lets the {@link TaskException} of the nested bean pass, so VanillaBP
   * would treat it as a BPMN error and commit the aggregate. The nested bean's
   * interceptor has marked the transaction rollback-only by then.
   */
  @WorkflowTask
  public void nestedTaskException(
      final TransactionAggregate aggregate) {

    aggregate.setStatus("about-to-fail");
    nestedTransactionalBean.raiseTaskException();

  }

  /**
   * The variant without any {@code TaskException}: the handler swallows the exception of
   * the nested call and returns normally, which would report the task as completed while
   * nothing can be persisted.
   */
  @WorkflowTask
  public void swallowedNestedFailure(
      final TransactionAggregate aggregate) {

    try {
      nestedTransactionalBean.fail();
    } catch (final RuntimeException e) {
      aggregate.setStatus("swallowed");
    }

  }

  @WorkflowTask
  @Transactional(dontRollbackOn = TaskException.class)
  public void acceptedAnnotation(
      final TransactionAggregate aggregate) {

    aggregate.setStatus("accepted");

  }

}
