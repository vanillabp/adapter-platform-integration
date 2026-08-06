package io.vanillabp.migration.test.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;

/**
 * The sync model (story 28): which attributes of a workflow aggregate are shared
 * with the BPMS. The rule under test is the inheritance chain - adapter default,
 * aggregate class, attribute, nested type - where every level only overrides what
 * it explicitly says.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateSyncSupportTest {

  private final AggregateSyncSupport testee = new AggregateSyncSupport();

  private Map<String, Object> full(
      final Object aggregate) {

    return testee.syncedValues(aggregate, AggregateSyncMode.FULL);

  }

  private Map<String, Object> none(
      final Object aggregate) {

    return testee.syncedValues(aggregate, AggregateSyncMode.NONE);

  }

  enum ItemSize {
    NORMAL,
    BIG
  }

  public static class PlainAggregate {

    private String content = "hello";

    private ItemSize size = ItemSize.BIG;

    @NoSyncWithBPMS
    private String creditCardNumber = "4711";

    public String getContent() {
      return content;
    }

    public ItemSize getSize() {
      return size;
    }

    public String getCreditCardNumber() {
      return creditCardNumber;
    }

    public boolean isShippedAsBigItem() {
      return size == ItemSize.BIG;
    }

  }

  @Test
  @DisplayName("The adapter's default decides for an aggregate carrying no annotation")
  public void theAdapterDefaultDecides() {

    final var aggregate = new PlainAggregate();

    // a remote BPMS shares everything - except what is excluded explicitly
    final var shared = full(aggregate);
    assertEquals("hello", shared.get("content"));
    assertEquals("BIG", shared.get("size"), "enums are shared by name");
    assertEquals(Boolean.TRUE, shared.get("shippedAsBigItem"), "getters are attributes like any other");
    assertFalse(shared.containsKey("creditCardNumber"), "@NoSyncWithBPMS excludes an attribute");

    // an embedded BPMS reads the aggregate live and shares nothing by default
    assertEquals(Map.of(), none(aggregate));

  }

  @NoSyncWithBPMS
  public static class OptInAggregate {

    private ItemSize size = ItemSize.NORMAL;

    private String secret = "s3cr3t";

    public ItemSize getSize() {
      return size;
    }

    public String getSecret() {
      return secret;
    }

    @SyncWithBPMS
    public boolean isShippedAsNormalItem() {
      return size == ItemSize.NORMAL;
    }

  }

  @Test
  @DisplayName("A class annotation overrides the adapter's default, attributes override the class")
  public void theClassAnnotationOverridesTheAdapterDefault() {

    // even on a remote BPMS (default FULL) the class' @NoSyncWithBPMS wins ...
    final var shared = full(new OptInAggregate());

    assertEquals(Map.of("shippedAsNormalItem", Boolean.TRUE), shared);

    // ... and the same holds the other way round on an embedded BPMS
    assertEquals(Map.of("shippedAsNormalItem", Boolean.TRUE), none(new OptInAggregate()));

  }

  public static class Item {

    private long itemId;

    private ItemSize size;

    Item(
        final long itemId,
        final ItemSize size) {
      this.itemId = itemId;
      this.size = size;
    }

    public long getItemId() {
      return itemId;
    }

    public ItemSize getSize() {
      return size;
    }

  }

  @NoSyncWithBPMS
  public static class NarrowedItem {

    private long itemId;

    private String internalNote = "not for the BPMS";

    NarrowedItem(
        final long itemId) {
      this.itemId = itemId;
    }

    @SyncWithBPMS
    public long getItemId() {
      return itemId;
    }

    public String getInternalNote() {
      return internalNote;
    }

  }

  @NoSyncWithBPMS
  public static class NestedAggregate {

    private Set<Item> inheritedItems = Set.of(new Item(1, ItemSize.NORMAL));

    private List<NarrowedItem> narrowedItems = List.of(new NarrowedItem(2));

    private Item hidden = new Item(3, ItemSize.BIG);

    @SyncWithBPMS
    public List<Item> getInheritedItems() {
      return List.copyOf(inheritedItems);
    }

    @SyncWithBPMS
    public List<NarrowedItem> getNarrowedItems() {
      return narrowedItems;
    }

    public Item getHidden() {
      return hidden;
    }

  }

  @Test
  @DisplayName("Nested objects inherit the behavior of the attribute holding them - unless their type says otherwise")
  public void nestedObjectsInheritFromTheirAttribute() {

    final var shared = full(new NestedAggregate());

    // the aggregate opted out, so only the two annotated attributes are shared
    assertEquals(Set.of("inheritedItems", "narrowedItems"), shared.keySet());

    // a DTO without an annotation of its own behaves like the attribute holding
    // it: shared -> ALL of its attributes are shared
    @SuppressWarnings("unchecked")
    final var inherited = (List<Map<String, Object>>) shared.get("inheritedItems");
    assertEquals(1, inherited.size());
    assertEquals(Map.of("itemId", 1L, "size", "NORMAL"), inherited.getFirst());

    // a DTO with an annotation of its own narrows what it exposes wherever it is used
    @SuppressWarnings("unchecked")
    final var narrowed = (List<Map<String, Object>>) shared.get("narrowedItems");
    assertEquals(Map.of("itemId", 2L), narrowed.getFirst());

  }

  public static class ValueTypesAggregate {

    public String getText() {
      return "text";
    }

    public int getNumber() {
      return 42;
    }

    public java.time.LocalDate getDay() {
      return java.time.LocalDate.parse("2026-08-06");
    }

    public java.util.UUID getReference() {
      return java.util.UUID.fromString("00000000-0000-0000-0000-000000004711");
    }

    public String[] getTags() {
      return new String[]{
          "a", "b"
      };
    }

    public Map<String, ItemSize> getSizes() {
      return Map.of("first", ItemSize.BIG);
    }

    public String getNothing() {
      return null;
    }

  }

  @Test
  @DisplayName("Values are converted to plain JDK types every BPMS understands")
  public void valuesAreConvertedToPlainTypes() {

    final var shared = full(new ValueTypesAggregate());

    assertEquals("text", shared.get("text"));
    assertEquals(42, shared.get("number"));
    assertEquals("2026-08-06", shared.get("day"), "temporal values become their string form");
    assertEquals("00000000-0000-0000-0000-000000004711", shared.get("reference"));
    assertEquals(List.of("a", "b"), shared.get("tags"), "arrays become lists");
    assertEquals(Map.of("first", "BIG"), shared.get("sizes"));
    assertTrue(shared.containsKey("nothing"));
    assertEquals(null, shared.get("nothing"), "a null value is shared as null");

  }

  public static class CyclicAggregate {

    private CyclicAggregate self;

    public CyclicAggregate getSelf() {
      if (self == null) {
        self = this;
      }
      return self;
    }

  }

  @Test
  @DisplayName("A cyclic object graph is stopped instead of looping forever")
  public void cyclicGraphsAreStopped() {

    final var shared = full(new CyclicAggregate());

    var current = shared;
    for (var depth = 1; depth < AggregateSyncSupport.MAX_DEPTH; ++depth) {
      final var next = current.get("self");
      if (!(next instanceof Map)) {
        assertTrue(next instanceof String, "the cycle is cut by sharing a string representation");
        return;
      }
      @SuppressWarnings("unchecked")
      final var nested = (Map<String, Object>) next;
      current = nested;
    }
    assertTrue(
        current.get("self") instanceof String,
        () -> "the cycle has to be cut at depth "
            + AggregateSyncSupport.MAX_DEPTH);

  }

  @SyncWithBPMS
  @NoSyncWithBPMS
  public static class ContradictingAggregate {

    public String getContent() {
      return "?";
    }

  }

  @Test
  @DisplayName("Annotating both ways is a defect reported with a guiding message")
  public void contradictingAnnotationsAreReported() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> full(new ContradictingAggregate()));

    assertTrue(exception.getMessage().contains("@SyncWithBPMS"), exception::getMessage);
    assertTrue(exception.getMessage().contains("@NoSyncWithBPMS"), exception::getMessage);

  }

  @Test
  @DisplayName("A null aggregate shares nothing")
  public void nullAggregateSharesNothing() {

    assertEquals(Map.of(), full(null));

  }

}
