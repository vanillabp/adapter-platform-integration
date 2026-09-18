package io.vanillabp.integration.test.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An extension picks the method by the version of the BPMN process its event came from,
 * with the selection VanillaBP's own <code>&#64;WorkflowTask</code> methods go through.
 * Two methods for one element are told apart by the versions they name, and an element
 * nobody serves in that version answers with nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = TestApplication.class)
public class ExtensionHandlerVersionsTest {

  private static final String ELEMENT = "Activity_TwoGenerations";

  private static final String MODULE = "extension-module";

  private static final String PROCESS = "DummyProcess";

  @Autowired
  private NotedWorkflowService workflowService;

  @Autowired
  private ExtensionHandlers handlers;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private NotedAggregate startWorkflow(
      final String content) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new NotedAggregate();
      aggregate.setContent(content);
      return workflowService
          .getProcessService()
          .startWorkflow(aggregate);
    });

  }

  private String titleOfVersion(
      final NotedAggregate aggregate,
      final String processVersion) {

    return workflowService
        .getNoteService()
        .noteOfVersion(aggregate, ELEMENT, SampleNoteDetails.Kind.CREATED, processVersion)
        .map(SampleNoteDetails::getTitle)
        .orElse(null);

  }

  @Test
  @DisplayName("The version of the event picks between two methods for one element")
  public void theVersionPicksTheMethod() {

    final var aggregate = startWorkflow("two-generations");

    assertEquals("first generation", titleOfVersion(aggregate, "1"));
    assertEquals("later generations", titleOfVersion(aggregate, "2"));
    assertEquals("later generations", titleOfVersion(aggregate, "17"));

  }

  @Test
  @DisplayName("An event without a version reaches no method which names one")
  public void anEventWithoutAVersionReachesNoneOfThem() {

    final var aggregate = startWorkflow("no-version");

    assertNull(titleOfVersion(aggregate, null));
    assertFalse(
        handlers
            .hasHandler(
                SampleNote.class,
                MODULE,
                PROCESS,
                List.of(ELEMENT),
                null));
    assertTrue(
        handlers
            .hasHandler(
                SampleNote.class,
                MODULE,
                PROCESS,
                List.of(ELEMENT),
                "1"));

  }

}
