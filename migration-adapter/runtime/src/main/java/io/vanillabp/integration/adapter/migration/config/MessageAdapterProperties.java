package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

/**
 * What an application says about ONE message which a workflow waits for (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.messages.&lt;message&gt;.*</code>,
 * where the key is the message name as the BPMN model writes it).
 * <p>
 * It is a map of its own next to the tasks, and not a task. So a value meant for a message
 * never applies to a task of the same name. Only one key is read here,
 * <code>message-time-to-live</code> (see
 * {@link MigrationAdapterProperties#messageTimeToLive(String, String, String)}). It is the same
 * for every adapter, so it carries no adapter ID.
 */
public class MessageAdapterProperties {

  /**
   * The empty section from which a configuration binder starts, one per configured message.
   */
  public MessageAdapterProperties() {

  }

  /**
   * How long a correlation of this message waits for a workflow which does not wait for it
   * yet. It is the most specific of the four levels. <code>null</code> means "this message
   * says nothing".
   */
  private java.time.Duration messageTimeToLive;

  /**
   * Refused here on purpose. The time-to-live of a message means the same on every BPMS, so
   * it is written without an adapter ID. This section is bound, so that a line written below
   * <code>...messages.&lt;message&gt;.adapters.&lt;id&gt;</code> gets a message which says
   * where it belongs, instead of being ignored. The keys are the adapter IDs.
   */
  private Map<String, MessageSettings> adapters = Map.of();

  /**
   * How long a correlation of this message waits for a workflow which does not wait for it
   * yet.
   *
   * @return The time-to-live, <code>null</code> where this message says nothing
   */
  public java.time.Duration getMessageTimeToLive() {

    return messageTimeToLive;

  }

  /**
   * How long a correlation of this message waits for a workflow which does not wait for it
   * yet.
   *
   * @param messageTimeToLive The time-to-live, <code>null</code> where this message says
   *          nothing
   */
  public void setMessageTimeToLive(
      final java.time.Duration messageTimeToLive) {

    this.messageTimeToLive = messageTimeToLive;

  }

  /**
   * Refused here on purpose, see {@link #adapters}.
   *
   * @return The sections keyed by adapter ID, never <code>null</code>
   */
  public Map<String, MessageSettings> getAdapters() {

    return adapters;

  }

  /**
   * Refused here on purpose, see {@link #adapters}.
   *
   * @param adapters The sections keyed by adapter ID
   */
  public void setAdapters(
      final Map<String, MessageSettings> adapters) {

    this.adapters = adapters;

  }

  /**
   * What an application wrote for ONE adapter about one message (properties section
   * <code>...messages.&lt;message&gt;.adapters.&lt;id&gt;.*</code>). Nothing here is read. A
   * time-to-live written here ends the startup with a message naming
   * <code>...messages.&lt;message&gt;.message-time-to-live</code>.
   */
  public static class MessageSettings {

    /**
     * The empty section from which a configuration binder starts.
     */
    public MessageSettings() {

    }

    /**
     * Refused here on purpose: the time-to-live of a message carries no adapter ID.
     * <code>null</code> means "nothing was written here".
     */
    private java.time.Duration messageTimeToLive;

    /**
     * How long a correlation of this message waits for a workflow which does not wait for it
     * yet.
     *
     * @return The time-to-live, <code>null</code> where this message says nothing
     */
    public java.time.Duration getMessageTimeToLive() {

      return messageTimeToLive;

    }

    /**
     * How long a correlation of this message waits for a workflow which does not wait for it
     * yet.
     *
     * @param messageTimeToLive The time-to-live, <code>null</code> where this message says
     *          nothing
     */
    public void setMessageTimeToLive(
        final java.time.Duration messageTimeToLive) {

      this.messageTimeToLive = messageTimeToLive;

    }

  }

}
