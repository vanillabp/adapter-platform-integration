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
 */
@QuarkusMain
public class NativeImageApplication implements QuarkusApplication {

  private static final String AGGREGATE_ID = "native-1";

  @Inject
  OrderWorkflowService workflowService;

  @Inject
  OrderAggregateRepository repository;

  @Override
  public int run(
      final String... args) {

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
