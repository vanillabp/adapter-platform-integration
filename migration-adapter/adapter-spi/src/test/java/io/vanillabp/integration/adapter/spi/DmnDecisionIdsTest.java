package io.vanillabp.integration.adapter.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
@DisplayName("The decision ids of a DMN file")
public class DmnDecisionIdsTest {

  private static final String TWO_DECISIONS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                   id="definitions" name="rating" namespace="http://vanillabp.io/test">
        <inputData id="score" name="Score"/>
        <decision id="rating" name="Rating">
          <informationRequirement id="req1">
            <requiredDecision href="#risk"/>
          </informationRequirement>
          <informationRequirement id="req2">
            <requiredInput href="#score"/>
          </informationRequirement>
          <decisionTable id="ratingTable" hitPolicy="UNIQUE"/>
        </decision>
        <decision id="risk" name="Risk">
          <decisionTable id="riskTable" hitPolicy="UNIQUE"/>
        </decision>
      </definitions>
      """;

  @Test
  @DisplayName("are the ids of its decisions, in the order of the file")
  public void theDecisionsOfTheFileAreReported() {

    assertEquals(
        List.of("rating", "risk"),
        List.copyOf(DmnDecisionIds.of(TWO_DECISIONS.getBytes(StandardCharsets.UTF_8))));

  }

  @Test
  @DisplayName("are rewritten together with every reference pointing at one of them")
  public void aReferenceFollowsTheIdItNames() {

    final var rewritten = new String(
        DmnDecisionIds.rewrite(TWO_DECISIONS.getBytes(StandardCharsets.UTF_8), id -> "my-module__"
            + id), StandardCharsets.UTF_8);

    assertEquals(
        List.of("my-module__rating", "my-module__risk"),
        List.copyOf(DmnDecisionIds.of(rewritten.getBytes(StandardCharsets.UTF_8))),
        rewritten);
    assertTrue(
        rewritten.contains("href=\"#my-module__risk\""),
        "the decision built on another one names it by its new id: "
            + rewritten);

  }

  @Test
  @DisplayName("leave everything which is not a decision alone")
  public void whatIsNotADecisionKeepsItsId() {

    final var rewritten = new String(
        DmnDecisionIds.rewrite(TWO_DECISIONS.getBytes(StandardCharsets.UTF_8), id -> "my-module__"
            + id), StandardCharsets.UTF_8);

    // the input data is not deployed under a name of its own, and a decision table id is
    // local to its decision - renaming either would change a file for no reason
    assertTrue(rewritten.contains("id=\"score\""), rewritten);
    assertTrue(rewritten.contains("href=\"#score\""), rewritten);
    assertTrue(rewritten.contains("id=\"ratingTable\""), rewritten);
    assertTrue(rewritten.contains("id=\"definitions\""), rewritten);

  }

  @Test
  @DisplayName("are left untouched where the file declares none")
  public void aFileWithoutDecisionsIsHandedOnUnchanged() {

    final var noDecision = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="empty" name="empty"/>
        """.getBytes(StandardCharsets.UTF_8);

    assertEquals(
        noDecision,
        DmnDecisionIds.rewrite(noDecision, id -> "my-module__"
            + id),
        "the very bytes are handed on, so a file nothing has to be done to is not reformatted");

  }

  @Test
  @DisplayName("are not touched by an adapter which does not deploy decision tables, and it says so")
  public void anAdapterWithoutDmnSupportSaysSo() {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(AdapterDeploymentService.class);
    logger.addAppender(logWatcher);

    final var context = new Object();
    final Object handedBack;
    try {
      handedBack = new AdapterWithoutDmnSupport()
          .readDmn(
              "my-module",
              context,
              "rating.dmn",
              new java.io.ByteArrayInputStream(TWO_DECISIONS.getBytes(StandardCharsets.UTF_8)));
    } finally {
      logger.detachAppender(logWatcher);
      logWatcher.stop();
    }

    assertEquals(context, handedBack, "the context of the module's processes is handed back untouched");
    final var message = logWatcher.list
        .stream()
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .findFirst()
        .orElseThrow(() -> new AssertionError("nothing was said about the file which was not deployed"));
    assertTrue(message.contains("rating.dmn"), message);
    assertTrue(message.contains("my-module"), message);
    assertTrue(message.contains("no-dmn"), "the adapter is named: "
        + message);

  }

  @Test
  @DisplayName("cannot be read from something which is not XML, and it says so")
  public void aBrokenFileIsNamed() {

    final var exception = assertThrows(
        IllegalArgumentException.class,
        () -> DmnDecisionIds.of("this is not a decision table".getBytes(StandardCharsets.UTF_8)));
    assertTrue(exception.getMessage().contains("DMN"), exception.getMessage());

  }

  /**
   * An adapter which was written before decision tables were deployed, or whose BPMS has
   * no decisions at all: it implements neither {@code readDmn} nor anything of it.
   */
  private static class AdapterWithoutDmnSupport implements AdapterDeploymentService<Object, Object> {

    @Override
    public String getAdapterId() {

      return "no-dmn";

    }

    @Override
    public String getAdapterType() {

      return "no-dmn";

    }

    @Override
    public List<java.util.Map.Entry<String, Object>> readBpmn(
        final String workflowModuleId,
        final String filename,
        final java.io.InputStream bpmn,
        final boolean isVanillaBpBpmn) {

      return List.of();

    }

    @Override
    public Object prepareBpmn(
        final String workflowModuleId,
        final Object existingContext,
        final String filename,
        final String bpmnProcessId,
        final Object model) {

      return existingContext;

    }

    @Override
    public void deployResources(
        final String workflowModuleId,
        final Object bpmsProcessingContext) {

    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final Object bpmsProcessingContext) {

    }

    @Override
    public Class<Object> getModelType() {

      return Object.class;

    }

    @Override
    public Class<Object> getProcessContextType() {

      return Object.class;

    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final Object model,
        final Object context) {

    }

  }

}
