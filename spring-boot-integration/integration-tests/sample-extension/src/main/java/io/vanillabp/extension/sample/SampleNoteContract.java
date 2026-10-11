package io.vanillabp.extension.sample;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.extension.spi.handler.HandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;

/**
 * What the sample extension tells VanillaBP about {@link SampleNote}. It says how a method
 * is matched, which parameters it may have, and that its return value is handed back.
 * <p>
 * This is all the wiring an extension writes. VanillaBP does everything else for its own
 * annotations, and now for this one too: it finds the methods, loads the aggregate, runs
 * the transaction and writes the guiding messages.
 */
public final class SampleNoteContract {

  /**
   * The id of the extension. Every message about one of its methods names it. It is also
   * the section of its configuration (<code>vanillabp.extensions.sample.*</code>).
   */
  public static final String EXTENSION_ID = "sample";

  private SampleNoteContract() {
  }

  /**
   * @return The contract to register with {@code ExtensionHandlers}
   */
  public static HandlerContract build() {

    return HandlerContract
        .of(EXTENSION_ID, SampleNote.class)
        .lookupKeys(annotation -> keysOf((SampleNote) annotation))
        .versions(annotation -> List.of(((SampleNote) annotation).version()))
        .callsCarryTheProcessVersion()
        .coreParameters(
            CoreHandlerParameter.WORKFLOW_AGGREGATE,
            CoreHandlerParameter.TASK_PARAM,
            CoreHandlerParameter.MULTI_INSTANCE)
        .parameterBinder(SampleNoteContract::bindDetails)
        .parameterBinder(SampleNoteContract::bindEvent)
        .deliversReturnValue()
        .build();

  }

  /**
   * The keys one occurrence of the annotation names. If it names neither, the list stays
   * empty. For VanillaBP an empty list means "the name of the method".
   */
  private static List<String> keysOf(
      final SampleNote annotation) {

    final var keys = new LinkedList<String>();
    if (!annotation.element().equals(SampleNote.USE_METHOD_NAME)) {
      keys.add(annotation.element());
    }
    if (!annotation.taskDefinition().equals(SampleNote.USE_METHOD_NAME)) {
      keys.add(annotation.taskDefinition());
    }
    return keys;

  }

  /**
   * The prefilled note, recognized by its type.
   */
  private static Optional<HandlerValueSource> bindDetails(
      final HandlerParameter parameter) {

    return parameter.getType().equals(SampleNoteDetails.class)
        ? Optional.of(context -> context.getPayload())
        : Optional.empty();

  }

  /**
   * What happened to the element, recognized by its annotation. A parameter may carry the
   * annotation but have the wrong type. This binder REPORTS that mistake instead of
   * rejecting the parameter. A rejection would end the boot with "nothing can bind this
   * parameter", and that says nothing about the real mistake.
   */
  private static Optional<HandlerValueSource> bindEvent(
      final HandlerParameter parameter) {

    if (!parameter.isAnnotationPresent(SampleNoteEvent.class)) {
      return Optional.empty();
    }
    if (!parameter.getType().equals(SampleNoteDetails.Kind.class)) {
      throw new IllegalStateException(
          """
              The %s is annotated with @SampleNoteEvent but is not of type \
              SampleNoteDetails.Kind! Change the parameter's type."""
              .formatted(parameter.describe()));
    }
    return Optional
        .of(context -> ((SampleNoteDetails) context.getPayload()).getKind());

  }

}
