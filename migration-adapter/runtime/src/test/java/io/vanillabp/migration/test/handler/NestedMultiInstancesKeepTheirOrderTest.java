package io.vanillabp.migration.test.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.handler.HandlerContexts;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Nested multi-instance elements are read from the outside in, so the scopes of one
 * invocation are worth nothing without their order. A workflow task always carried it;
 * a call an extension builds lost it while the call was closed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NestedMultiInstancesKeepTheirOrderTest {

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  private @interface Note {
  }

  private static final HandlerMultiInstance ONE_ITERATION = new HandlerMultiInstance("element", 0, 3);

  @Test
  @DisplayName("A call an extension builds keeps its scopes outermost first")
  public void aCallKeepsTheOrderTheScopesWereAddedIn() {

    final var call = HandlerCall
        .of(Note.class, "loan-approval", "LoanApproval")
        .workflowAggregateId("4711")
        .multiInstance("countries", ONE_ITERATION)
        .multiInstance("branches", ONE_ITERATION)
        .multiInstance("applications", ONE_ITERATION)
        .build();

    assertEquals(
        List.of("countries", "branches", "applications"),
        List.copyOf(call.getMultiInstances().keySet()));

  }

  @Test
  @DisplayName("An invocation of a workflow task keeps the order its adapter reported")
  public void aWorkflowTaskKeepsTheOrderTheAdapterReported() {

    final var reportedByTheAdapter = new LinkedHashMap<String, MultiInstanceValue>();
    reportedByTheAdapter.put("countries", new MultiInstanceValue("element", 0, 3));
    reportedByTheAdapter.put("branches", new MultiInstanceValue("element", 0, 3));
    reportedByTheAdapter.put("applications", new MultiInstanceValue("element", 0, 3));

    assertEquals(
        List.of("countries", "branches", "applications"),
        List.copyOf(HandlerContexts.adapt(reportedByTheAdapter).keySet()));

  }

}
