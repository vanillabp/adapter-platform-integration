package io.vanillabp.integration.test;

/**
 * The model most tests of this module deploy without caring about it:
 * {@code test-module/processes/dummy/DummyProcess.bpmn}. The BPMS double names its process after
 * the file, {@code DummyProcess}, and in most of these tests no workflow service claims it. A
 * process nobody claims ends the start unless the application says that it belongs to somebody
 * else, so a test which only needs the file to be there says that.
 */
public final class ThePlaceholderModel {

  /**
   * The line which marks the placeholder process as somebody else's.
   */
  public static final String BELONGS_TO_SOMEBODY_ELSE = "vanillabp.workflow-modules.test-module.workflows.DummyProcess.implemented-externally=true";

  private ThePlaceholderModel() {
  }

}
