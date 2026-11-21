package io.vanillabp.extension.sample;

import java.util.List;
import java.util.Optional;

import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.service.AggregateServiceContext;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;
import io.vanillabp.integration.extension.spi.settings.VanillaBpSettings;
import io.vanillabp.integration.spi.IdentifierScoping;
import io.vanillabp.integration.spi.workflowtask.WorkflowTaskClaims;

/**
 * Builds one {@link SampleNoteService} per workflow-aggregate class. VanillaBP calls it
 * when the application first injects the service. That is why injecting it is optional.
 */
public class SampleNoteServiceFactory implements AggregateServiceFactory<SampleNoteService> {

  /**
   * The key this extension reads from its own section of the configuration.
   */
  public static final String GREETING = "greeting";

  private final VanillaBpSettings properties;

  private final WorkflowTaskClaims claims;

  private final IdentifierScoping scoping;

  /**
   * @param properties VanillaBP's configuration, which holds the section of this extension
   * @param claims What the core knows about the workflow services of the application
   * @param scoping How the BPMS of an adapter spells the identifiers of the application
   */
  public SampleNoteServiceFactory(
      final VanillaBpSettings properties,
      final WorkflowTaskClaims claims,
      final IdentifierScoping scoping) {

    this.properties = properties;
    this.claims = claims;
    this.scoping = scoping;

  }

  @Override
  public Class<SampleNoteService> getServiceInterface() {

    return SampleNoteService.class;

  }

  @Override
  public SampleNoteService createService(
      final AggregateServiceContext context) {

    return new SampleNoteService<Object>() {

      @Override
      public Optional<SampleNoteDetails> noteOf(
          final Object workflowAggregate,
          final String elementId,
          final SampleNoteDetails.Kind kind) {

        // reading a note is a read, so VanillaBP must not save the aggregate
        return invoke(workflowAggregate, List.of(elementId), kind, false, null);

      }

      @Override
      public Optional<SampleNoteDetails> noteOfKeys(
          final Object workflowAggregate,
          final List<String> lookupKeys,
          final SampleNoteDetails.Kind kind) {

        return invoke(workflowAggregate, lookupKeys, kind, false, null);

      }

      @Override
      public Optional<SampleNoteDetails> noteOfVersion(
          final Object workflowAggregate,
          final String elementId,
          final SampleNoteDetails.Kind kind,
          final String processVersion) {

        return invoke(workflowAggregate, List.of(elementId), kind, false, processVersion);

      }

      @Override
      public Optional<SampleNoteDetails> recordNoteOf(
          final Object workflowAggregate,
          final String elementId,
          final SampleNoteDetails.Kind kind) {

        return invoke(workflowAggregate, List.of(elementId), kind, true, null);

      }

      private Optional<SampleNoteDetails> invoke(
          final Object workflowAggregate,
          final List<String> lookupKeys,
          final SampleNoteDetails.Kind kind,
          final boolean saving,
          final String processVersion) {

        final var elementId = lookupKeys.getFirst();
        final var prefilled = new SampleNoteDetails(
            elementId, kind, "%s %s".formatted(configuredGreeting(), elementId));
        final var call = HandlerCall
            .of(SampleNote.class, context.getWorkflowModuleId(), context.getBpmnProcessId())
            // the element id first and the task definition after it. VanillaBP asks
            // extensions to offer the keys in this order
            .lookupKeys(lookupKeys)
            .processVersion(processVersion)
            .workflowAggregateId(context.getWorkflowAggregateId(workflowAggregate))
            .payload(prefilled)
            .variable("kind", kind.name());
        if (!saving) {
          call.withoutSavingTheWorkflowAggregate();
        }
        return context
            .getHandlers()
            .invoke(call.build())
            .map(SampleNoteDetails.class::cast);

      }

      @Override
      public String bpmsHolding(
          final Object workflowAggregate) {

        return context
            .getElection()
            .adapterIdOfWorkflow(
                context.getWorkflowModuleId(),
                context.getBpmnProcessId(),
                context.getWorkflowAggregateId(workflowAggregate));

      }

      @Override
      public boolean claims(
          final String bpmnProcessId) {

        return claims.isClaimedByAWorkflowService(context.getWorkflowModuleId(), bpmnProcessId);

      }

      @Override
      public String workflowAggregateIdName() {

        return claims.resolveWorkflowAggregateIdName(context.getWorkflowModuleId(), context.getBpmnProcessId());

      }

      @Override
      public String scopedProcessIdOn(
          final String adapterId) {

        return IdentifierScoping
            .scopedProcessId(scoping, context.getWorkflowModuleId(), context.getBpmnProcessId(), adapterId);

      }

      @Override
      public String configuredGreeting() {

        return properties
            .extensionProperty(context.getWorkflowModuleId(), SampleNoteContract.EXTENSION_ID, GREETING);

      }

    };

  }

}
