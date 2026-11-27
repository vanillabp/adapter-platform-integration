package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.outbox.PhaseTwoOutboxTransaction;
import io.vanillabp.integration.extension.spi.resolver.PhaseTwoOutboxResolver;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A store says whether it replaces a waiting entry, and the resolver turns a store which
 * cannot into the message an extension refuses its start with. The starts themselves are
 * held by <code>AnExtensionRefusesAStoreWhichCannotReplaceTest</code> on both platforms.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheStoresSayWhetherTheyCanReplaceTest {

  /**
   * The first words of the refusal. The case in which every store replaces checks that it
   * gets no message at all.
   */
  private static final String REFUSAL = "plans phase-two calls which replace the entry still waiting";

  /**
   * A store of an application which was written before replacing existed.
   */
  private static final class AStoreWhichCannotReplace implements PhaseTwoOutbox {

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      return true;

    }

  }

  private static PhaseTwoOutboxResolver aResolverHolding(
      final PhaseTwoOutbox... stores) {

    return new PhaseTwoOutboxResolver() {

      @Override
      public PhaseTwoOutbox resolveFor(
          final Class<?> workflowAggregateClass) {

        return stores[0];

      }

      @Override
      public Collection<PhaseTwoOutbox> allStores() {

        return List.of(stores);

      }

      @Override
      public String remediesDescription() {

        return "";

      }

    };

  }

  private static JdbcPhaseTwoOutboxStore theJdbcStore() {

    final var connections = (io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess) () -> DriverManager
        .getConnection("jdbc:h2:mem:stores-say-whether-they-replace;DB_CLOSE_DELAY=-1", "sa", "");
    final var transaction = new PhaseTwoOutboxTransaction() {

      @Override
      public boolean isActive() {

        return false;

      }

      @Override
      public void afterCommit(
          final Runnable action) {
        // nothing is scheduled here
      }

    };
    return new JdbcPhaseTwoOutboxStore(
        connections, transaction, "OUTBOX_SAYS_IT_REPLACES", new JdbcPhaseTwoPayloadStore(
            connections, "OUTBOX_SAYS_IT_REPLACES_PAYLOAD", JdbcPhaseTwoOutboxStore
                .entriesNamingTheirPayload("OUTBOX_SAYS_IT_REPLACES")), () -> {
                });

  }

  @Test
  @DisplayName("A store which only implements schedule says that it cannot replace")
  public void theDefaultCannotReplace() {

    assertFalse(new AStoreWhichCannotReplace().canReplaceWhatIsStillWaiting());

  }

  @Test
  @DisplayName("The JDBC store of the core says that it replaces")
  public void theJdbcStoreReplaces() {

    assertTrue(theJdbcStore().canReplaceWhatIsStillWaiting());

  }

  @Test
  @DisplayName("A store which cannot replace is named, together with what to implement")
  public void aStoreWhichCannotReplaceIsNamed() {

    final var why = aResolverHolding(theJdbcStore(), new AStoreWhichCannotReplace())
        .whyTheStoresCannotReplace("cockpit")
        .orElseThrow();

    assertTrue(why.contains(REFUSAL), why);
    assertTrue(why.contains("Extension 'cockpit'"), why);
    assertTrue(why.contains(AStoreWhichCannotReplace.class.getName()), why);
    assertFalse(
        why.contains(JdbcPhaseTwoOutboxStore.class.getName()), "a store which replaces is not part of the problem: "
            + why);
    assertTrue(why.contains("PhaseTwoOutbox#scheduleReplacingWhatIsStillWaiting"), why);
    assertTrue(why.contains("PhaseTwoOutbox#canReplaceWhatIsStillWaiting answer true"), why);
    assertTrue(why.contains("use one of the stores VanillaBP ships"), why);

  }

  @Test
  @DisplayName("Where every store replaces, there is nothing to refuse")
  public void everyStoreReplaces() {

    assertTrue(aResolverHolding(theJdbcStore()).whyTheStoresCannotReplace("cockpit").isEmpty());

  }

}
