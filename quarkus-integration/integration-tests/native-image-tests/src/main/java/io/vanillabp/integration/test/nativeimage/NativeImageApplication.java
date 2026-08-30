package io.vanillabp.integration.test.nativeimage;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * The assertion of this module, written as the application's main: booting is what has to
 * work, and an exit code says whether it did.
 * <p>
 * Building the native image is the harder half, but a binary
 * which cannot start is no proof of anything - the BPMN resources of the workflow module were
 * missing from the image until the deployment pipeline started registering them. So the
 * application boots, runs the deployment pipeline, starts a workflow through the generated
 * process service and reads the aggregate back out of the relational database. Anything going
 * wrong on that way either throws or leaves the aggregate unwritten, and the exit code is
 * what the pipeline of the platform looks at.
 * <p>
 * The second thing it asserts is the configuration its workflow modules ship, see
 * {@link WorkflowModuleConfigurationCheck}: those files are read from the classpath, which a
 * binary has none of, so they reach it only as registered resources. Started with
 * <code>-Dquarkus.profile=tenant</code> the same binary has to answer with the values of the
 * profile-specific files.
 */
@QuarkusMain
public class NativeImageApplication implements QuarkusApplication {

  private static final String AGGREGATE_ID = "native-1";

  @Inject
  OrderWorkflowService workflowService;

  @Inject
  OrderAggregateRepository repository;

  @Inject
  WorkflowModuleConfigurationCheck configuration;

  @Override
  public int run(
      final String... args) {

    final var configurationComplaints = configuration.whatTheConfigurationGotWrong();
    if (!configurationComplaints.isEmpty()) {
      System.err.println(
          "With the profile(s) '%s' active the configuration of the workflow modules was not read as expected:%n  %s"
              .formatted(configuration.activeProfiles(), String.join("\n  ", configurationComplaints)));
      return 2;
    }
    System.out.println("VanillaBP read the configuration of both workflow modules with the profile(s) '%s' active."
        .formatted(configuration.activeProfiles()));

    final var stored = QuarkusTransaction
        .requiringNew()
        .call(() -> {
          workflowService.startWorkflow(AGGREGATE_ID);
          return repository.findById(AGGREGATE_ID);
        });

    if (stored == null) {
      System.err.println("The workflow aggregate '%s' was not stored!".formatted(AGGREGATE_ID));
      return 1;
    }
    System.out.println("VanillaBP started the workflow of aggregate '%s' (status '%s')."
        .formatted(stored.getId(), stored.getStatus()));
    return 0;

  }

}
