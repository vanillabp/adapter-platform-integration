package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.extension.spi.resolver.PhaseTwoOutboxResolver;
import io.vanillabp.integration.it.SecondaryProcessValidationTest.RenamedAggregate;
import io.vanillabp.integration.it.SecondaryProcessValidationTest.RenamedAggregatePersistence;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An extension which plans calls that replace the entry still waiting for its dispatch
 * refuses the start where a store of the application cannot replace, on Quarkus. The store
 * here is one of the application's own, and it implements only <code>schedule</code>. The
 * extension asks the resolver of the platform, the way an extension is told to in the
 * javadoc of <code>PhaseTwoOutboxResolver#whyTheStoresCannotReplace</code>.
 * <p>
 * The same store without such an extension starts: {@link SecondaryProcessValidationTest}
 * boots with a store which cannot replace either. The Spring Boot side holds both cases in
 * <code>AnExtensionRefusesAStoreWhichCannotReplaceTest</code> of the extension integration
 * test.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnExtensionRefusesAStoreWhichCannotReplaceTest {

  static final String EXTENSION_NAME = "replacing-extension";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("secondary-process/application.yaml", "application.yaml")
          .addClass(RenamedAggregate.class)
          .addClass(RenamedAggregatePersistence.class)
          .addClass(ReplacingWorkflowService.class)
          .addClass(AStoreWhichCannotReplace.class)
          .addClass(AnExtensionWhichPlansReplacingCalls.class)
          .addAsResource(
              new StringAsset("not parsed by the dummy adapter"), "processes/dummy/OrderApproval.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          final var message = current.getMessage();
          if ((message != null) && message.contains("plans phase-two calls which replace")) {
            assertTrue(message.contains("Extension '%s'".formatted(EXTENSION_NAME)), message);
            assertTrue(message.contains(AStoreWhichCannotReplace.class.getName()), message);
            assertTrue(message.contains("PhaseTwoOutbox#scheduleReplacingWhatIsStillWaiting"), message);
            assertTrue(message.contains("PhaseTwoOutbox#canReplaceWhatIsStillWaiting answer true"), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the refusal of the store which cannot replace, but got: "
            + throwable);
      });

  @Test
  @DisplayName("An extension which plans replacing calls ends the start where a store cannot replace")
  public void theStartEnds() {
    // the assertion happens on the startup exception (assertException above)
  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = RenamedAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SecondaryProcessValidationTest.DEPLOYED_PROCESS))
  public static class ReplacingWorkflowService {
  }

  /**
   * A store of the application which was written before replacing existed.
   */
  @ApplicationScoped
  public static class AStoreWhichCannotReplace implements PhaseTwoOutbox {

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      return true;

    }

  }

  /**
   * An extension which plans calls that replace what is still waiting, like the Business
   * Cockpit does. So it asks at startup whether the stores can do that.
   */
  @ApplicationScoped
  public static class AnExtensionWhichPlansReplacingCalls implements ExtensionWiringService<Object, Object> {

    @Inject
    PhaseTwoOutboxResolver outboxResolver;

    @Inject
    StartupReport startupReport;

    @Override
    public Class<Object> getModelType() {

      return Object.class;

    }

    @Override
    public Class<Object> getProcessContextType() {

      return Object.class;

    }

    @Override
    public String getExtensionName() {

      return EXTENSION_NAME;

    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final Object model,
        final Object context) {

      outboxResolver
          .whyTheStoresCannotReplace(getExtensionName())
          .ifPresent(why -> startupReport.refuse(StartupTopic.CODE, getExtensionName(), why));

    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final Object bpmsProcessingContext) {
      // nothing to start
    }

  }

}
