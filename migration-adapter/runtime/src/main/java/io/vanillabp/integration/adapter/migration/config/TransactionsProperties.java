package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of what VanillaBP does when the store of a workflow aggregate is not
 * covered by the transaction it opens (properties section
 * <code>vanillabp.transactions</code>, overridable per workflow module as
 * <code>vanillabp.workflow-modules.&lt;id&gt;.transactions</code>).
 * <p>
 * The only setting is whether unguarded writes are accepted. VanillaBP refuses to boot
 * where a platform can name both the defect and its fix - a MongoDB-managed aggregate in
 * an application whose only transaction manager is a JPA one is the case this exists for.
 * An application which knowingly wants that behaviour states it here, and the message
 * stays as a WARN so the decision remains visible in the log.
 */
@Getter
@Setter
@SuperBuilder
public class TransactionsProperties {

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, which is how a field given a default one day keeps
   * it: Lombok moves the initializer of such a field into the builder, so a constructor
   * which sets nothing itself would leave the field <code>null</code>.
   */
  public TransactionsProperties() {

    this(builder());

  }

  /**
   * What VanillaBP does about a workflow aggregate whose store is demonstrably not
   * covered by the transaction it opens.
   */
  public enum UnguardedAggregateWrites {

    /**
     * The boot ends with a guiding message naming the fix. The default.
     */
    REJECTED,

    /**
     * The application accepts writes which do not commit or roll back together. The
     * message is logged as a WARN instead of ending the boot.
     */
    ACCEPTED

  }

  /**
   * Whether writes to a store outside VanillaBP's transaction are accepted.
   * <code>null</code> in a workflow module's section means "whatever is configured
   * globally"; the default of the global section is
   * {@link UnguardedAggregateWrites#REJECTED}.
   */
  private UnguardedAggregateWrites unguardedAggregateWrites;

}
