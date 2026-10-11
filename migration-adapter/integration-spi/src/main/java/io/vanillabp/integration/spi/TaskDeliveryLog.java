package io.vanillabp.integration.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The durable memory of the task deliveries VanillaBP already processed. It is the
 * inbound counterpart of the {@link PhaseTwoOutbox}. A remote BPMS delivers a task AT
 * LEAST ONCE. It repeats a delivery whenever it did not learn the result, e.g. after a
 * crash between the commit of the local transaction and the report to the BPMS.
 * Without a memory, the <code>&#64;WorkflowTask</code> method runs a second time. With
 * one, the core skips the business code and reports the recorded outcome again.
 * <p>
 * <strong>Recording contract:</strong> call {@link #record(TaskDelivery)} inside the
 * transaction which persists the workflow aggregate, while it still runs. The
 * implementation MUST enlist the record in exactly that transaction. The record becomes
 * visible if and only if the transaction commits. Work without a record would run the
 * business code twice. A record without the work would skip it, although nothing
 * happened. Both are worse than no deduplication at all. So a delivery which is rolled
 * back leaves NO record and is processed again. That is what makes the retry of the BPMS
 * work.
 * <p>
 * <strong>Uniqueness:</strong> the {@link TaskDelivery#deliveryKey()} is unique in the
 * store. The store enforces this with its own mechanism (unique index or constraint). A
 * second {@link #record(TaskDelivery)} of the same key does nothing and returns
 * <code>false</code>. So two nodes which process the same delivery at the same time (both
 * read no record) end up with one record. Nothing is rolled back then, because both
 * handlers really ran. The core writes one WARN which names the delivery, the adapter and
 * the workflow, and counts the case as
 * <code>vanillabp.task.redeliveries.concurrent</code>.
 * <p>
 * <strong>A delivery nobody deduplicates:</strong> an adapter may report no delivery identity.
 * This says that it never repeats a delivery. The core still writes a record for it, because
 * the record answers two more questions than the deduplication: which BPMS holds the task, and
 * which kind of id its id is. Both answers were lost without the row. Such a record has a key
 * of its own, which belongs to one row and to no delivery. So
 * {@link #recordedDelivery(String)} is never called with it, and nothing can land on it twice.
 * A store needs no code for this. It writes and reads the key as text, as always.
 * <p>
 * <strong>The start of a workflow:</strong> the log also holds one row for each workflow which
 * was started. It is written in the transaction which persists the aggregate. It carries the
 * workflow aggregate and the BPMS' own id of the workflow, and nothing about a task. So the
 * BPMS election and an extension can read that id without asking a BPMS (see
 * {@link #workflowStartOf(String, String, String)}). This means three things for a store. All
 * three are about telling the row apart from a delivery by {@link TaskDelivery#recordKind()}.
 * First, no question about open work may count it, because a row without a task would show up
 * as a phantom task in each of those answers. Second, {@link #releaseRecordsOf} must leave it
 * where it is, because changes to an aggregate keep arriving after its workflow ended. Third,
 * it has a retention of its own. It is not a delivery, which may go as soon as nobody can
 * repeat it.
 * <p>
 * <strong>Retention:</strong> records are deleted asynchronously once
 * <code>vanillabp.delivery.retention</code> has passed. It defaults to
 * <code>vanillabp.outbox.retention</code> (7 days). It is a property of its own since the
 * two stopped being the same kind of thing (see decision 24 in the repository's DECISIONS.md).
 * HERE the number decides correctness. A BPMS which
 * redelivers a task later than that finds no record and runs the business code a second
 * time. On the OUTBOX side, the deduplication window ends with the dispatch (see decision
 * 22 in the repository's DECISIONS.md). There the retention only decides how long a
 * dispatched entry stays readable for support. So an installation which keeps its outbox
 * table small no longer shortens this window by accident. The period counts from the last
 * redelivery the record answered ({@link #stillOpen(String)}). So while a task stays open,
 * its record keeps answering, and the clock only starts once nobody hands that task out
 * any more.
 * <p>
 * A record of a delivery nobody deduplicates is kept exactly as long, and the same statement
 * deletes it. For it the period counts from the moment the handler ran, because no repetition
 * ever arrives to move it. Correctness does not depend on that number here, because the
 * record answers no repetition anyway. A deleted record costs two things: the saved round trip
 * to the BPMS for a task operation, and the clearer message of a failure which names the kind
 * of an id. An installation whose tasks stay open longer than the retention, and which wants
 * both, raises the retention.
 * <p>
 * The row about the start of a workflow is kept for
 * <code>vanillabp.delivery.workflow-start-retention</code> instead. This period counts from the
 * moment the workflow started, and the release at the end of a workflow does not delete the
 * row. Correctness does not depend on that number either. A deleted row costs this: the id of
 * that workflow is not known any more. So a caller which wanted to report a changed aggregate
 * finds no id, and the report is dropped without a word.
 * <p>
 * <strong>Release at the end of a workflow:</strong> if
 * <code>vanillabp.delivery.release-on-workflow-end</code> is switched on, the records of
 * a workflow are deleted the moment it ends. Nothing of an ended instance can be
 * redelivered, so the clock does not have to decide it any more (see
 * {@link #releaseRecordsOf(String, String, String, Instant)}). A store which does not
 * implement the release keeps its records until the retention has passed. If the release
 * was asked for, the startup says so.
 * <p>
 * The platform integrations (JDBC/JPA and MongoDB) or the application itself provide the
 * implementations, because the platform-neutral core must not depend on any particular
 * persistence technology. Which log serves a workflow aggregate is decided for each
 * aggregate (see {@link TaskDeliveryLogAware}).
 * <p>
 * Why a delivery is written down at all, and why the record carries two timestamps instead of one,
 * is decision 6 in the repository's DECISIONS.md. Why the adapter id has to travel with it is
 * decision 17 in the repository's DECISIONS.md, and why the record also answers which adapter
 * holds a task is decision 30 in the repository's DECISIONS.md.
 */
public interface TaskDeliveryLog {

  /**
   * The record of the given delivery, if VanillaBP processed it before. Read it inside
   * the transaction the delivery is processed in.
   *
   * @param deliveryKey The delivery's identity (see
   *          {@link TaskDelivery#deliveryKey()})
   * @return The record or {@link Optional#empty()} if this delivery was not
   *         processed (or its record was cleaned up after the retention period)
   */
  Optional<TaskDelivery> recordedDelivery(
      String deliveryKey);

  /**
   * Records a processed delivery. Call it inside the transaction which persists the
   * workflow aggregate, while that transaction still runs. The implementation MUST
   * enlist in that transaction.
   *
   * @param delivery What was processed, and with which outcome
   * @return <code>true</code> if the record was written, <code>false</code> if a
   *         record of the same {@link TaskDelivery#deliveryKey()} already existed
   *         (no-op, which the core reports as two deliveries having run at the same
   *         time)
   */
  boolean record(
      TaskDelivery delivery);

  /**
   * Writes the row about the start of a workflow. It REPLACES the row of an earlier workflow of
   * the same aggregate. If the BPMS reported the start itself, it is called inside the transaction
   * which persists the aggregate. If VanillaBP started the workflow, it is called inside the
   * transaction of the dispatch.
   *
   * <h4>Why this is not {@link #record(TaskDelivery)}</h4>
   *
   * One aggregate may carry a SECOND workflow once the first one ended. The row is keyed by the
   * aggregate and the BPMN process, not by the workflow ({@link WorkflowStartKey}). That is what
   * makes the read a lookup. An insert which refuses a duplicate would leave the id of the FIRST
   * workflow in place, and answer every question about the second one with it. So a row with a
   * different workflow id is overwritten. A row which already names this workflow is left alone,
   * so a start dispatched twice writes nothing.
   * <p>
   * One thing may still be added to a row which already names this workflow: its
   * {@link TaskDelivery#processVersion()}, if the row has none and the new one has. The start of
   * one workflow can be reported twice, once by the adapter which started it and once by the
   * BPMS itself, and only one of the two may know the version. A version which a row already
   * holds is never changed, because it is the version the workflow started on.
   * <p>
   * The default inserts and keeps what is there. That is what a store written before this method
   * existed does. Such a store answers the id of a second workflow on one aggregate with the id of
   * the first one, until the retention deletes the row. So a store which can update implements
   * this. The stores VanillaBP ships do.
   *
   * @param workflowStart The row, built by
   *          {@link TaskDelivery#workflowStart(String, String, String, String, String, String, java.time.Instant)}
   * @return <code>true</code> if the store now holds the id or the version of this workflow for
   *         the first time, <code>false</code> if it held everything already
   */
  default boolean recordWorkflowStart(
      final TaskDelivery workflowStart) {

    return record(workflowStart);

  }

  /**
   * Reports that the BPMS delivered a task again, and its record says the task is still
   * open. So this record is still in use and has to outlive the retention. The core calls
   * it inside the transaction of that redelivery, for every delivery answered with
   * <code>COMPLETION_PENDING</code>.
   * <p>
   * A store which deletes by age keeps a SECOND timestamp per record for this. The
   * retention counts from the last redelivery. {@link TaskDelivery#recordedAt()} keeps
   * meaning the moment the handler ran, because the age of an open task is measured from
   * there (<code>vanillabp.delivery.max-task-age</code>). Refreshing
   * <code>recordedAt</code> instead would erase that age. And keeping every pending record
   * forever would keep the record of a task nobody ever completes in the store for good.
   * <p>
   * <strong>Not a write per call.</strong> The BPMS redelivers an open task each time it
   * renews its lock, and the redelivery runs in the transaction of the workflow aggregate.
   * So implementations remember the key and refresh their records in bulk, separately. The
   * stores VanillaBP ships do it from the timer which already runs the retention cleanup.
   * If a crash loses this memory, it costs one interval of refreshes and nothing more. A
   * record only expires when a whole retention passes without a single refresh.
   * <p>
   * The default implementation does nothing. So every store written before this method
   * existed stays valid, and its records expire as they always did.
   *
   * @param deliveryKey The identity of the redelivered delivery (see
   *          {@link TaskDelivery#deliveryKey()})
   */
  default void stillOpen(
      final String deliveryKey) {

  }

  /**
   * The adapter ids which the OPEN records of the BPMN process of one workflow module belong
   * to. An open record has the outcome <code>COMPLETION_PENDING</code>: VanillaBP still
   * answers redeliveries of that task.
   * <p>
   * This is asked once at startup, for one question only. If an adapter id holds open
   * records but is not configured any more, the id was RENAMED or removed too early. Both
   * cost the same, and today nobody notices either of them. The delivery key is built from
   * the delivering adapter. So under a new name a redelivery finds no record, and the
   * <code>&#64;WorkflowTask</code> method runs a second time.
   * <p>
   * The default answers an empty set, which means "this store cannot say". The check is
   * then skipped and nothing is made up. A store which implements it answers a cheap query
   * over {@link TaskDelivery#adapterId()}, {@link TaskDelivery#workflowModuleId()} and
   * {@link TaskDelivery#bpmnProcessId()}. Records written before that field existed carry
   * no adapter id, and they are not part of any answer.
   *
   * @param workflowModuleId The workflow module to ask about
   * @param bpmnProcessId The BPMN process to ask about
   * @return The adapter ids of open records, empty if the store cannot say
   */
  default java.util.Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return java.util.Set.of();

  }

  /**
   * Deletes the records of ONE workflow. It is called when the workflow ended and nothing
   * of it can be redelivered any more. It runs inside the transaction of that notification,
   * so the deletion commits with whatever else that transaction does.
   * <p>
   * The four arguments limit the deletion, and each of them matters. An aggregate may carry
   * more than one BPMN process. It may also outlive its workflow and carry a SECOND one
   * afterwards. The records of that second workflow were written AFTER the notification and
   * must survive it.
   * <p>
   * The default implementation deletes nothing. So every store written before this method
   * existed stays valid, and the retention cleans up its records as it always did.
   * VanillaBP reports at startup that the release was asked for but cannot be done, and
   * names the store and the property.
   *
   * @param workflowModuleId The workflow module of the ended workflow
   * @param bpmnProcessId The BPMN process of the ended workflow
   * @param workflowAggregateId The ID of its workflow aggregate
   * @param recordedBefore Only records written before this moment are deleted
   * @return The number of records deleted
   */
  default int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Instant recordedBefore) {

    return 0;

  }

  /**
   * The record which left the given task open, whether or not that task has been closed
   * since. Without it, the BPMS election of a task operation would have to ask every
   * configured BPMS this one question.
   *
   * <h4>What the core does with it</h4>
   *
   * A record whose {@link TaskDelivery#taskClosedAt()} is <code>null</code> names the
   * adapter which delivered that task. That adapter is the one which holds it, so no BPMS
   * is asked. A record which carries the moment says that the application already completed
   * or cancelled this task. The operation then does nothing except log a warning, as it
   * always did. If there is no record at all, the election asks the adapters one by one,
   * exactly as before. This happens if the store cannot answer, if no delivery was ever
   * recorded, or if the retention deleted the record. This fallback keeps the answer a hint
   * and not a registry (see decision 30 in the repository's DECISIONS.md).
   *
   * <h4>What a store has to answer</h4>
   *
   * The record of the delivery which reported <code>COMPLETION_PENDING</code> for this task.
   * If a task was delivered more than once, it is the most recently written one. A store
   * which cannot be queried by the task id answers {@link Optional#empty()}, and so does the
   * default. So every store written before this method existed keeps working, and its
   * application pays the round trips it always paid.
   * <p>
   * This is asked on the thread of the caller, inside the transaction of the caller, once per
   * task operation. So it has to be a cheap indexed read, not a scan.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param taskId The BPMS' identity of the task (see {@link TaskDelivery#taskId()})
   * @return The record or {@link Optional#empty()} if there is none or this store cannot
   *         say
   */
  default Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return Optional.empty();

  }

  /**
   * Writes down that this task is over. Then the next operation on it is answered as a
   * no-op, without asking anybody.
   * <p>
   * Two moments call it. The first is the completion or cancellation by the application. It
   * calls this AFTER phase two succeeded, on the thread which dispatches it, and not when the
   * caller asked. Until phase two ran, the task is still open for the BPMS. Some BPMS hand a
   * task out again to renew its lock, and exactly this record answers those redeliveries. A
   * second call in that window is already refused by the idempotency key of the outbox (see
   * decision 22 in the repository's DECISIONS.md). This closes the gap after that: the key is
   * free again once the entry was dispatched, and from then on the record answers. The second
   * moment is the BPMS. It calls this through a delivery of
   * {@link io.vanillabp.spi.service.TaskEvent.Event#CANCELED}, which closes the record at once.
   * A task the BPMS took away is never handed out again, so there is no lock left to renew.
   * <p>
   * <b>Every</b> record which names that task is closed, not only the newest one. A task may
   * have more than one record. For example, the delivery which handed it to the application and
   * the delivery which reported its cancelation share a task id. A record left open keeps the
   * task alive for everything which reads the open work. Why all of them and not one is
   * decision 72 in the repository's DECISIONS.md.
   * <p>
   * A record which was closed before keeps the moment it was closed at. So the count says how
   * many records this call closed, and a caller can use it to claim a task. If two application
   * instances derive the same cancelation, both call this, and only one of them reads a number
   * above zero.
   * <p>
   * A store which cannot do it does nothing, and so does the default. The election then asks
   * about a task which is gone, and gets the same no-op one round trip later.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @param taskId The BPMS' identity of the closed task
   * @return The number of records this call marked
   */
  default int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return 0;

  }

  /**
   * The open tasks of ONE workflow aggregate. This is the durable answer to the question an
   * extension asks before it shows what is waiting for somebody.
   *
   * <h4>What "open" means</h4>
   *
   * A record is open if its outcome is <code>COMPLETION_PENDING</code>, so the handler left the
   * task to the application, AND if {@link #markTaskClosed} has not stamped it. Then the
   * completion or cancellation the application asked for has not reached the BPMS yet, or it
   * was never asked for. The moment {@link #markTaskClosed} writes takes a task out of this
   * answer. So a task completed in the same second is gone from it as soon as phase two
   * succeeded, not when the caller asked. A record which the retention deleted is gone as
   * well. That is why this answers what the store still knows, not what the BPMS holds.
   *
   * <h4>Which tasks are in it at all</h4>
   *
   * The tasks the APPLICATION was handed. A record is written where a
   * <code>&#64;WorkflowTask</code> method ran. So a user task without a method in the
   * application is not in this answer, and never was. The adapter DOES hand such a task to
   * VanillaBP, because the lifecycle listeners sit on every user task of the model. It finishes
   * the notification itself once it sees that no method serves it, and that leaves nothing to
   * write down. A record carries the outcome of a delivery, and a delivery nobody processed has
   * none. So this answer is the open work of the application, not the open work of the
   * workflow. A caller which needs the second one asks the BPMS. Why the log stays what it is
   * is decision 54 in the repository's DECISIONS.md.
   * <p>
   * The BPMN process is the one which DELIVERED the task. For a task which a called process
   * handed out, that is the secondary id, not the id the application completes it on. So a
   * caller which wants everything a workflow aggregate waits for asks once for each BPMN
   * process the workflow service serves. The core's own read does the same.
   *
   * <h4>Ordering and size</h4>
   *
   * Oldest first, by {@link TaskDelivery#recordedAt()}. That is the order in which the tasks
   * were handed to the application, which is how a list of open work reads. Records with the
   * same moment may come in any order.
   * <p>
   * There is no paging and no limit, because the MODEL limits the answer, not the age of the
   * application. A workflow has as many open tasks as its BPMN process has tokens waiting at
   * user tasks. That is a handful, and a multi-instance element limits it by its collection.
   * If an aggregate carried a SECOND workflow under another BPMN process, that workflow adds
   * nothing here, because the process is part of the question.
   *
   * <h4>What it costs</h4>
   *
   * This is asked once per screen an extension builds, on the thread of the caller. A store
   * answers it from an index over the open records, and does not walk everything it ever
   * recorded. The stores VanillaBP ships say in their own documentation which index that is.
   * <p>
   * The default answers an empty list, as a store written before this method existed does. A
   * caller which needs the open tasks and gets none has to be able to live with it. In the
   * same way, {@link #recordOfTask} lets the election fall back to asking each BPMS.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @return The open records, oldest first, empty if there are none or this store cannot
   *         say
   */
  default List<TaskDelivery> openTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return List.of();

  }

  /**
   * The open tasks of ONE workflow of the BPMS. The core reads this on every wake-up, to see
   * which other tasks of that workflow it still believes are open.
   *
   * <h4>How this differs from {@link #openTasksOfAggregate}</h4>
   *
   * The question is the same, the key is not. That method is keyed by workflow module, BPMN
   * process and aggregate. An extension asks that question: it holds an aggregate and wants
   * everything waiting for somebody. This method is keyed by the BPMS' own id of the running
   * instance. A delivery raises that question: a job arrived for one workflow, and what else is
   * open IN THAT WORKFLOW decides what may be asked.
   * <p>
   * The different key has three results. An aggregate may carry a second workflow, and that one
   * is not in this answer. A task which a called process handed out carries the secondary BPMN
   * process id. So a read by aggregate has to be repeated for each BPMN process the workflow
   * service serves, while this read covers them all at once. And this read is cheap. The id is
   * {@code VARCHAR(255)} and indexed, while the id of the aggregate holds up to 1024 characters
   * and has no portable index.
   *
   * <h4>Which records it cannot see</h4>
   *
   * A record whose adapter named no workflow. {@link TaskDelivery#workflowId()} is filled from
   * what the adapter reports. If an adapter reports none, the column stays empty, and the
   * records of such an adapter are not in this answer and never will be. Neither is a record
   * written before the column existed. The Camunda 7, Camunda 8 and Process-Engine-API adapters
   * VanillaBP ships all report the id.
   * <p>
   * What "open" means, which tasks are in it at all, the ordering and the missing limit are
   * exactly as {@link #openTasksOfAggregate} describes them.
   *
   * <h4>What it costs</h4>
   *
   * This is asked once per delivery of a workflow whose BPMN process has any asynchronous task,
   * on the thread of the delivery. So it has to be an indexed read. The stores VanillaBP ships
   * index the workflow id and say so in their own documentation.
   * <p>
   * The default answers an empty list, as a store written before this method existed does. A
   * caller then derives nothing, which is how every VanillaBP behaved before this read
   * existed.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param workflowId The BPMS' own id of the workflow (see {@link TaskDelivery#workflowId()})
   * @return The open records, oldest first, empty if there are none or this store cannot
   *         say
   */
  default List<TaskDelivery> openTasksOfWorkflow(
      final String workflowModuleId,
      final String workflowId) {

    return List.of();

  }

  /**
   * The row about the START of the workflow of one aggregate. It says where that workflow runs in
   * the BPMS, as far as this store was told.
   *
   * <h4>What the core does with it</h4>
   *
   * The BPMS election of an operation about a WORKFLOW asks every configured adapter until one
   * says it holds the workflow, and nothing is written down about the result. The id in this row
   * makes the asking unnecessary. Some BPMS answer such a question from a read model. There it is
   * also the only answer shortly after the start, because the read model has not caught up yet.
   * An extension which asks where a workflow is gets the same value.
   * <p>
   * It says what was true when the workflow started, not whether that workflow still runs. So it
   * does not make an operation on an instance the BPMS has forgotten succeed. Knowing the id and
   * sending something to it are two different uses of one row.
   *
   * <h4>What a store has to answer</h4>
   *
   * The row is stored under a key derived from the three arguments ({@link WorkflowStartKey}).
   * So the default answers it with the lookup by key which every store implements already. No
   * store needs code for this, and none needs an index. A store which holds no such row answers
   * {@link Optional#empty()}, and so does a store whose retention deleted it.
   *
   * @param workflowModuleId The workflow module of the workflow
   * @param bpmnProcessId The BPMN process of the workflow
   * @param workflowAggregateId The workflow aggregate's ID in serialized form
   * @return The row, or {@link Optional#empty()} if there is none
   */
  default Optional<TaskDelivery> workflowStartOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    return recordedDelivery(
        WorkflowStartKey.of(workflowModuleId, bpmnProcessId, workflowAggregateId))
        .filter(row -> DeliveryRecordKind.of(row.recordKind()) == DeliveryRecordKind.WORKFLOW_START);

  }

  /**
   * Whether this store holds any OPEN record for the given BPMN process. An open record has
   * the outcome <code>COMPLETION_PENDING</code>.
   *
   * <h4>Why this is not {@link #adapterIdsOfOpenTasks}</h4>
   *
   * That method answers an empty set both for "there are none" and for "I cannot say". That
   * is right for its use: an adapter id nobody configured is only worth reporting if a store
   * can name one. Here the EMPTY case is the interesting one, so the two cases have to be told
   * apart. So this method returns <code>null</code> for "I cannot say".
   *
   * <h4>What it means if there are none</h4>
   *
   * VanillaBP writes a record when a handler runs. Every redelivery which the record of an open
   * task answers keeps it alive. So if a store holds NO open record while the BPMS holds open
   * tasks of that process, the store never saw them. That is what an upgrade from version 1
   * looks like. It makes the handlers of those tasks run a second time when the BPMS delivers
   * them again.
   *
   * @param workflowModuleId The workflow module to ask about
   * @param bpmnProcessId The BPMN process to ask about
   * @return <code>true</code> or <code>false</code>, or <code>null</code> if this store
   *         cannot say
   */
  default Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return null;

  }

}
