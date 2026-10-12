package io.vanillabp.integration.adapter.spi.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an adapter hands the core about one task of a BPMN model. It also tests the
 * adapters written before the BPMN name was part of it. Every constructor and factory
 * they use is still there and answers <code>null</code> for the name. So an adapter which
 * reads no name compiles and behaves as before.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmnTaskSpecTest {

  @Test
  @DisplayName("An adapter reading the BPMN name passes it on")
  public void theNameIsCarried() {

    final var task = new BpmnTaskSpec("Activity_Score", "scoreApplicant", false, "Score the applicant");

    assertEquals("Activity_Score", task.activityId());
    assertEquals("scoreApplicant", task.taskDefinition());
    assertFalse(task.optional());
    assertEquals("Score the applicant", task.name());

    final var userTask = BpmnTaskSpec.userTask("Activity_Approve", "approve", "Approve the loan");
    assertTrue(userTask.optional());
    assertEquals("Approve the loan", userTask.name());

  }

  @Test
  @DisplayName("An adapter which reads no name says so by not passing one")
  public void withoutANameTheSpecIsWhatItWas() {

    assertNull(new BpmnTaskSpec("Activity_Score", "scoreApplicant").name());
    assertNull(new BpmnTaskSpec("Activity_Score", "scoreApplicant", true).name());
    assertNull(BpmnTaskSpec.userTask("Activity_Approve", "approve").name());

    // An element without a name in the model gives the same answer as an adapter which
    // reads no name. Nothing VanillaBP decides depends on the name.
    assertEquals(
        new BpmnTaskSpec("Activity_Score", "scoreApplicant"),
        new BpmnTaskSpec("Activity_Score", "scoreApplicant", false, null));

  }

}
