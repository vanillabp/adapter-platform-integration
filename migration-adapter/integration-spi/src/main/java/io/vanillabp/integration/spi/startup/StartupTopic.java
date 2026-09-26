package io.vanillabp.integration.spi.startup;

/**
 * What a startup finding is about, which is the same as where its fix lies.
 * <p>
 * Almost every message VanillaBP writes while an application starts ends with an
 * instruction, and that instruction names one place: a property key, a Java class, a BPMN
 * model, a table, a dependency. So the topics are those places, and the order below is the
 * order a developer walks them in - what is on the class path first, then what the
 * configuration says about it, then the code, then the models, then what the BPMS already
 * holds from earlier deployments, then what the database still holds, then the machines
 * underneath.
 * <p>
 * Grouping by severity was the obvious alternative and says almost nothing: a box exists
 * only on a start which survived, and nearly everything such a start can write is a
 * warning. Grouping by adapter or by the phase of the boot names something true about
 * VanillaBP rather than about the reader.
 * <p>
 * See decision &lt;pending: 579&gt; in the repository's DECISIONS.md.
 */
public enum StartupTopic {

  /** What VanillaBP artifacts are on the class path and whether they belong together. */
  PARTS_AND_VERSIONS("PARTS AND VERSIONS"),

  /** Anything whose fix is a line in a configuration file. The biggest group by far. */
  CONFIGURATION("CONFIGURATION"),

  /**
   * Workflow services, <code>&#64;WorkflowTask</code> methods, workflow aggregates, the
   * values shared with the BPMS, the transaction annotations.
   */
  CODE("CODE"),

  /** What a modeller has to change, and what this start deployed and what it did not. */
  BPMN_MODELS("BPMN MODELS"),

  /** Versions a BPMS still holds, tenants, workflows running on a version nobody serves. */
  DEPLOYED_VERSIONS("DEPLOYED VERSIONS"),

  /** Outbox entries, delivery records, adapter ids only the database still knows. */
  STORED_STATE("STORED STATE"),

  /** Tables, a MongoDB which is no replica set, a cache cluster of one node. */
  INFRASTRUCTURE("INFRASTRUCTURE");

  private final String heading;

  StartupTopic(
      final String heading) {

    this.heading = heading;

  }

  /**
   * The words this topic carries in the box, upper case because they are the one thing a
   * reader scans for.
   *
   * @return The heading
   */
  public String heading() {

    return heading;

  }

}
