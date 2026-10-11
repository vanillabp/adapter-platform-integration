package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One invocation of an extension's handler method: which method is meant, which
 * workflow it runs for, and where its parameters get their values from.
 * <p>
 * The transaction is not part of it. VanillaBP joins the transaction of the caller of the
 * extension, and opens one only if none is running. This works for an extension called
 * from an application's own transaction, and for one called from a thread where nothing
 * is open.
 * <p>
 * The workflow aggregate is named in one of two ways. Usually the call carries its ID.
 * VanillaBP loads it, runs the method and saves it, the same steps a workflow task goes
 * through. If the aggregate does not exist yet, the caller builds one and passes it in
 * ({@link Builder#workflowAggregate(Object)}). It is then saved like a loaded one, unless
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
    // keep the order in which the caller added the scopes: nested multi-instance
    // elements are read from the outside in, and with a map without an order a
    // reader would have to guess which scope is which
    this.multiInstances = Collections.unmodifiableMap(new LinkedHashMap<>(builder.multiInstances));
    this.payload = builder.payload;
    this.savesWorkflowAggregate = builder.savesWorkflowAggregate;

  }

  /**
   * Starts building a call of a method of the given contract.
   *
   * @param annotationType The annotation of the contract to invoke
   * @param workflowModuleId The workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process the element belongs to. For an element of a called
   *          process this is the called process, not the one at the top (see
   *          {@link #getBpmnProcessId()})
   * @return The builder
   */
  public static Builder of(
      final Class<? extends Annotation> annotationType,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return new Builder(annotationType, workflowModuleId, bpmnProcessId);

  }

  /**
   * The annotation the invoked methods carry. It selects the {@link HandlerContract} which
   * serves the call. So a call which names an annotation no extension described is
   * refused. It does not silently find nothing.
   *
   * @return The annotation of the contract to invoke
   */
  public Class<? extends Annotation> getAnnotationType() {

    return annotationType;

  }

  /**
   * The workflow module of the workflow this call is about. Together with the BPMN process
   * id, it names the BPMN process whose methods are looked up.
   *
   * @return The workflow module of the workflow
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * The BPMN process this call is about: the process the element belongs to. Nothing is
   * registered for a process whose workflow service has no method with the annotation. A
   * call for such a process finds nothing, and it does not fail.
   * <p>
   * For an element of a called process (a call activity) this is the called process. It is
   * not the process at the top, which the workflow aggregate was started with. Methods are
   * bound per workflow service class and per process it declares, also the called one
   * through <code>secondaryBpmnProcesses</code>. A class serves every process it declares,
   * so the id of the process at the top may find the same method. But the version of the
   * call belongs to the called process. Under the id of the process at the top, it is
   * compared with the versions of the wrong process. Pass the id the BPMS reports for the
   * element.
   *
   * @return The BPMN process the element belongs to
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * The keys this call offers, most wanted first. The first key which some method serves
   * decides. So the order says which identity of a BPMN element the caller prefers (see
   * decision 51 in the repository's DECISIONS.md).
   *
   * @return The keys a method may be matched by. A method which serves ANY of them runs
   */
  public List<String> getLookupKeys() {

    return lookupKeys;

  }

  /**
   * The version of the BPMN process this call is about ({@link #getBpmnProcessId()}), as
   * the BPMS reports it. It decides between methods which serve different versions
   * ({@link HandlerContract.Builder#versions(java.util.function.Function)}).
   *
   * @return The version, or <code>null</code> if the BPMS reported none
   */
  public String getProcessVersion() {

    return processVersion;

  }

  /**
   * The aggregate VanillaBP loads for this call, named by its ID. A call names either this
   * or the aggregate itself.
   *
   * @return The ID of the workflow aggregate to load, or <code>null</code> if the
   *         caller passed one in
   */
  public Object getWorkflowAggregateId() {

    return workflowAggregateId;

  }

  /**
   * The aggregate the caller built, for an event about a workflow whose aggregate does not
   * exist yet. Ask {@link #isWorkflowAggregateProvided()} before you read it. A caller may
   * pass in nothing, and this is <code>null</code> in both cases.
   *
   * @return The workflow aggregate the caller built, or <code>null</code> if the
   *         call names its ID instead
   */
  public Object getWorkflowAggregate() {

    return workflowAggregate;

  }

  /**
   * Whether the aggregate comes with the call, or is loaded by its ID. This decides, not a
   * <code>null</code> check. A caller may pass in nothing. Then the method runs without an
   * aggregate, and nothing is saved afterwards.
   *
   * @return Whether the caller passed in the aggregate instead of its ID
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
   * The multi-instance scopes this call runs in, one per BPMN element which has
   * multi-instance characteristics. Read a scope by its element id, or walk through the
   * map. It keeps the order in which the scopes were added. For nested elements that is
   * outermost first.
   *
   * @return The multi-instance scopes of this invocation, keyed by BPMN element id
   */
  public Map<String, HandlerMultiInstance> getMultiInstances() {

    return multiInstances;

  }

  /**
   * The extension's own object for this call. Only the binders of the extension read it.
   * VanillaBP passes it on without looking into it.
   *
   * @return The extension's own event object the binders of the extension read
   */
  public Object getPayload() {

    return payload;

  }

  /**
   * Whether VanillaBP saves the aggregate after the method returned. If a contract says
   * that none of its methods writes, that wins over this. So a call of such a contract
   * never saves.
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
     * The keys the method may be matched by, usually the BPMN element id and the task
     * definition of the element the event belongs to. A method which serves any of them
     * runs. A method which serves {@link HandlerContract#EVERY_KEY} runs if no key is
     * served at all.
     * <p>
     * <b>The order decides.</b> The first key which some method serves wins. So pass a
     * list whose order says which key you prefer, and put the BPMN element id first.
     * VanillaBP is moving to it as the identity of an element. A list which starts with it
     * keeps working unchanged when the task definition goes away (see decision 51 in the
     * repository's DECISIONS.md).
     *
     * @param lookupKeys The keys, most wanted first. <code>null</code> entries are
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
     * The version of the BPMN process this call is about. It decides between methods
     * which serve different versions of one model.
     * <p>
     * Pass the version identifier THE BPMS reports, spelled the way the BPMS spells it:
     * the number for Camunda 7 and Camunda 8, or the version tag if that is all an engine
     * has. It is compared with what the methods name. So a version formatted for a
     * screen ("3 (release-2024)") matches nothing.
     * <p>
     * If a BPMS reports no version, leave it out. A call without a version is served
     * only by a method which names no version. A method which does name one is reported
     * at startup, so it does not silently never run. See
     * {@link HandlerContract.Builder#callsCarryTheProcessVersion()}.
     *
     * @param processVersion The version, or <code>null</code> if none is known
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
     * The workflow aggregate the caller built, for an event about a workflow whose
     * aggregate does not exist yet.
     *
     * @param workflowAggregate The aggregate to pass to the method
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
     * Adds process variables to the ones the builder already holds. It does not replace
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
     * Adds the current iteration of one multi-instance element. Add one for each element
     * the invocation runs in, nested ones included, because a parameter of the method may
     * name any of them.
     *
     * Add nested elements from the outside in, because the call passes them on in that
     * order.
     *
     * @param elementId The BPMN element carrying the multi-instance characteristics
     * @param multiInstance Its current iteration
     * @return This builder
     * @throws NullPointerException If the element id or the iteration is
     *           <code>null</code>. Nothing can be bound from a scope without them. So it is
     *           refused here, and not later at the parameter which tries
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
     * Passes the extension's own object to the binders of the extension. Anything which a
     * method of the extension needs, and which VanillaBP knows nothing about, goes here.
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
     * Runs the method WITHOUT saving the aggregate afterwards. This is for an event which
     * only reads (the Business Cockpit building the details of a user task somebody
     * opened).
     * <p>
     * If a contract says that none of its methods ever writes
     * ({@link HandlerContract.Builder#neverSavesTheWorkflowAggregate()}), every call of it
     * only reads. So this is only needed if some calls write and others do not.
     * <p>
     * It switches off the save VanillaBP does. Some persistence layers write the changes
     * of a managed object by themselves, like the dirty checking of JPA. They still write
     * when the transaction commits. So a handler which should change nothing really has
     * to change nothing.
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
     *           load nor an aggregate to pass in (guiding message)
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
