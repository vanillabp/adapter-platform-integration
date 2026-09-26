package io.vanillabp.integration.spi.startup;

/**
 * Where a check says what it found while an application starts, instead of writing a line
 * of its own.
 * <p>
 * The platform collects what is reported here and says it once, at the end of the start,
 * as one block grouped by {@link StartupTopic}. An adapter reaches that block through this
 * interface: the collection itself belongs to the core, which decides when a start is over
 * and what happens to a reason not to start, and an adapter has no business in either.
 *
 * <h2>How an adapter gets one</h2>
 *
 * Both platform integrations publish one instance per application, so an adapter asks for a
 * bean of this type the way it asks for any other: a constructor parameter on Spring Boot,
 * an injection point or a producer parameter on Quarkus. An adapter which is built without
 * a container, in a test, builds a collection of its own and reads it back.
 *
 * <h2>What belongs here</h2>
 *
 * A finding, which is something the developer of the application has to know about their
 * application. What the adapter set up, how many workers it started, which BPMS it reached:
 * those are reports about the adapter doing its work and they stay where they are.
 * <p>
 * A finding is reported UNFORMATTED, with its scope beside its text. The same finding
 * arrives once per workflow module, per BPMN process and per adapter id, and the block
 * folds two findings of one topic carrying the same text into one entry naming both scopes.
 * Writing the scope into the sentence takes that away.
 *
 * <h2>When it is too late</h2>
 *
 * The block is written once, at the end of the start. A finding reported after that goes
 * into the log where it was found, because there is no block left to fold it into, and a
 * reason not to start is written rather than thrown: the start it was meant to stop is
 * over.
 */
public interface StartupReport {

  /**
   * Notes something the start survives and which still asks the developer for something.
   *
   * @param topic Where its fix lies
   * @param scope What it is about - a workflow module, a BPMN process, an adapter id, a
   *          property key - written as the developer knows it and never folded into the
   *          text
   * @param message The whole message, ending with what to do
   */
  void notice(
      StartupTopic topic,
      String scope,
      String message);

  /**
   * Notes something which is wrong and which the start survives.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  void warn(
      StartupTopic topic,
      String scope,
      String message);

  /**
   * Notes something which is wrong enough to be written at error level and which the start
   * still survives.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  void error(
      StartupTopic topic,
      String scope,
      String message);

  /**
   * Notes a reason the start cannot go on with. The platform throws it at the end of the
   * start, together with every other reason found until then, so a developer who put two
   * things wrong learns both in one start.
   * <p>
   * A check which cannot let the start walk on - because the next question depends on the
   * one it just proved unanswerable - throws where it stands instead and says so in its
   * javadoc.
   *
   * @param topic Where its fix lies
   * @param scope What it is about
   * @param message The whole message, ending with what to do
   */
  void refuse(
      StartupTopic topic,
      String scope,
      String message);

}
