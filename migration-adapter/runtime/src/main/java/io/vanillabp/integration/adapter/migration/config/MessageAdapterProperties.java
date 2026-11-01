package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

/**
 * What an application says about ONE message which a workflow waits for (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.messages.&lt;message&gt;.*</code>,
 * where the key is the message name as the BPMN model writes it).
 * <p>
 * It is a map of its own next to the tasks, and not a task. So a value meant for a message
 * never applies to a task of the same name. Only one key is read here, the
 * <code>message-time-to-live</code> of each adapter (see
 * {@link MigrationAdapterProperties#messageTimeToLive}).
 */
public class MessageAdapterProperties {

  /**
   * The empty section from which a configuration binder starts, one per configured message.
   */
  public MessageAdapterProperties() {

  }

  /**
   * What each adapter is told about this message. The keys are the adapter IDs.
   */
  private Map<String, MessageSettings> adapters = Map.of();

  /**
   * What each adapter is told about this message.
   *
   * @return The sections keyed by adapter ID, never <code>null</code>
   */
  public Map<String, MessageSettings> getAdapters() {

    return adapters;

  }

  /**
   * What each adapter is told about this message.
   *
   * @param adapters The sections keyed by adapter ID
   */
  public void setAdapters(
      final Map<String, MessageSettings> adapters) {

    this.adapters = adapters;

  }

  /**
   * What ONE adapter is told about one message (properties section
   * <code>...messages.&lt;message&gt;.adapters.&lt;id&gt;.*</code>).
   */
  public static class MessageSettings {

    /**
     * The empty section from which a configuration binder starts.
     */
    public MessageSettings() {

    }

    /**
     * How long a correlation of this message waits for a workflow which does not wait for it
     * yet. <code>null</code> means "this message says nothing".
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
