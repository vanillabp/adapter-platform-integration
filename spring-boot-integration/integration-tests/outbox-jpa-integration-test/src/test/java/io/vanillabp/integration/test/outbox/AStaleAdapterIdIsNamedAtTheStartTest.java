package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * An adapter id which a waiting outbox entry names although the configuration does not,
 * on the store a Spring Boot application gets by default.
 * <p>
 * This store keeps the id in a column of its own, so the boot can ask for it with one
 * indexed query per BPMN process and says it while the application starts - hours before
 * the entry would have been read for a dispatch. That is what decision 47 in the
 * repository's DECISIONS.md asks of a store which can answer, and what
 * {@link StaleAdapterIdNamedAtDispatchTest} shows for gruelbox, which cannot.
 * <p>
 * Two contexts on one database: the first runs with an adapter 'old-bpms' at first priority
 * and leaves an entry of it undispatched, the second does not configure that adapter at all.
 * Nothing is dispatched in the second one - the entry waits an hour for its next attempt -
 * so the report can only come from the start.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class AStaleAdapterIdIsNamedAtTheStartTest {

  /**
   * A database of this test's own, kept alive between the two contexts - see
   * {@link OutboxRecoveryTest} for why it is not shared with the other tests.
   */
  private static final String DATASOURCE_URL = "jdbc:h2:mem:outbox-stale-adapter-at-start;DB_CLOSE_DELAY=-1";

  private static final String STALE_ADAPTER = "old-bpms";

  /**
   * What the report names, which is the adapter id and the reading an application has to
   * choose between.
   */
  private static final String THE_REPORT_NAMING_IT = "The adapter id '%s' is NOT configured any more"
      .formatted(STALE_ADAPTER);

  @Test
  @DisplayName("The id of a stale entry is named while the application starts")
  public void theStaleAdapterIdIsNamedAtTheStart(
      final CapturedOutput output) throws Exception {

    // the first context leaves an entry of 'old-bpms' behind: its dispatch fails and the
    // next attempt is an hour away, which is the shape of a crash
    try (var context = run("--vanillabp.adapters.%s.type=dummy".formatted(STALE_ADAPTER),
        "--vanillabp.prioritized-adapters=%s,test".formatted(STALE_ADAPTER),
        "--vanillabp.workflow-modules.test-module.adapters.%s.resources-location=classpath*:test-module/processes/dummy"
            .formatted(STALE_ADAPTER))) {
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      listener.failNextDispatches(Integer.MAX_VALUE);
      @SuppressWarnings("unchecked")
      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();
      final var transactionTemplate = context.getBean(TransactionTemplate.class);
      final var attachedAggregate = transactionTemplate.execute(status -> {
        final var aggregate = new Aggregate();
        aggregate.setContent("stale-adapter-id-at-start");
        return processService.startWorkflow(aggregate);
      });
      assertNotNull(attachedAggregate);
      listener.awaitInvocations(1, 10000);
      // the failed attempt has to be written before the context goes away, otherwise the
      // second context meets an entry which was never dispatched
      FailedAttempts.awaitWrittenDown(context, 1);
      assertEquals(
          0,
          occurrencesOf(THE_REPORT_NAMING_IT, output),
          "while the adapter is configured there is nothing to report");
    }

    try (var context = run()) {

      assertTrue(
          occurrencesOf(THE_REPORT_NAMING_IT, output) > 0,
          "the store can name the id while booting, so the boot has to: "
              + output.getAll());
      assertTrue(
          output.getAll().contains("retired-adapters"),
          "the report names the property which says the leftovers are known");
      assertTrue(
          output.getAll().contains("waiting phase-two outbox entries"),
          "and it names what is left over");
      assertEquals(
          0,
          context.getBean(RecordingPhaseTwoListener.class).getInvocations().size(),
          "nothing was dispatched in this context, so the report is the start's");

    }

  }

  /**
   * Runs the test application against the database both contexts share. Both the poll
   * interval and the distance to the next attempt are an hour, so the entry the first
   * context leaves behind is still waiting when the second one has booted.
   *
   * @param adapterConfiguration What this context configures beyond that
   * @return The running application context
   */
  private ConfigurableApplicationContext run(
      final String... adapterConfiguration) {

    final var arguments = new ArrayList<String>();
    arguments.add("--spring.datasource.url="
        + DATASOURCE_URL);
    arguments.add("--vanillabp.outbox.poll-interval=PT1H");
    arguments.add("--vanillabp.outbox.attempt-frequency=PT1H");
    arguments.addAll(List.of(adapterConfiguration));
    return new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(arguments.toArray(String[]::new));

  }

  /**
   * How often the given text stands in what the application wrote.
   */
  private static int occurrencesOf(
      final String text,
      final CapturedOutput output) {

    final var written = output.getAll();
    var occurrences = 0;
    var found = written.indexOf(text);
    while (found >= 0) {
      occurrences++;
      found = written.indexOf(text, found + text.length());
    }
    return occurrences;

  }

}
