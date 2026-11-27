package io.vanillabp.integration.test.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.extension.spi.resolver.PhaseTwoOutboxResolver;
import io.vanillabp.integration.outbox.jdbc.JdbcPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An extension which plans calls that replace the entry still waiting for its dispatch
 * refuses the start where a store of the application cannot replace. The rule holds only
 * where such an extension is there. An application without one starts with the same store,
 * because nobody will ever ask it to replace.
 * <p>
 * Each test starts the application INSIDE the test method, with its own beans. They are
 * registered by hand and not by a configuration class. A configuration class in this
 * package would be found by the component scan of every other test of this module, and
 * the refusal would end their starts as well. The Quarkus side holds the refusal in
 * <code>AnExtensionRefusesAStoreWhichCannotReplaceTest</code> of the deployment
 * integration tests.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnExtensionRefusesAStoreWhichCannotReplaceTest {

  static final String EXTENSION_NAME = "replacing-extension";

  /**
   * The first words of the refusal. The test without the extension checks that they never
   * appear.
   */
  static final String REFUSAL = "plans phase-two calls which replace the entry still waiting";

  /**
   * A store of the application which was written before replacing existed. It takes the
   * name of the default store, so the platform does not create that one next to it.
   */
  static final class AStoreWhichCannotReplace implements PhaseTwoOutbox {

    @Override
    public boolean schedule(
        final io.vanillabp.integration.spi.PhaseTwoCall call) {

      return true;

    }

  }

  /**
   * An extension which plans calls that replace what is still waiting, like the Business
   * Cockpit does. It asks while it wires, because on Spring Boot the start is over before
   * the workflow processing starts.
   */
  static final class AnExtensionWhichPlansReplacingCalls implements ExtensionWiringService<Object, Object> {

    private final ConfigurableApplicationContext context;

    AnExtensionWhichPlansReplacingCalls(
        final ConfigurableApplicationContext context) {

      this.context = context;

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
    public String getExtensionName() {

      return EXTENSION_NAME;

    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final Object model,
        final Object bpmsContext) {

      context
          .getBean(PhaseTwoOutboxResolver.class)
          .whyTheStoresCannotReplace(getExtensionName())
          .ifPresent(why -> context
              .getBean(StartupReport.class)
              .refuse(StartupTopic.CODE, getExtensionName(), why));

    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final Object bpmsProcessingContext) {
      // nothing to start
    }

  }

  private static SpringApplicationBuilder theApplication(
      final String database,
      final boolean withTheStoreWhichCannotReplace,
      final boolean withTheExtension) {

    return new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        // a database of its own: the other contexts of this scenario are still cached, and
        // their schema must not be created twice
        .properties("spring.datasource.url=jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database))
        .initializers(context -> {
          if (withTheStoreWhichCannotReplace) {
            context
                .getBeanFactory()
                .registerSingleton(JdbcPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME,
                    new AStoreWhichCannotReplace());
          }
          if (withTheExtension) {
            context
                .getBeanFactory()
                .registerSingleton("replacingExtension", new AnExtensionWhichPlansReplacingCalls(context));
          }
        });

  }

  @Test
  @DisplayName("An extension which plans replacing calls ends the start where a store cannot replace")
  public void theExtensionEndsTheStart() {

    final var refused = assertThrows(
        RuntimeException.class, () -> theApplication("store-cannot-replace", true, true)
            .run()
            .close());

    var current = (Throwable) refused;
    while ((current != null) && ((current.getMessage() == null) || !current.getMessage().contains(REFUSAL))) {
      current = current.getCause();
    }
    assertTrue(current != null, () -> "the start ended for another reason: "
        + refused);
    final var message = current.getMessage();
    assertTrue(message.contains("Extension '%s'".formatted(EXTENSION_NAME)), message);
    assertTrue(message.contains(AStoreWhichCannotReplace.class.getName()), message);
    assertTrue(message.contains("PhaseTwoOutbox#scheduleReplacingWhatIsStillWaiting"), message);
    assertTrue(message.contains("PhaseTwoOutbox#canReplaceWhatIsStillWaiting answer true"), message);

  }

  @Test
  @DisplayName("The same store starts where no extension plans replacing calls")
  public void theSameStoreStartsWithoutTheExtension(
      final CapturedOutput output) {

    // the output of this class holds the refusal of the other test already, so only what
    // this start wrote is read
    final var before = output.getAll().length();
    try (var context = theApplication("store-without-extension", true, false).run()) {

      assertTrue(context.isRunning());
      assertFalse(output.getAll().substring(before).contains(REFUSAL), output.getAll().substring(before));

    }

  }

  @Test
  @DisplayName("The store of the platform lets an extension which plans replacing calls start")
  public void theStoreOfThePlatformLetsTheExtensionStart(
      final CapturedOutput output) {

    // the output of this class holds the refusal of the other test already, so only what
    // this start wrote is read
    final var before = output.getAll().length();
    try (var context = theApplication("platform-store-with-extension", false, true).run()) {

      assertTrue(context.isRunning());
      assertTrue(
          context
              .getBean(JdbcPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME, PhaseTwoOutbox.class)
              .canReplaceWhatIsStillWaiting(),
          "the JDBC store of the platform replaces");
      assertFalse(output.getAll().substring(before).contains(REFUSAL), output.getAll().substring(before));

    }

  }

}
