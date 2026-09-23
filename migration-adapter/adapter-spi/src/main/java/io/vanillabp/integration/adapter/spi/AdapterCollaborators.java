package io.vanillabp.integration.adapter.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * Everything the platform hands to an adapter, in one object which the adapter takes in
 * its constructor.
 * <p>
 * Before this existed, the collaborators arrived one setter call at a time, and a
 * registrar which forgot one produced an adapter that deployed, ran tasks and never
 * reported a workflow end - without anything failing. Nothing in the code said which
 * calls belonged to a complete registration, so the list lived in whoever had written
 * the last registrar.
 * <p>
 * <b>What every adapter gets:</b>
 * <ul>
 * <li>{@link #workflowTaskWiring()} - what the adapter asks while it reads a BPMN file
 * (see {@link WorkflowTaskWiring}).</li>
 * <li>{@link #workflowTaskInvoker()} - where a delivered task goes (see
 * {@link WorkflowTaskInvoker}).</li>
 * <li>{@link #scoping()} - how the adapter avoids a name clash between two workflow
 * modules deployed to the same BPMS (see {@link NameClashAvoidanceSupport}).</li>
 * <li>{@link #workflowAggregateSync()} - which values of a workflow aggregate the BPMS
 * may see (see {@link WorkflowAggregateSync}).</li>
 * <li>{@link #preCommitRegistrar()} - where the adapter hangs work that has to happen
 * before the caller's transaction commits (see {@link PreCommitRegistrar}).</li>
 * </ul>
 * These five are mandatory: both platform integrations provide them for every
 * application, so an adapter may rely on them, and a builder without one refuses to
 * build, naming the adapter id and what is missing.
 * <p>
 * <b>What an adapter may not get:</b>
 * <ul>
 * <li>{@link #workflowEndedInvoker()} - where a workflow's end is reported.</li>
 * <li>{@link #bpmsInitiatedStartInvoker()} - where a workflow the BPMS started by
 * itself is reported, e.g. through a timer or a message start event.</li>
 * </ul>
 * These two are handed over as {@link Optional} because an adapter has to work without
 * them: an application which never asks for either has nothing to report to. Both
 * platform integrations do provide them today, though - they come out of the same core
 * bean as the two above - so an adapter built without one is nearly always a
 * registration which left it out, and the build says so with the adapter id next to it.
 * That is the failure this class was written for: an adapter which deploys, runs tasks
 * and never reports a workflow end, with nothing going wrong anywhere.
 *
 * @see #forAdapter(String)
 */
public final class AdapterCollaborators {

  private static final Logger log = LoggerFactory.getLogger(AdapterCollaborators.class);

  private final String adapterId;

  private final WorkflowTaskWiring workflowTaskWiring;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final NameClashAvoidanceSupport scoping;

  private final WorkflowAggregateSync workflowAggregateSync;

  private final PreCommitRegistrar preCommitRegistrar;

  private final WorkflowEndedInvoker workflowEndedInvoker;

  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  private AdapterCollaborators(
      final Builder builder) {

    this.adapterId = builder.adapterId;
    this.workflowTaskWiring = builder.workflowTaskWiring;
    this.workflowTaskInvoker = builder.workflowTaskInvoker;
    this.scoping = builder.scoping;
    this.workflowAggregateSync = builder.workflowAggregateSync;
    this.preCommitRegistrar = builder.preCommitRegistrar;
    this.workflowEndedInvoker = builder.workflowEndedInvoker;
    this.bpmsInitiatedStartInvoker = builder.bpmsInitiatedStartInvoker;

  }

  /**
   * Starts a set of collaborators for one adapter id.
   *
   * @param adapterId The adapter id these collaborators belong to - it names the adapter
   *                  in whatever the builder has to report
   * @return The builder
   */
  public static Builder forAdapter(
      final String adapterId) {

    return new Builder(adapterId);

  }

  /**
   * Which adapter instance these collaborators were assembled for - the id the
   * application configured under <code>vanillabp.adapters</code>, and the one the
   * builder names in everything it reports.
   *
   * @return The adapter id these collaborators were built for
   */
  public String adapterId() {

    return adapterId;

  }

  /**
   * The wiring half of the task SPI - what an adapter calls while it deploys, never at
   * runtime. {@link WorkflowTaskWiring} says which of those calls are due when.
   *
   * @return What the adapter asks while it reads a BPMN file
   */
  public WorkflowTaskWiring workflowTaskWiring() {

    return workflowTaskWiring;

  }

  /**
   * The runtime half of the task SPI - what the adapter's worker threads hold to hand a
   * delivered task to the application and to learn what to answer the BPMS with.
   *
   * @return Where a delivered task goes
   */
  public WorkflowTaskInvoker workflowTaskInvoker() {

    return workflowTaskInvoker;

  }

  /**
   * How an adapter turns a plain identifier into the one its BPMS sees. The core composes
   * those strings so that every adapter scopes alike, which is why an adapter asks here
   * instead of building a prefix of its own.
   *
   * @return How the adapter avoids a name clash between workflow modules
   */
  public NameClashAvoidanceSupport scoping() {

    return scoping;

  }

  /**
   * What an adapter may hand to its BPMS of a workflow aggregate, asked at every sync
   * point. An adapter reads an aggregate through this and never by itself, because what
   * leaves the application is the application's decision - decision 10 in the
   * repository's DECISIONS.md.
   *
   * @return Which values of a workflow aggregate the BPMS may see
   */
  public WorkflowAggregateSync workflowAggregateSync() {

    return workflowAggregateSync;

  }

  /**
   * Where an adapter hands a phase-one check which should run as late as possible. The
   * later such a check runs, the smaller the window in which its answer goes stale before
   * phase two dispatches - see {@link PreCommitRegistrar}.
   *
   * @return Where work is hung which has to happen before the caller's transaction
   *         commits
   */
  public PreCommitRegistrar preCommitRegistrar() {

    return preCommitRegistrar;

  }

  /**
   * Where an adapter reports the end of a workflow its BPMS told it about. An adapter has
   * to cope with an empty answer, so it asks the {@link Optional} rather than unwrapping
   * it - the end is then not reported and nothing else changes.
   *
   * @return Where a workflow's end is reported, empty if the application has no
   *         {@code @WorkflowEnded} method
   */
  public Optional<WorkflowEndedInvoker> workflowEndedInvoker() {

    return Optional.ofNullable(workflowEndedInvoker);

  }

  /**
   * Where an adapter reports a workflow its BPMS started on its own, and where it asks
   * while it deploys whether such a start is served at all. An empty answer is coped with
   * like the one of {@link #workflowEndedInvoker()}.
   *
   * @return Where a workflow the BPMS started by itself is reported, empty if the
   *         application has no method for it
   */
  public Optional<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker() {

    return Optional.ofNullable(bpmsInitiatedStartInvoker);

  }

  /**
   * Collects the collaborators of one adapter id and refuses to build an incomplete
   * set.
   */
  public static final class Builder {

    private final String adapterId;

    private WorkflowTaskWiring workflowTaskWiring;

    private WorkflowTaskInvoker workflowTaskInvoker;

    private NameClashAvoidanceSupport scoping;

    private WorkflowAggregateSync workflowAggregateSync;

    private PreCommitRegistrar preCommitRegistrar;

    private WorkflowEndedInvoker workflowEndedInvoker;

    private BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

    private Builder(
        final String adapterId) {

      this.adapterId = adapterId;

    }

    /**
     * Hands over the wiring half of the task SPI.
     *
     * @param workflowTaskWiring What the adapter asks while it reads a BPMN file -
     *                           mandatory, so {@link #build()} refuses a set without it
     * @return This builder
     */
    public Builder workflowTaskWiring(
        final WorkflowTaskWiring workflowTaskWiring) {

      this.workflowTaskWiring = workflowTaskWiring;
      return this;

    }

    /**
     * Hands over the runtime half of the task SPI.
     *
     * @param workflowTaskInvoker Where a delivered task goes - mandatory, so
     *                            {@link #build()} refuses a set without it
     * @return This builder
     */
    public Builder workflowTaskInvoker(
        final WorkflowTaskInvoker workflowTaskInvoker) {

      this.workflowTaskInvoker = workflowTaskInvoker;
      return this;

    }

    /**
     * Hands over the core's name-clash scoping.
     *
     * @param scoping How the adapter avoids a name clash between workflow modules -
     *                mandatory, so {@link #build()} refuses a set without it
     * @return This builder
     */
    public Builder scoping(
        final NameClashAvoidanceSupport scoping) {

      this.scoping = scoping;
      return this;

    }

    /**
     * Hands over the aggregate sync every adapter reads a workflow aggregate through.
     *
     * @param workflowAggregateSync Which values of a workflow aggregate the BPMS may
     *                              see - mandatory, so {@link #build()} refuses a set
     *                              without it
     * @return This builder
     */
    public Builder workflowAggregateSync(
        final WorkflowAggregateSync workflowAggregateSync) {

      this.workflowAggregateSync = workflowAggregateSync;
      return this;

    }

    /**
     * Hands over the hook a phase-one check is run by.
     *
     * @param preCommitRegistrar Where work is hung which has to happen before the
     *                           caller's transaction commits - mandatory, so
     *                           {@link #build()} refuses a set without it
     * @return This builder
     */
    public Builder preCommitRegistrar(
        final PreCommitRegistrar preCommitRegistrar) {

      this.preCommitRegistrar = preCommitRegistrar;
      return this;

    }

    /**
     * Hands over where the end of a workflow is reported.
     *
     * @param workflowEndedInvoker May be null: an application without a
     *                             {@code @WorkflowEnded} method has none
     * @return This builder
     */
    public Builder workflowEndedInvoker(
        final WorkflowEndedInvoker workflowEndedInvoker) {

      this.workflowEndedInvoker = workflowEndedInvoker;
      return this;

    }

    /**
     * Hands over where a workflow the BPMS started by itself is reported.
     *
     * @param bpmsInitiatedStartInvoker May be null: an application which never lets the
     *                                  BPMS start a workflow has none
     * @return This builder
     */
    public Builder bpmsInitiatedStartInvoker(
        final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker) {

      this.bpmsInitiatedStartInvoker = bpmsInitiatedStartInvoker;
      return this;

    }

    /**
     * Closes the set: throws where a mandatory collaborator is missing, and warns about
     * the optional ones which did not arrive. Both messages name the adapter id, because
     * an application running several adapters otherwise learns nothing from them.
     *
     * @return The collaborators, complete
     * @throws IllegalStateException If a mandatory collaborator is missing - the message
     *                               names the adapter id and every one of them
     */
    public AdapterCollaborators build() {

      final var missing = new ArrayList<String>();
      collect(missing, workflowTaskWiring, "workflowTaskWiring");
      collect(missing, workflowTaskInvoker, "workflowTaskInvoker");
      collect(missing, scoping, "scoping");
      collect(missing, workflowAggregateSync, "workflowAggregateSync");
      collect(missing, preCommitRegistrar, "preCommitRegistrar");
      if (!missing.isEmpty()) {
        throw new IllegalStateException(
            ("The adapter '%s' cannot be built: the platform did not hand over %s. Every adapter is "
                + "given these, so this is a defect of the registration code of that adapter and not "
                + "something an application can configure away.")
                .formatted(adapterId, String.join(", ", missing)));
      }

      reportWhatIsMissing();

      return new AdapterCollaborators(this);

    }

    private static void collect(
        final List<String> missing,
        final Object collaborator,
        final String name) {

      if (collaborator == null) {
        missing.add("'"
            + name
            + "'");
      }

    }

    /**
     * Says which of the optional collaborators this adapter did not get. An adapter has
     * to cope without them, but both platform integrations hand them over for every
     * application, so their absence is worth a warning rather than a note: an operator
     * wondering why no workflow end arrives finds the answer in the log of the boot
     * which caused it.
     */
    private void reportWhatIsMissing() {

      final var absent = new ArrayList<String>();
      collect(absent, workflowEndedInvoker, "workflowEndedInvoker");
      collect(absent, bpmsInitiatedStartInvoker, "bpmsInitiatedStartInvoker");
      if (absent.isEmpty()) {
        return;
      }

      log
          .warn(
              "Adapter '{}' was built without {}: workflows ending or started by the BPMS are not "
                  + "reported to the application through this adapter, and nothing else will fail "
                  + "because of it. Both platform integrations provide these, so this is the "
                  + "registration of the adapter having left them out.",
              adapterId,
              String.join(" and ", absent));

    }

  }

}
