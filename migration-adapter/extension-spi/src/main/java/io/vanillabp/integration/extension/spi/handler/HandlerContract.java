package io.vanillabp.integration.extension.spi.handler;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What an extension tells VanillaBP about an annotation of its own, so that the
 * mechanics behind <code>&#64;WorkflowTask</code> serve it as well. It says which methods
 * of a <code>&#64;WorkflowService</code> class belong to the extension, what they are
 * matched by, what may stand in their parameter list, and whether what they return is
 * handed back to the caller.
 * <p>
 * A contract is registered once at startup ({@link ExtensionHandlers#register}). The
 * methods it describes are then invoked through
 * {@link ExtensionHandlers#invoke(HandlerCall)}. VanillaBP loads the workflow aggregate,
 * binds the parameters, calls the method and saves the aggregate, all in one transaction.
 * These are the same steps a workflow task goes through.
 * <p>
 * <b>Matching.</b> Every method with the annotation serves the lookup keys which
 * {@link Builder#lookupKeys(Function)} reads from it. A method whose annotation names no
 * key serves its own METHOD NAME. <code>&#64;WorkflowTask</code> follows the same
 * convention. {@link #EVERY_KEY} makes a method serve every key of its BPMN process. An
 * invocation passes in the keys it accepts (an element id and a task definition, for
 * example), and the method which NAMES one of them runs. If no method names one, the
 * method which serves every key runs. So a catch-all may stand next to methods for single
 * elements, and the specific one wins. <code>&#64;WorkflowStartedByBpms</code> follows
 * the same rule for its start events.
 * <p>
 * <b>The order of the invocation's list decides which key wins.</b> If two methods serve
 * one element under two keys, the key offered first decides. So with that order an
 * extension says which identity of an element it prefers. The keys a METHOD declares have
 * no such rank. A method says what it answers to, and that is a set (see decision 51 in
 * the repository's DECISIONS.md).
 * <p>
 * <b>Versions narrow a method further.</b> If your annotation has a version attribute,
 * {@link Builder#versions(Function)} reads it. VanillaBP then applies the same selection
 * its own <code>&#64;WorkflowTask</code> uses. A method runs for the versions of the BPMN
 * process it names. A method which names none runs for every version, and that is what a
 * contract which says nothing about versions gets. The call names the version it is about
 * ({@link HandlerCall.Builder#processVersion(String)}). A contract whose calls carry a
 * version says so once ({@link Builder#callsCarryTheProcessVersion()}). Then a method
 * nothing could ever reach is reported at startup, not at the first event.
 * <p>
 * <b>Zero matches are allowed.</b> Nothing is registered for a BPMN process whose workflow
 * service has no such method. For it, {@link ExtensionHandlers#hasHandler} answers
 * <code>false</code>, and an invocation returns an empty result. What an extension does
 * instead is up to the extension (the Business Cockpit passes its prefilled details
 * through unchanged). But if two methods name the same key of one BPMN process, the
 * application does not start. The same holds for two catch-alls, because nobody can guess
 * which of them was meant. Two methods whose versions do not overlap are not such a pair,
 * because the version says which of them is meant.
 * <p>
 * <b>Saving the workflow aggregate is the normal case.</b> A call may switch it off
 * ({@link HandlerCall.Builder#withoutSavingTheWorkflowAggregate()}). If the methods of an
 * extension never write, it says so here instead
 * ({@link Builder#neverSavesTheWorkflowAggregate()}), once, for all of them.
 */
public final class HandlerContract {

  /**
   * A lookup key which stands for "every key of this BPMN process". It is the default of
   * the Business Cockpit's <code>&#64;WorkflowDetailsProvider</code>, which exists once per
   * process. It is also the default of a <code>&#64;WorkflowStartedByBpms</code> method
   * which names no start event.
   */
  public static final String EVERY_KEY = "*";

  private final String extensionId;

  private final Class<? extends Annotation> annotationType;

  private final Function<Annotation, List<String>> lookupKeys;

  private final Function<Annotation, List<String>> versions;

  private final Set<CoreHandlerParameter> coreParameters;

  private final List<HandlerParameterBinder> parameterBinders;

  private final HandlerAnnotationCheck annotationCheck;

  private final boolean deliversReturnValue;

  private final boolean savesWorkflowAggregate;

  private final boolean callsCarryTheProcessVersion;

  private final boolean deployedVersionNeedsAMethod;

  private final String consequenceOfAnElementWithoutAMethod;

  private HandlerContract(
      final Builder builder) {

    this.extensionId = builder.extensionId;
    this.annotationType = builder.annotationType;
    this.lookupKeys = builder.lookupKeys;
    this.versions = builder.versions;
    this.callsCarryTheProcessVersion = builder.callsCarryTheProcessVersion;
    this.deployedVersionNeedsAMethod = builder.deployedVersionNeedsAMethod;
    this.consequenceOfAnElementWithoutAMethod = builder.consequenceOfAnElementWithoutAMethod;
    this.coreParameters = Set.copyOf(builder.coreParameters);
    this.parameterBinders = List.copyOf(builder.parameterBinders);
    this.annotationCheck = builder.annotationCheck;
    this.deliversReturnValue = builder.deliversReturnValue;
    this.savesWorkflowAggregate = builder.savesWorkflowAggregate;

  }

  /**
   * Starts building a contract.
   *
   * @param extensionId The extension's id, e.g. <code>business-cockpit</code>. It
   *          names the extension in every message about one of its methods
   * @param annotationType The annotation the extension's methods carry. Repeatable
   *          annotations are supported. A method then serves every key of every
   *          repetition
   * @return The builder
   */
  public static Builder of(
      final String extensionId,
      final Class<? extends Annotation> annotationType) {

    return new Builder(extensionId, annotationType);

  }

  /**
   * The extension this contract belongs to. It names the extension in every message about
   * one of its methods, so a developer can read which library asked for what.
   *
   * @return The id of the extension owning this contract
   */
  public String getExtensionId() {

    return extensionId;

  }

  /**
   * The annotation the extension's handler methods carry. A {@link HandlerCall} names it to
   * reach them, and the scan looks for it on the methods of a
   * <code>&#64;WorkflowService</code> class.
   *
   * @return The annotation the extension's handler methods carry
   */
  public Class<? extends Annotation> getAnnotationType() {

    return annotationType;

  }

  /**
   * How the keys a method serves are read out of one use of the annotation.
   *
   * @return Reads the keys one use of the annotation names, or an empty list
   *         for "the method's name"
   */
  public Function<Annotation, List<String>> getLookupKeys() {

    return lookupKeys;

  }

  /**
   * How the process versions a method serves are read out of one use of the annotation.
   *
   * @return Reads the version specifications one use of the annotation names,
   *         or an empty list for "every version"
   */
  public Function<Annotation, List<String>> getVersions() {

    return versions;

  }

  /**
   * Whether the calls of this contract name the version of the BPMN process. Only the
   * extension can answer it. With the answer, the startup can report a method which no call
   * could ever reach (see decision 56 in the repository's DECISIONS.md).
   *
   * @return Whether the calls of this contract name the version of the BPMN process they
   *         are about
   */
  public boolean callsCarryTheProcessVersion() {

    return callsCarryTheProcessVersion;

  }

  /**
   * Whether the start ends if an element of the deployed model has methods of this contract,
   * but none of them covers the version which the BPMS gave that model. The extension reports
   * the elements through
   * {@link ExtensionHandlers#elementsOfTheDeployedModel(Class, String, String, List)}.
   *
   * @return Whether the deployed version needs a method for each reported element
   * @see Builder#theDeployedVersionNeedsAMethod()
   */
  public boolean deployedVersionNeedsAMethod() {

    return deployedVersionNeedsAMethod;

  }

  /**
   * What it means for the extension if an element has no method in the version which this
   * start deployed. The message which ends the start shows it next to the element.
   *
   * @return The sentence the extension gave. <code>null</code> if it gave none, then the
   *         message uses a neutral sentence of its own
   * @see Builder#theDeployedVersionNeedsAMethod(String)
   */
  public String getConsequenceOfAnElementWithoutAMethod() {

    return consequenceOfAnElementWithoutAMethod;

  }

  /**
   * Which of the parameters VanillaBP binds itself the methods of this contract may ask
   * for. A parameter of a kind which is left out is refused while the method is scanned. So
   * the developer reads about it at the start, and does not get <code>null</code> later.
   *
   * @return The parameter kinds VanillaBP binds for these methods
   */
  public Set<CoreHandlerParameter> getCoreParameters() {

    return coreParameters;

  }

  /**
   * The binders of the parameter kinds which the extension's own SPI adds. They are asked
   * before VanillaBP tries its own. So an extension may serve a parameter which VanillaBP
   * would otherwise take.
   *
   * @return The binders the extension contributes, asked in the order they were added
   */
  public List<HandlerParameterBinder> getParameterBinders() {

    return parameterBinders;

  }

  /**
   * What the extension checks about one use of its annotation. It runs while the scan still
   * holds the method which carries the annotation.
   *
   * @return What the extension checks about one use of its annotation while the
   *         method carrying it is scanned, or <code>null</code> if it checks nothing
   */
  public HandlerAnnotationCheck getAnnotationCheck() {

    return annotationCheck;

  }

  /**
   * Whether what a method returns reaches the caller of
   * {@link ExtensionHandlers#invoke(HandlerCall)}. If it does not, a method of this
   * contract has to be <code>void</code>.
   *
   * @return Whether what a method returns is handed back to the caller
   */
  public boolean deliversReturnValue() {

    return deliversReturnValue;

  }

  /**
   * Whether VanillaBP saves the workflow aggregate after a method of this contract ran.
   * With <code>false</code> the extension says that none of its methods writes. Then no
   * call of this contract saves either.
   *
   * @return Whether the aggregate is saved if the call asks for it
   */
  public boolean savesWorkflowAggregate() {

    return savesWorkflowAggregate;

  }

  /**
   * Builds a {@link HandlerContract}.
   */
  public static final class Builder {

    private final String extensionId;

    private final Class<? extends Annotation> annotationType;

    private Function<Annotation, List<String>> lookupKeys = annotation -> List.of();

    private Function<Annotation, List<String>> versions = annotation -> List.of();

    private final Set<CoreHandlerParameter> coreParameters = new LinkedHashSet<>();

    private final List<HandlerParameterBinder> parameterBinders = new java.util.LinkedList<>();

    private HandlerAnnotationCheck annotationCheck;

    private boolean deliversReturnValue = false;

    private boolean savesWorkflowAggregate = true;

    private boolean callsCarryTheProcessVersion = false;

    private boolean deployedVersionNeedsAMethod = false;

    private String consequenceOfAnElementWithoutAMethod;

    private Builder(
        final String extensionId,
        final Class<? extends Annotation> annotationType) {

      this.extensionId = extensionId;
      this.annotationType = annotationType;

    }

    /**
     * How the keys a method serves are read from one use of the annotation, usually
     * from the annotation's <code>id</code> or <code>taskDefinition</code> attribute. An
     * EMPTY list means "the method's name", the convention VanillaBP's own annotations
     * follow. {@link HandlerContract#EVERY_KEY} makes the method serve every key of its
     * BPMN process.
     * <p>
     * Without this the methods are matched by their name only.
     *
     * @param lookupKeys Reads the keys of one use of the annotation
     * @return This builder
     */
    public Builder lookupKeys(
        final Function<Annotation, List<String>> lookupKeys) {

      this.lookupKeys = lookupKeys;
      return this;

    }

    /**
     * How the process versions a method serves are read from one use of the
     * annotation, usually from its <code>version</code> attribute. The specifications
     * are written the way <code>&#64;WorkflowTask(version = ...)</code> writes them,
     * version tags included: <code>3</code>, <code>release-2024</code>, <code>1-3</code>,
     * <code>v1.0..v2.0</code>, <code>&gt;3</code>, <code>&lt;=v2.0</code> and
     * <code>*</code> for every version.
     * <p>
     * An EMPTY list means every version. A method which names nothing serves every
     * version, and so do all methods of a contract which leaves this out. Two methods
     * which serve one key stop the start of the application only if their versions
     * overlap. So one method per generation of a model is a valid way to write them.
     * <p>
     * A method is compared with the version the call names
     * ({@link HandlerCall.Builder#processVersion(String)}). Say
     * {@link #callsCarryTheProcessVersion()} if your calls name one. Then a method
     * VanillaBP could never reach is reported at startup.
     *
     * @param versions Reads the version specifications of one use of the
     *          annotation
     * @return This builder
     */
    public Builder versions(
        final Function<Annotation, List<String>> versions) {

      this.versions = versions;
      return this;

    }

    /**
     * Declares that the calls of this contract name the version of the BPMN process they
     * are about ({@link HandlerCall.Builder#processVersion(String)}), whenever the BPMS
     * reports one.
     * <p>
     * This makes a version attribute usable, and VanillaBP checks the methods of an
     * application against it at startup. Without it, a method which names a version can
     * never run, because a call without a version is served by a method which names none.
     * The startup says so for every such method. The application does not have to notice
     * it at the first event which does not arrive.
     * <p>
     * Say it only if a version really arrives. The BPMS your extension runs on decides
     * that. A Camunda 8 job carries the version of its process, and a Camunda 7 process
     * definition knows it. An engine behind the Process-Engine-API fills a version tag only
     * if it wants to. If your extension has one part per BPMS, this answers for the part in
     * use. A single event which arrives without a version is served by the methods which
     * name none.
     *
     * @return This builder
     */
    public Builder callsCarryTheProcessVersion() {

      this.callsCarryTheProcessVersion = true;
      return this;

    }

    /**
     * Says that the version which a start deployed needs a method of this contract for
     * each element the extension reports, if that element has methods at all. If none of
     * them covers that version, the start ends. The message is the same as for a
     * <code>&#64;WorkflowTask</code> method in that case.
     * <p>
     * The reason is the same as for <code>&#64;WorkflowTask</code>. VanillaBP checks the
     * wiring before the deployment, when nobody knows the version yet. But a call picks its
     * method by version. So a method whose range misses the deployed version passes the
     * start. After that, no call of that version ever finds it. Use this where such a silent
     * miss is a defect of the application, and not a choice.
     * <p>
     * VanillaBP knows the methods, but not the elements of the model. So the extension names
     * them: call {@link ExtensionHandlers#elementsOfTheDeployedModel(Class, String, String, List)}
     * from its <code>wireBpmn</code>. If an element has no method of this contract, the check
     * does not ask about it, because "no method" is a valid answer. The calls of this
     * contract must name the version too ({@link #callsCarryTheProcessVersion()}), because the
     * methods are checked against a version.
     *
     * @return This builder
     */
    public Builder theDeployedVersionNeedsAMethod() {

      this.deployedVersionNeedsAMethod = true;
      return this;

    }

    /**
     * Does the same as {@link #theDeployedVersionNeedsAMethod()}, and adds one sentence to
     * the message which ends the start. The sentence says what an element without a method
     * means for your extension. Then the developer knows what the defect costs. For example:
     * "The cockpit shows such a task without the details of its provider." Without it, the
     * message uses a neutral sentence.
     *
     * @param consequenceOfAnElementWithoutAMethod One full sentence, written for the
     *          developer of the application
     * @return This builder
     */
    public Builder theDeployedVersionNeedsAMethod(
        final String consequenceOfAnElementWithoutAMethod) {

      this.deployedVersionNeedsAMethod = true;
      final var saysNothing = (consequenceOfAnElementWithoutAMethod == null) || consequenceOfAnElementWithoutAMethod
          .isBlank();
      this.consequenceOfAnElementWithoutAMethod = saysNothing
          ? null
          : consequenceOfAnElementWithoutAMethod.strip();
      return this;

    }

    /**
     * Allows a parameter kind which VanillaBP binds itself. Everything not allowed is
     * refused at startup. For example, an extension whose event has no process variables
     * leaves {@link CoreHandlerParameter#TASK_PARAM} out. Then the developer reads that,
     * and does not get <code>null</code>.
     *
     * @param parameters The kinds to allow
     * @return This builder
     */
    public Builder coreParameters(
        final CoreHandlerParameter... parameters) {

      java.util.Collections.addAll(coreParameters, parameters);
      return this;

    }

    /**
     * Adds a binder for the parameters of the extension's own SPI. Binders are asked
     * in the order they were added, before VanillaBP tries its own.
     *
     * @param binder The binder
     * @return This builder
     */
    public Builder parameterBinder(
        final HandlerParameterBinder binder) {

      parameterBinders.add(binder);
      return this;

    }

    /**
     * What the extension checks about one use of its annotation. It runs while the scan
     * holds the method which carries the annotation, once per use. So a repeatable
     * annotation is checked as often as it is written. If a check refuses by throwing,
     * the application does not start. The message names the annotation, the class, the
     * method and the extension, and then what the check said.
     * <p>
     * Here an attribute is checked which the {@link HandlerContract} cannot describe.
     * Without it, an extension has to walk through the classes of the application again
     * to find the method, and that walk reads other methods than the scan does.
     *
     * @param annotationCheck The check
     * @return This builder
     */
    public Builder validatingAnnotation(
        final HandlerAnnotationCheck annotationCheck) {

      this.annotationCheck = annotationCheck;
      return this;

    }

    /**
     * Declares that what a method returns is handed back to the caller of
     * {@link ExtensionHandlers#invoke(HandlerCall)}. Without this, a method has to be
     * <code>void</code>, like a <code>&#64;WorkflowTask</code> method.
     *
     * @return This builder
     */
    public Builder deliversReturnValue() {

      this.deliversReturnValue = true;
      return this;

    }

    /**
     * Declares that no method of this contract ever writes the workflow aggregate. So
     * VanillaBP never saves it after one of them ran, whatever a single call asks for.
     * <p>
     * A call says the same with
     * {@link HandlerCall.Builder#withoutSavingTheWorkflowAggregate()}. Here it is said once,
     * for all methods with the annotation. The difference is who can read it. At the call
     * only the caller knows. This is known while the methods are wired. There VanillaBP
     * warns about a handler which may write an aggregate where nothing would notice a
     * second writer (see decision 50 in the repository's DECISIONS.md). Such a contract
     * gets no warning, because there is nothing to warn about.
     * <p>
     * Say it only if it holds for every method of the annotation. An extension with
     * reading providers and writing notifications describes them as two contracts. That
     * is the more precise way anyway.
     *
     * @return This builder
     */
    public Builder neverSavesTheWorkflowAggregate() {

      this.savesWorkflowAggregate = false;
      return this;

    }

    /**
     * Builds the contract, and refuses one which cannot name the extension or the
     * annotation it is about.
     *
     * @return The contract
     * @throws IllegalArgumentException If the extension id is missing or blank, if no
     *           annotation was named, or if the contract asks for a method for the deployed
     *           version, but its calls name no version (guiding message)
     */
    public HandlerContract build() {

      if ((extensionId == null) || extensionId.isBlank()) {
        throw new IllegalArgumentException(
            "A handler contract needs the id of the extension owning it - it names the extension in "
                + "every message about one of its methods!");
      }
      if (annotationType == null) {
        throw new IllegalArgumentException(
            """
                The handler contract of extension '%s' names no annotation! Pass the annotation your \
                extension's methods carry to HandlerContract.of(extensionId, annotationType)."""
                .formatted(extensionId));
      }
      if (deployedVersionNeedsAMethod && !callsCarryTheProcessVersion) {
        throw new IllegalArgumentException(
            """
                The handler contract of extension '%s' for '@%s' says theDeployedVersionNeedsAMethod() \
                but not callsCarryTheProcessVersion()! A method is held against the deployed version \
                only where the calls name a version, because a call without one never picks a method \
                by version. Add callsCarryTheProcessVersion() if your calls name the version, or drop \
                theDeployedVersionNeedsAMethod()."""
                .formatted(extensionId, annotationType.getSimpleName()));
      }
      return new HandlerContract(this);

    }

  }

}
