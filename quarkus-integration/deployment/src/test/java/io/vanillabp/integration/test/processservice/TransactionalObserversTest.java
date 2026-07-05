package io.vanillabp.integration.test.processservice;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;
import lombok.Getter;

/**
 * Tests that Arc transactional observers work with Narayana-provided Synchronization registry.
 * All observers also make use of Request scoped bean so that we verify that the context is automatically activated.
 */
public class TransactionalObserversTest {

  @RegisterExtension
  static final QuarkusUnitTest config = new QuarkusUnitTest()
      .withApplicationRoot((
          jar) -> jar
              .addClass(DummyAdapters.class)                          // necessary due to anonymous class in DummyAdapters
              .addAsResource("application.yaml")                   // load sample application properties
              .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module")          // define workflow module at global classpath
              .addClasses(ObservingBean.class, Actions.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()); // add mocked adapter

  public static String AFTER_SUCCESS = "AFTER_SUCCESS";
  public static String AFTER_COMPLETION = "AFTER_COMPLETION";
  public static String AFTER_FAILURE = "AFTER_FAILURE";
  public static String BEFORE_COMPLETION = "BEFORE_COMPLETION";
  public static String PLAIN = "PLAIN";

  @BeforeEach
  public void before() {
    Actions.clear();
  }

  @Inject
  UserTransaction tx;

  @Inject
  Event<String> event;

  @Test
  public void testTransactionSuccessful() throws Exception {
    tx.begin();
    event.fire("commit");
    Assertions.assertEquals(1, Actions.getActions().size());
    Assertions.assertTrue(Actions.contains(TransactionalObserversTest.PLAIN));
    tx.commit();
    List<String> actions = Actions.getActions();
    Assertions.assertEquals(4, actions.size());
    Actions.contains(TransactionalObserversTest.AFTER_COMPLETION, TransactionalObserversTest.AFTER_SUCCESS,
        TransactionalObserversTest.BEFORE_COMPLETION);
    Actions.precedes(TransactionalObserversTest.BEFORE_COMPLETION, TransactionalObserversTest.AFTER_COMPLETION,
        TransactionalObserversTest.AFTER_SUCCESS);
  }

  @Test
  public void testTransactionFailed() throws Exception {
    tx.begin();
    event.fire("rollback");
    Assertions.assertEquals(1, Actions.getActions().size());
    Assertions.assertTrue(Actions.contains(TransactionalObserversTest.PLAIN));
    tx.rollback();
    Assertions.assertEquals(3, Actions.getActions().size());
    Actions.contains(TransactionalObserversTest.AFTER_COMPLETION, TransactionalObserversTest.AFTER_FAILURE);
  }

  @Test
  public void testOutsideTransaction() {
    System.err.println("Before: "
        + Actions.getActions());
    event.fire("outsideTx");
    System.err.println("After: "
        + Actions.getActions());
    Assertions.assertEquals(5, Actions.getActions().size());
    Actions.contains(TransactionalObserversTest.AFTER_COMPLETION, TransactionalObserversTest.AFTER_FAILURE,
        TransactionalObserversTest.BEFORE_COMPLETION, TransactionalObserversTest.AFTER_SUCCESS,
        TransactionalObserversTest.PLAIN);
  }

  @ApplicationScoped
  static class ObservingBean {

    @Inject
    private TransactionManager transactionManager;

    public void observeAfterSuccess(
        @Observes(during = TransactionPhase.AFTER_SUCCESS) String payload,
        ReqScopedBean bean) throws Exception {
      System.err.println("S: "
          + payload
          + " -> "
          + (transactionManager.getTransaction() != null));
      Actions.add(TransactionalObserversTest.AFTER_SUCCESS);
      bean.ping();
    }

    public void observeAfterFailure(
        @Observes(during = TransactionPhase.AFTER_FAILURE) String payload,
        ReqScopedBean bean) throws Exception {
      System.err.println("F: "
          + payload
          + " -> "
          + (transactionManager.getTransaction() != null));
      Actions.add(TransactionalObserversTest.AFTER_FAILURE);
      bean.ping();
    }

    public void observeAfterCompletion(
        @Observes(during = TransactionPhase.AFTER_COMPLETION) String payload,
        ReqScopedBean bean) throws Exception {
      System.err.println("C: "
          + payload
          + " -> "
          + (transactionManager.getTransaction() != null));
      Actions.add(TransactionalObserversTest.AFTER_COMPLETION);
      bean.ping();
    }

    public void observeBeforeCompletion(
        @Observes(during = TransactionPhase.BEFORE_COMPLETION) String payload,
        ReqScopedBean bean) throws Exception {
      System.err.println("B: "
          + payload
          + " -> "
          + (transactionManager.getTransaction() != null));
      Actions.add(TransactionalObserversTest.BEFORE_COMPLETION);
      bean.ping();
    }

    public void classicObserver(
        @Observes String payload,
        ReqScopedBean bean) throws Exception {
      System.err.println("-: "
          + payload
          + " -> "
          + (transactionManager.getTransaction() != null));
      Actions.add(TransactionalObserversTest.PLAIN);
      bean.ping();
    }
  }

  @RequestScoped
  static class ReqScopedBean {
    // just to verify that the context gets activated for OMs
    public void ping() {
    }
  }

  static class Actions {

    @Getter
    private static List<String> actions = new ArrayList<String>();

    public static void clear() {
      actions.clear();
    }

    public static boolean add(
        Object o) {
      return actions.add(o.toString());
    }

    public static boolean isSequence(
        Object... seq) {
      int i = 0;
      return objectsToStrings(seq).equals(actions);
    }

    // true iff obj exists and all otherObjects exist and indexOf(obj) < indexOf(x) for each x from otherObjects
    public static boolean precedes(
        Object obj,
        Object... otherObjects) {
      boolean precedes = true;
      int i = 0;
      if (precedes = (Actions.contains(obj) && Actions.contains(otherObjects))) {
        while (i < otherObjects.length && (precedes = precedes && actions.indexOf(obj.toString()) < actions
            .indexOf(otherObjects[i++].toString())))
          ;
      }
      return precedes;
    }

    public static boolean startsWith(
        Object... objects) {
      return actions.subList(0, objects.length).equals(objectsToStrings(objects));
    }

    public static boolean endsWith(
        Object... objects) {
      return actions.subList(actions.size() - objects.length, actions.size()).equals(objectsToStrings(objects));
    }

    public static boolean contains(
        Object... objects) {
      return actions.containsAll(objectsToStrings(objects));
    }

    private static List<String> objectsToStrings(
        final Object... objects) {
      List<String> result = new ArrayList<String>();
      for (Object obj : objects) {
        result.add(obj.toString());
      }
      return result;
    }
  }

}
