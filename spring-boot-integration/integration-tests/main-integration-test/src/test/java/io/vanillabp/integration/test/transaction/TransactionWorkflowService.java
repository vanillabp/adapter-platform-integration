package io.vanillabp.integration.test.transaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the acceptance test for the transaction contract. Some handlers
 * call the nested transactional bean. None of them carries a transaction annotation itself.
 * The startup check cannot cover this case, so the check at runtime has to catch it.
 * <p>
 * {@link #acceptedAnnotation} carries the annotation which a VanillaBP 1 application brings
 * along. The application has to boot with it, in this test and in every other test of this
 * module. This is exactly what the startup check promises for it.
 */
@WorkflowService(
    workflowAggregateClass = TransactionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TransactionProcess"))
public class TransactionWorkflowService {

  @Autowired
  private NestedTransactionalBean nestedTransactionalBean;

  /**
   * The handler lets the {@link TaskException} of the nested bean pass. So VanillaBP would
   * treat it as a BPMN error and commit the aggregate. But by then, the interceptor of the
   * nested bean has marked the transaction rollback-only.
   */
  @WorkflowTask
  public void nestedTaskException(
      final TransactionAggregate aggregate) {

    aggregate.setStatus("about-to-fail");
    nestedTransactionalBean.raiseTaskException();

  }

  /**
   * The variant without any {@code TaskException}. The handler swallows the exception of the
   * nested call and returns normally. This would report the task as completed, although
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
  @Transactional(noRollbackFor = TaskException.class)
  public void acceptedAnnotation(
      final TransactionAggregate aggregate) {

    aggregate.setStatus("accepted");

  }

}
