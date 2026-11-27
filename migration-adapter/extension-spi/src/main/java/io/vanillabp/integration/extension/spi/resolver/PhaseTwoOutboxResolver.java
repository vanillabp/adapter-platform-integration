package io.vanillabp.integration.extension.spi.resolver;

import java.util.Collection;
import java.util.Optional;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;

/**
 * Resolves the {@link PhaseTwoOutbox} which is used for a workflow aggregate. The platform
 * integrations implement it, and the core calls it AT STARTUP, when it checks the outbox of
 * every workflow aggregate. An extension asks it for the store of its own outbox entries. The
 * platform resolves
 * in this order:
 * <ol>
 * <li>the most specific {@link PhaseTwoOutboxAware} bean which covers the aggregate class
 * (the bean which names the aggregate itself wins over one which names a supertype),</li>
 * <li>the platform's default choice: the single available outbox bean, or, if there are
 * several, the platform's default outbox which matches the persistence technology that
 * manages the aggregate,</li>
 * <li><code>null</code> if no outbox is available at all. Then the core fails the startup
 * with a guiding message which includes {@link #remediesDescription()}.</li>
 * </ol>
 */
public interface PhaseTwoOutboxResolver {

  /**
   * Resolves the outbox for aggregates of the given class.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The outbox or <code>null</code> if none is available
   * @throws IllegalStateException If several outboxes exist and none can be assigned to the
   *           aggregate. The guiding message names the beans found and the remedy (provide a
   *           {@link PhaseTwoOutboxAware} bean)
   */
  PhaseTwoOutbox resolveFor(
      Class<?> workflowAggregateClass);

  /**
   * Every store which this application holds, including the ones which a
   * {@link PhaseTwoOutboxAware} bean names for a single workflow aggregate. An application
   * which provides all of its stores that way has no plain store bean at all. So asking for
   * the store beans alone would say "none" about an application which has several.
   * <p>
   * <strong>What the order means:</strong> nothing a caller may build on. The stores come in
   * the order in which the platform lists its beans. The ones which a
   * {@link PhaseTwoOutboxAware} bean names come after the plain store beans. The order is
   * stable within one boot of one application. It says nothing about priority. Only
   * {@link #resolveFor(Class)} says which store serves a workflow aggregate.
   * <p>
   * <strong>The collection is read-only</strong>, and a store appears in it once, however many
   * beans point at it. Callers use it to count the stores of an application, and to recognise
   * the one store which all of its aggregates share. A caller who wants a store for an
   * aggregate asks {@link #resolveFor(Class)}.
   * <p>
   * A store which the platform would never select is not in here. On Quarkus that is a
   * platform default which <code>vanillabp.outbox.jdbc.enabled</code> or
   * <code>vanillabp.outbox.mongo.enabled</code> switched off, or which has no datasource.
   *
   * @return The stores, read-only and possibly empty
   */
  Collection<PhaseTwoOutbox> allStores();

  /**
   * Says why the stores of this application cannot carry an extension which plans calls that
   * replace the entry still waiting for its dispatch
   * ({@link io.vanillabp.integration.spi.PhaseTwoCall#replacingWhatIsStillWaiting()}). It is
   * empty where every store can replace.
   * <p>
   * A store which cannot replace keeps the older state and drops the younger call. That is
   * the one thing such an extension must not run into, and at runtime only a warning would
   * say it. So the extension asks this while it wires a BPMN process and refuses the start
   * with the answer:
   *
   * <pre>{@code
   * outboxResolver
   *     .whyTheStoresCannotReplace(getExtensionName())
   *     .ifPresent(why -> startupReport.refuse(StartupTopic.CODE, getExtensionName(), why));
   * }</pre>
   *
   * It asks in <code>wireBpmn</code> and not in <code>startWorkflowProcessing</code>. On
   * Spring Boot the start is over before the workflow processing starts, and a reason not to
   * start which arrives after that is only written to the log. Asking once is enough, because
   * the answer is the same for every BPMN process.
   * <p>
   * An extension which never plans such calls does not ask, and the application starts as
   * before. See decision 152 in the repository's DECISIONS.md.
   * <p>
   * Every store of {@link #allStores()} is asked, not only the store of one workflow
   * aggregate. An extension usually plans for every workflow it is wired to, and it learns
   * which aggregate a call belongs to only when it plans the call.
   *
   * @param extensionName How the extension names itself in its messages
   * @return The whole message for the refusal, ending with what to do, or empty if every
   *         store can replace
   */
  default Optional<String> whyTheStoresCannotReplace(
      final String extensionName) {

    final var storesWhichCannotReplace = allStores()
        .stream()
        .filter(store -> !store.canReplaceWhatIsStillWaiting())
        .map(store -> store.getClass().getName())
        .distinct()
        .toList();
    if (storesWhichCannotReplace.isEmpty()) {
      return Optional.empty();
    }
    return Optional
        .of(
            """
                Extension '%s' plans phase-two calls which replace the entry still waiting for its \
                dispatch, and these outbox stores cannot replace: %s. Such a store keeps the older \
                state and drops the younger one. To solve this either
                - implement PhaseTwoOutbox#scheduleReplacingWhatIsStillWaiting in that store and let \
                PhaseTwoOutbox#canReplaceWhatIsStillWaiting answer true, or
                - use one of the stores VanillaBP ships instead."""
                .formatted(extensionName, String.join(", ", storesWhichCannotReplace)));

  }

  /**
   * Lines with remedies for this platform. They are added to the core's guiding message
   * when no outbox is available but one is required (for example which starter or extension
   * to add to enable a default implementation).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

}
