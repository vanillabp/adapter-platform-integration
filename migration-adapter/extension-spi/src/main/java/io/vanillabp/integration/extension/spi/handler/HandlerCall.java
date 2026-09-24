package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One invocation of an extension's handler method: which method is meant, which
 * workflow it runs for and what its parameters are bound from.
 * <p>
 * The transaction is not part of it. VanillaBP takes part in the transaction the caller
 * of the extension is in and opens one only where none runs, which serves an extension
 * called from an application's own transaction as well as one called from a thread where
 * nothing is open.
 * <p>
 * The workflow aggregate is named in one of two ways. Usually the call carries its ID
 * and VanillaBP loads it, runs the method and saves it - the steps a workflow task goes
 * through. Where the aggregate does not exist yet, the caller builds one and hands it in
 * ({@link Builder#workflowAggregate(Object)}); it is then saved like a loaded one unless
 * the call says otherwise.
 *
 * @see ExtensionHandlers#invoke(HandlerCall)
 */
public final class HandlerCall {

  private final Class<? extends Annotation> annotationType;

  private final String workflowModuleId;

  private final String bpmnProcessId;

  private final List<String> lookupKeys;

  private final String processVersion;

  private final Object workflowAggregateId;

  private final Object workflowAggregate;

  private final boolean aggregateProvided;

  private final Map<String, Object> variables;

  private final Map<String, HandlerMultiInstance> multiInstances;

  private final Object payload;

  private final boolean savesWorkflowAggregate;

  private HandlerCall(
      final Builder builder) {

    this.annotationType = builder.annotationType;
    this.workflowModuleId = builder.workflowModuleId;
    this.bpmnProcessId = builder.bpmnProcessId;
    this.lookupKeys = List.copyOf(builder.lookupKeys);
    this.processVersion = builder.processVersion;
    this.workflowAggregateId = builder.workflowAggregateId;
    this.workflowAggregate = builder.workflowAggregate;
    this.aggregateProvided = builder.aggregateProvided;
    this.variables = Map.copyOf(builder.variables);
    // the order the caller added the scopes in survives, because nested multi-instance
    // elements are read from the outside in and a map without an order would leave a
    // reader guessing which scope is which
    this.multiInstances = Collections.unmodifiableMap(new LinkedHashMap<>(builder.multiInstances));
    this.payload = builder.payload;
    this.savesWorkflowAggregate = builder.savesWorkflowAggregate;

  }

  /**
   * Starts building a call of a method of the given contract.
   *
   * @param annotationType The annotation of the contract to invoke
   * @param workflowModuleId The workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process the workflow belongs to
   * @return The builder
   */
  public static Builder of(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return new Builder(annotationType, workflowModuleId, bpmnProcessId);

  }

  /**
   * The annotation the invoked methods carry. It selects the {@link HandlerContract} the
   * call is served by, so a call naming an annotation no extension described is refused
   * rather than silently finding nothing.
   *
   * @return The annotation of the contract to invoke
   */
  public Class<? extends Annotation> getAnnotationType() {

    return annotationType;

  }

  /**
   * The workflow module of the workflow this call is about - one half of the pair which
   * names the BPMN process whose methods are looked up.
   *
   * @return The workflow module of the workflow
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The BPMN process of the workflow this call is about. Nothing is registered for a
   * process whose workflow service carries no method of the annotation, and a call for it
   * finds nothing instead of failing.
   *
   * @return The BPMN process of the workflow
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * The keys this call offers, most wanted first. The first of them some method serves
   * decides, so the order says which identity of a BPMN element the caller prefers (see
   * decision 51 in the repository's DECISIONS.md).
   *
   * @return The keys a method may be matched by - a method serving ANY of them runs
   */
  public List<String> getLookupKeys() {

    return lookupKeys;

  }

  /**
   * The version of the BPMN process this call is about, as the BPMS reports it - what
   * decides between methods serving different versions
   * ({@link HandlerContract.Builder#versions(java.util.function.Function)}).
   *
   * @return The version, or <code>null</code> where the BPMS reported none
   */
  public String getProcessVersion() {

    return processVersion;

  }

  /**
   * The aggregate VanillaBP loads for this call, named by its ID. A call names either this
   * or the aggregate itself.
   *
   * @return The ID of the workflow aggregate to load, or <code>null</code> where the
   *         caller handed one in
   */
  public Object getWorkflowAggregateId() {

    return workflowAggregateId;

  }

  /**
   * The aggregate the caller built, for an event about a workflow whose aggregate does not
   * exist yet. Ask {@link #isWorkflowAggregateProvided()} before you read it: a caller may
   * hand in nothing, and this is <code>null</code> in both cases.
   *
   * @return The workflow aggregate the caller built, or <code>null</code> where the
   *         call names its ID instead
   */
  public Object getWorkflowAggregate() {

    return workflowAggregate;

  }

  /**
   * Whether the aggregate travels with the call or is loaded from its ID. This and not a
   * <code>null</code> check is what decides: a caller may hand in nothing, and the method
   * then runs without an aggregate and nothing is saved afterwards.
   *
   * @return Whether the caller handed in the aggregate rather than its ID
   */
  public boolean isWorkflowAggregateProvided() {

    return aggregateProvided;

  }

  /**
   * The process variables this call offers to the method's parameters.
   *
   * @return The process variables <code>&#64;TaskParam</code> parameters read
   */
  public Map<String, Object> getVariables() {

    return variables;

  }

  /**
   * The multi-instance scopes this call runs in, one per BPMN element carrying
   * multi-instance characteristics. Read a scope by the element id it is keyed under, or
   * walk the map: it keeps the order the scopes were added in, which for nested elements
   * is outermost first.
   *
   * @return The multi-instance scopes of this invocation, keyed by BPMN element id
   */
  public Map<String, HandlerMultiInstance> getMultiInstances() {

    return multiInstances;

  }

  /**
   * The extension's own object for this call. Only the binders the extension contributed
   * read it; VanillaBP hands it through without looking into it.
   *
   * @return The extension's own event object the binders of the extension read
   */
  public Object getPayload() {

    return payload;

  }

  /**
   * Whether VanillaBP saves the aggregate after the method returned. A contract stating
   * that none of its methods writes outranks this, so a call of such a contract never
   * saves.
   *
   * @return Whether the aggregate is saved after the method returned
   */
  public boolean savesWorkflowAggregate() {

    return savesWorkflowAggregate;

  }

  /**
   * Builds a {@link HandlerCall}.
   */
  public static final class Builder {

    private final Class<? extends Annotation> annotationType;

    private final String workflowModuleId;

    private final String bpmnProcessId;

    private List<String> lookupKeys = List.of();

    private String processVersion;

    private Object workflowAggregateId;

    private Object workflowAggregate;

    private boolean aggregateProvided = false;

    private final Map<String, Object> variables = new LinkedHashMap<>();

    private final Map<String, HandlerMultiInstance> multiInstances = new LinkedHashMap<>();

    private Object payload;

    private boolean savesWorkflowAggregate = true;

    private Builder(
        final Class<? extends Annotation> annotationType,
        final String workflowModuleId,
        final String bpmnProcessId) {

      this.annotationType = annotationType;
      this.workflowModuleId = workflowModuleId;
      this.bpmnProcessId = bpmnProcessId;

    }

    /**
     * The keys the method may be matched by - typically the BPMN element id and the task
     * definition of the element the event belongs to. A method serving any of them runs;
     * a method serving {@link HandlerContract#EVERY_KEY} runs where no key is served at
     * all.
     * <p>
     * <b>The order decides.</b> The first key some method serves wins, so hand in a list
     * whose order says which key you prefer, and put the BPMN element id first: it is the
     * identity VanillaBP is moving to, and a list which starts with it keeps working
     * unchanged when the task definition goes away (see decision 51 in the repository's
     * DECISIONS.md).
     *
     * @param lookupKeys The keys, most wanted first; <code>null</code> entries are
     *          ignored
     * @return This builder
     */
    public Builder lookupKeys(
        final Collection<String> lookupKeys) {

      this.lookupKeys = lookupKeys == null
          ? List.of()
          : lookupKeys
              .stream()
              .filter(java.util.Objects::nonNull)
              .toList();
      return this;

    }

    /**
     * The version of the BPMN process this call is about, which decides between methods
     * serving different versions of one model.
     * <p>
     * Pass the version identifier THE BPMS reports, spelled the way it reports it: the
     * number for Camunda 7 and Camunda 8, the version tag where that is all an engine
     * has. It is compared to what the methods name, so a version dressed up for a
     * screen ("3 (release-2024)") matches nothing.
     * <p>
     * Where a BPMS reports no version, leave it out. A call without a version is served
     * by a method naming no version only, and a method which does name one is reported
     * at startup instead of silently never running - see
     * {@link HandlerContract.Builder#callsCarryTheProcessVersion()}.
     *
     * @param processVersion The version, or <code>null</code> where none is known
     * @return This builder
     */
    public Builder processVersion(
        final String processVersion) {

      this.processVersion = processVersion;
      return this;

    }

    /**
     * The workflow aggregate VanillaBP loads for this invocation.
     *
     * @param workflowAggregateId Its ID, in the aggregate's own ID type or serialized
     * @return This builder
     */
    public Builder workflowAggregateId(
        final Object workflowAggregateId) {

      this.workflowAggregateId = workflowAggregateId;
      return this;

    }

    /**
     * The workflow aggregate the caller built - for an event about a workflow whose
     * aggregate does not exist yet.
     *
     * @param workflowAggregate The aggregate to hand to the method
     * @return This builder
     */
    public Builder workflowAggregate(
        final Object workflowAggregate) {

      this.workflowAggregate = workflowAggregate;
      this.aggregateProvided = true;
      return this;

    }

    /**
     * Adds one process variable of this invocation.
     *
     * @param name The name of a process variable
     * @param value Its value
     * @return This builder
     */
    public Builder variable(
        final String name,
        final Object value) {

      variables.put(name, value);
      return this;

    }

    /**
     * Adds process variables to the ones the builder already holds, rather than replacing
     * them.
     *
     * @param variables The process variables of this invocation, <code>null</code> is
     *          ignored
     * @return This builder
     */
    public Builder variables(
        final Map<String, Object> variables) {

      if (variables != null) {
        this.variables.putAll(variables);
      }
      return this;

    }

    /**
     * Adds the current iteration of one multi-instance element. Add one per element the
     * invocation runs in, nested ones included, because a parameter of the method may name
     * any of them.
     *
     * Nested elements are added from the outside in, because that is the order the call
     * hands them on in.
     *
     * @param elementId The BPMN element carrying the multi-instance characteristics
     * @param multiInstance Its current iteration
     * @return This builder
     * @throws NullPointerException If the element id or the iteration is
     *           <code>null</code>. A scope without either is a scope nothing can be
     *           bound from, and it is refused here rather than at the parameter which
     *           tries
     */
    public Builder multiInstance(
        final String elementId,
        final HandlerMultiInstance multiInstance) {

      multiInstances
          .put(
              java.util.Objects.requireNonNull(elementId, "a multi-instance scope needs the id of its BPMN element"),
              java.util.Objects.requireNonNull(multiInstance, "a multi-instance scope needs its current iteration"));
      return this;

    }

    /**
     * Hands the extension's own object to the binders the extension contributed. Anything
     * a method of the extension needs and VanillaBP knows nothing about travels here.
     *
     * @param payload The extension's own event object, read by the binders it
     *          contributed
     * @return This builder
     */
    public Builder payload(
        final Object payload) {

      this.payload = payload;
      return this;

    }

    /**
     * Runs the method WITHOUT saving the aggregate afterwards - for an event which only
     * reads (the Business Cockpit building the details of a user task somebody opened).
     * <p>
     * A contract which says that none of its methods ever writes
     * ({@link HandlerContract.Builder#neverSavesTheWorkflowAggregate()}) makes every call
     * of it a reading one, so this is only needed where some calls write and others do
     * not.
     * <p>
     * What it switches off is the save VanillaBP performs. A persistence layer which
     * writes what changed on a managed object by itself - JPA's dirty checking - still
     * writes it when the transaction commits, so a handler meant to change nothing has to
     * change nothing.
     *
     * @return This builder
     */
    public Builder withoutSavingTheWorkflowAggregate() {

      this.savesWorkflowAggregate = false;
      return this;

    }

    /**
     * Builds the call, and refuses one which names no workflow aggregate at all.
     *
     * @return The call
     * @throws IllegalArgumentException If the call names neither the ID of an aggregate to
     *           load nor an aggregate to hand in (guiding message)
     */
    public HandlerCall build() {

      if (!aggregateProvided && (workflowAggregateId == null)) {
        throw new IllegalArgumentException(
            """
                The handler call for BPMN process '%s' of workflow module '%s' names neither the ID of \
                a workflow aggregate to load nor an aggregate to hand in! Call workflowAggregateId(...) \
                for a workflow which exists, workflowAggregate(...) for one whose aggregate you built \
                yourself."""
                .formatted(bpmnProcessId, workflowModuleId));
      }
      return new HandlerCall(this);

    }

  }

}
