package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.extension.sample.SampleNoteService;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.test.extension.EveryWorkflowRunsHere;
import io.vanillabp.integration.test.extension.NoteAggregate;
import io.vanillabp.integration.test.extension.NoteAggregatePersistence;
import io.vanillabp.integration.test.extension.NoteTaskWiringSource;
import io.vanillabp.integration.test.extension.NoteWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * An extension picks the method by the version of the BPMN process its event came from,
 * on Quarkus as on Spring Boot. The selection is the one VanillaBP's own
 * <code>&#64;WorkflowTask</code> methods go through, and it is measured on both platforms
 * because a mechanism working in the core says nothing about a platform ever calling it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerVersionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "NoteProcess";

  private static final String ELEMENT = "Activity_TwoGenerations";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("extension/application.yaml", "application.yaml")
          .addClass(NoteAggregate.class)
          .addClass(NoteAggregatePersistence.class)
          .addClass(NoteWorkflowService.class)
          .addClass(EveryWorkflowRunsHere.class)
          .addClass(NoteTaskWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/NoteProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  NoteAggregatePersistence persistence;

  @Inject
  SampleNoteService<NoteAggregate> noteService;

  @Inject
  ExtensionHandlers handlers;

  private NoteAggregate aggregate(
      final String id) {

    final var aggregate = new NoteAggregate();
    aggregate.setId(id);
    aggregate.setContent("two-generations");
    persistence.save(aggregate);
    return aggregate;

  }

  private String titleOfVersion(
      final NoteAggregate aggregate,
      final String processVersion) {

    return noteService
        .noteOfVersion(aggregate, ELEMENT, SampleNoteDetails.Kind.CREATED, processVersion)
        .map(SampleNoteDetails::getTitle)
        .orElse(null);

  }

  @Test
  @DisplayName("The version of the event picks between two methods for one element")
  public void theVersionPicksTheMethod() {

    final var aggregate = aggregate("version-1");

    assertEquals("first generation", titleOfVersion(aggregate, "1"));
    assertEquals("later generations", titleOfVersion(aggregate, "2"));
    assertEquals("later generations", titleOfVersion(aggregate, "17"));

  }

  @Test
  @DisplayName("An event without a version reaches no method which names one")
  public void anEventWithoutAVersionReachesNoneOfThem() {

    final var aggregate = aggregate("no-version");

    assertNull(titleOfVersion(aggregate, null));
    assertFalse(handlers.hasHandler(SampleNote.class, MODULE, PROCESS, List.of(ELEMENT), null));
    assertTrue(handlers.hasHandler(SampleNote.class, MODULE, PROCESS, List.of(ELEMENT), "1"));

  }

}
