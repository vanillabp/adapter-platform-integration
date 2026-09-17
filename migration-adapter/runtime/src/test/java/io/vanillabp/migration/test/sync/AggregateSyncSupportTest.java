package io.vanillabp.migration.test.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict.Kind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import lombok.Getter;

/**
 * The sync model: which attributes of a workflow aggregate are shared
 * with the BPMS. The rule under test is the inheritance chain - adapter default,
 * aggregate class, attribute, nested type - where every level only overrides what
 * it explicitly says.
 * <p>
 * The CLASS level is DERIVED where the application annotated only
 * attributes: the adapter's default applies as long as an aggregate carries no
 * annotation at all, the first annotation hands control to the application (the
 * class mode is then the opposite of what its attributes state) and mixing both
 * annotations without a class annotation is an ambiguity reported at startup.
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

  @Getter
  public static class PlainAggregate {

    private String content = "hello";

    private ItemSize size = ItemSize.BIG;

    public boolean isShippedAsBigItem() {
      return size == ItemSize.BIG;
    }

  }

  @Test
  @DisplayName("The adapter's default decides for an aggregate carrying no annotation at all")
  public void theAdapterDefaultDecides() {

    final var aggregate = new PlainAggregate();

    // a remote BPMS shares everything
    final var shared = full(aggregate);
    assertEquals("hello", shared.get("content"));
    assertEquals("BIG", shared.get("size"), "enums are shared by name");
    assertEquals(Boolean.TRUE, shared.get("shippedAsBigItem"), "getters are attributes like any other");

    // an embedded BPMS reads the aggregate live and shares nothing by default
    assertEquals(Map.of(), none(aggregate));

  }

  @Getter
  public static class OptOutByAttributeAggregate {

    private String content = "hello";

    @NoSyncWithBPMS
    private String creditCardNumber = "4711";

  }

  @Test
  @DisplayName("One @NoSyncWithBPMS attribute makes the CLASS opt-out - the adapter's default no longer applies")
  public void oneExcludedAttributeDerivesOptOut() {

    // The first annotation hands control to the application. Naming
    // what is NOT shared means everything else IS - even on an adapter defaulting
    // to NONE, where the aggregate would otherwise have shared NOTHING.
    final var expected = Map.<String, Object>of("content", "hello");
    assertEquals(expected, full(new OptOutByAttributeAggregate()));
    assertEquals(expected, none(new OptOutByAttributeAggregate()));

  }

  @Getter
  public static class OptInByAttributeAggregate {

    private ItemSize size = ItemSize.NORMAL;

    private String secret = "s3cr3t";

    @SyncWithBPMS
    public boolean isShippedAsNormalItem() {
      return size == ItemSize.NORMAL;
    }

  }

  @Test
  @DisplayName("One @SyncWithBPMS attribute makes the CLASS opt-in - nothing else is shared")
  public void oneSharedAttributeDerivesOptIn() {

    // naming what IS shared means the rest is not - a remote BPMS (default FULL)
    // would otherwise have received 'secret', too
    final var expected = Map.<String, Object>of("shippedAsNormalItem", Boolean.TRUE);
    assertEquals(expected, full(new OptInByAttributeAggregate()));
    assertEquals(expected, none(new OptInByAttributeAggregate()));

  }

  @NoSyncWithBPMS
  @Getter
  public static class OptInAggregate {

    private ItemSize size = ItemSize.NORMAL;

    private String secret = "s3cr3t";

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

  @Getter
  public static class Item {

    private long itemId;

    private ItemSize size;

    Item(
        final long itemId,
        final ItemSize size) {
      this.itemId = itemId;
      this.size = size;
    }

  }

  @NoSyncWithBPMS
  @Getter
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

  }

  @NoSyncWithBPMS
  @Getter
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

  @Getter
  public static class DerivingItem {

    private long itemId = 7;

    private String internalNote = "not for the BPMS";

    @SyncWithBPMS
    public long getItemId() {
      return itemId;
    }

  }

  @NoSyncWithBPMS
  public static class DerivingNestedAggregate {

    @SyncWithBPMS
    public DerivingItem getItem() {
      return new DerivingItem();
    }

  }

  @Test
  @DisplayName("A nested type derives its own mode from its attributes, too")
  public void nestedTypesDeriveTheirOwnMode() {

    final var shared = full(new DerivingNestedAggregate());

    // the attribute is shared, but the DTO's own (derived) opt-in narrows what it
    // exposes - without deriving it would have inherited "share everything"
    assertEquals(Map.of("item", Map.of("itemId", 7L)), shared);

  }

  @Getter
  public static class AmbiguousAggregate {

    @SyncWithBPMS
    private String customerName = "ACME";

    @NoSyncWithBPMS
    private String creditCardNumber = "4711";

    private String status = "new";

  }

  @Test
  @DisplayName("Mixing both annotations on attributes without a class annotation is ambiguous")
  public void mixedAttributeAnnotationsAreAmbiguous() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> new AggregateSyncSupport().validateSyncModel(AmbiguousAggregate.class));

    final var message = exception.getMessage();
    assertTrue(message.contains(AmbiguousAggregate.class.getName()), () -> message);
    assertTrue(message.contains("'customerName'"), () -> message);
    assertTrue(message.contains("'creditCardNumber'"), () -> message);
    assertTrue(message.contains("@NoSyncWithBPMS shares ONLY"), () -> message);
    assertTrue(message.contains("@SyncWithBPMS shares EVERYTHING EXCEPT"), () -> message);

    // the very same message reaches a developer whose type is only reachable at
    // runtime (the startup walk cannot see it) - the model itself refuses to guess
    assertTrue(
        assertThrowsExactly(IllegalStateException.class, () -> full(new AmbiguousAggregate()))
            .getMessage()
            .contains("does not state its own mode"));

  }

  public static class HoldingAmbiguousItems {

    public List<AmbiguousAggregate> getItems() {
      return List.of();
    }

  }

  @Test
  @DisplayName("The startup validation walks the attribute graph incl. generic element types")
  public void validationWalksNestedTypes() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> new AggregateSyncSupport().validateSyncModel(HoldingAmbiguousItems.class));

    assertTrue(exception.getMessage().contains(AmbiguousAggregate.class.getName()), exception::getMessage);

  }

  @Test
  @DisplayName("A valid model - and no model at all - passes the startup validation")
  public void validModelsPassTheValidation() {

    final var testee = new AggregateSyncSupport();
    testee.validateSyncModel(PlainAggregate.class);
    testee.validateSyncModel(OptInByAttributeAggregate.class);
    testee.validateSyncModel(NestedAggregate.class);
    testee.validateSyncModel(CyclicAggregate.class);
    testee.validateSyncModel(ValueTypesAggregate.class);
    testee.validateSyncModel(null);

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

  @Getter
  public static class BidirectionalOrder {

    private final List<BidirectionalItem> items = new java.util.LinkedList<>();

    public BidirectionalOrder(
        final int itemCount) {
      for (var index = 0; index < itemCount; ++index) {
        items.add(new BidirectionalItem(this, index));
      }
    }

  }

  @Getter
  public static class BidirectionalItem {

    private final BidirectionalOrder order;

    private final int position;

    BidirectionalItem(
        final BidirectionalOrder order,
        final int position) {
      this.order = order;
      this.position = position;
    }

    /**
     * The back reference every ordinary JPA entity has.
     */

  }

  @Test
  @DisplayName("A bidirectional relation (the normal entity case) is cut at the back reference")
  public void bidirectionalRelationsAreCutAtTheBackReference() {

    final var shared = full(new BidirectionalOrder(3));

    @SuppressWarnings("unchecked")
    final var items = (List<Map<String, Object>>) shared.get("items");
    assertEquals(3, items.size());
    for (final var item : items) {
      assertEquals(Set.of("order", "position"), item.keySet());
      // the back reference is NOT followed: it would repeat the whole order (and
      // with it all of its items) once per nesting level
      assertTrue(
          item.get("order") instanceof String reference && reference
              .startsWith(BidirectionalOrder.class.getName()),
          () -> "expected the cycle to be cut but got: "
              + item.get("order"));
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

  public interface PathAddress {

    String getCity();

  }

  public static class PathCustomer {

    /**
     * A field without a getter: VanillaBP 1 resolved it, the sync model never shares it,
     * and a path reading it below the first segment has to be reported the same way.
     */
    private String nickname;

    public String getName() {
      return "Ada";
    }

  }

  public static class PathItem {

    public java.math.BigDecimal getPrice() {
      return java.math.BigDecimal.ONE;
    }

  }

  public static class PathOrder {

    public PathCustomer getCustomer() {
      return new PathCustomer();
    }

    public java.time.LocalDate getDueDate() {
      return java.time.LocalDate.parse("2027-03-04");
    }

    public ItemSize getSize() {
      return ItemSize.BIG;
    }

    public List<PathItem> getItems() {
      return List.of(new PathItem());
    }

    public Map<String, String> getLabels() {
      return Map.of("kind", "express");
    }

    public PathAddress getAddress() {
      return () -> "Vienna";
    }

    public List<?> getAttachments() {
      return List.of();
    }

    @NoSyncWithBPMS
    public String getInternalCode() {
      return "IC-9";
    }

  }

  public static class PathAggregate {

    public PathOrder getOrder() {
      return new PathOrder();
    }

    /**
     * A decimal at the TOP level: shared as itself, and an adapter whose BPMS cannot
     * store it unchanged has to be able to find out that it is one.
     */
    public java.math.BigDecimal getTotal() {
      return new java.math.BigDecimal("120.50");
    }

    @NoSyncWithBPMS
    public PathOrder getHiddenOrder() {
      return new PathOrder();
    }

  }

  private PathVerdict find(
      final Class<?> aggregateClass,
      final String path) {

    return testee
        .whatAPathFinds(aggregateClass, List.of(path.split("\\.")), AggregateSyncMode.FULL);

  }

  @Test
  @DisplayName("A path reaching shared attributes all the way down finds a value")
  public void aWholeSharedPathFindsAValue() {

    assertEquals(Kind.SHARED_VALUE, find(PathAggregate.class, "order").kind());
    assertEquals(Kind.SHARED_VALUE, find(PathAggregate.class, "order.customer.name").kind());
    // navigating into a collection reads an element, so its element type continues
    assertEquals(Kind.SHARED_VALUE, find(PathAggregate.class, "order.items.price").kind());

  }

  @Test
  @DisplayName("An unshared segment is named wherever in the path it sits")
  public void anUnsharedSegmentIsNamed() {

    final var top = find(PathAggregate.class, "hiddenOrder.customer.name");
    assertEquals(Kind.NOT_SHARED, top.kind());
    assertEquals("hiddenOrder", top.segment());
    assertEquals(0, top.segmentIndex());
    assertEquals("PathAggregate", top.segmentOwner());

    final var nested = find(PathAggregate.class, "order.internalCode");
    assertEquals(Kind.NOT_SHARED, nested.kind());
    assertEquals("internalCode", nested.segment());
    assertEquals(1, nested.segmentIndex());
    assertEquals("PathOrder", nested.segmentOwner());

    // the migration case two levels down: a field without a getter is an attribute
    // version 1 read and this version can never share
    final var withoutAGetter = find(PathAggregate.class, "order.customer.nickname");
    assertEquals(Kind.NOT_SHARED, withoutAGetter.kind());
    assertEquals("nickname", withoutAGetter.segment());
    assertEquals("PathCustomer", withoutAGetter.segmentOwner());

  }

  @Test
  @DisplayName("A segment the declared type has not got is named with the type which has not got it")
  public void aSegmentWhichIsNoAttributeIsNamed() {

    final var verdict = find(PathAggregate.class, "order.customer.town");
    assertEquals(Kind.NO_SUCH_ATTRIBUTE, verdict.kind());
    assertEquals("town", verdict.segment());
    assertEquals(2, verdict.segmentIndex());
    assertEquals("PathCustomer", verdict.segmentOwner());

    // the first segment is answered the same way; whether a name which is no attribute
    // at all is worth a word is the caller's decision, not this walk's
    assertEquals(Kind.NO_SUCH_ATTRIBUTE, find(PathAggregate.class, "somethingTheModelProvides").kind());

  }

  @Test
  @DisplayName("Nothing lives below a value which travels as a number, a text or an enum's name")
  public void aSingleValueCarriesNothingBelowIt() {

    // the silent case a conditional event turns into an endless wait: the date reaches
    // the BPMS as '2027-03-04', and a text has no 'year'
    final var temporal = find(PathAggregate.class, "order.dueDate.year");
    assertEquals(Kind.NOTHING_BELOW, temporal.kind());
    assertEquals("year", temporal.segment());
    assertEquals("LocalDate", temporal.segmentOwner());

    // an enum arrives as its name, so the String API is all there is
    assertEquals(Kind.NOTHING_BELOW, find(PathAggregate.class, "order.size.blank").kind());
    // and a number is a number
    assertEquals(Kind.NOTHING_BELOW, find(PathAggregate.class, "order.items.price.scale").kind());

  }

  @Test
  @DisplayName("Wherever the declared type cannot decide, nothing is claimed")
  public void whatCannotBeDecidedIsNotClaimed() {

    // a map answers whatever key it happens to hold
    assertEquals(Kind.UNDECIDABLE, find(PathAggregate.class, "order.labels.kind").kind());
    // an interface is whichever implementation the application assigned
    assertEquals(Kind.UNDECIDABLE, find(PathAggregate.class, "order.address.city").kind());
    // a collection which does not say what its elements are
    assertEquals(Kind.UNDECIDABLE, find(PathAggregate.class, "order.attachments.name").kind());
    // and nothing to walk at all
    assertEquals(
        Kind.UNDECIDABLE,
        testee.whatAPathFinds(PathAggregate.class, List.of(), AggregateSyncMode.FULL).kind());
    assertEquals(Kind.UNDECIDABLE, testee.whatAPathFinds(PathAggregate.class, null, AggregateSyncMode.FULL).kind());
    assertEquals(
        Kind.UNDECIDABLE,
        testee.whatAPathFinds(null, List.of("order"), AggregateSyncMode.FULL).kind());
    assertEquals(
        Kind.UNDECIDABLE,
        testee.whatAPathFinds(PathAggregate.class, List.of("order", " "), AggregateSyncMode.FULL).kind());

  }

  @Test
  @DisplayName("The adapter's default decides for a path of an aggregate carrying no annotation")
  public void theAdapterDefaultDecidesForAPathToo() {

    assertEquals(
        Kind.SHARED_VALUE,
        testee.whatAPathFinds(PlainAggregate.class, List.of("content"), AggregateSyncMode.FULL).kind());
    final var unshared = testee
        .whatAPathFinds(PlainAggregate.class, List.of("content"), AggregateSyncMode.NONE);
    assertEquals(Kind.NOT_SHARED, unshared.kind());
    assertEquals("content", unshared.segment());

  }

  private Optional<Class<?>> typeAtTheEndOf(
      final Class<?> aggregateClass,
      final String path) {

    return testee
        .whatTypeAPathEndsAt(aggregateClass, List.of(path.split("\\.")), AggregateSyncMode.FULL);

  }

  @Test
  @DisplayName("A path reaching a shared value is answered with the type that attribute declares")
  public void aSharedValueIsAnsweredWithItsDeclaredType() {

    // the case an adapter asks about: a decimal at the top level, which a BPMS may
    // store in a format that hands something else back
    assertEquals(Optional.of(java.math.BigDecimal.class), typeAtTheEndOf(PathAggregate.class, "total"));
    // and the same question two levels down
    assertEquals(Optional.of(String.class), typeAtTheEndOf(PathAggregate.class, "order.customer.name"));
    assertEquals(
        Optional.of(java.math.BigDecimal.class),
        typeAtTheEndOf(PathAggregate.class, "order.items.price"));
    // an attribute holding a structure declares a type as well
    assertEquals(Optional.of(PathOrder.class), typeAtTheEndOf(PathAggregate.class, "order"));

  }

  @Test
  @DisplayName("A path which finds no value in the BPMS has no type to name")
  public void aPathFindingNoValueNamesNoType() {

    // nothing the sync model keeps back ever reaches the BPMS, so nothing of it has to
    // survive the way there and back
    assertTrue(typeAtTheEndOf(PathAggregate.class, "hiddenOrder.customer.name").isEmpty());
    assertTrue(typeAtTheEndOf(PathAggregate.class, "order.internalCode").isEmpty());
    // a date travels as its text, so 'year' is no value of the BPMS at all
    assertTrue(typeAtTheEndOf(PathAggregate.class, "order.dueDate.year").isEmpty());
    // a name which is no attribute of the aggregate may be a variable of the model
    assertTrue(typeAtTheEndOf(PathAggregate.class, "somethingTheModelProvides").isEmpty());
    assertTrue(typeAtTheEndOf(PathAggregate.class, "order.customer.town").isEmpty());

  }

  @Test
  @DisplayName("Wherever the declared types cannot decide, no type is named either")
  public void whatCannotBeDecidedNamesNoType() {

    // a map answers whatever key it happens to hold
    assertTrue(typeAtTheEndOf(PathAggregate.class, "order.labels.kind").isEmpty());
    // an interface is whichever implementation the application assigned
    assertTrue(typeAtTheEndOf(PathAggregate.class, "order.address.city").isEmpty());
    // the values are cut at the nesting limit, so a segment past it says nothing about
    // what the BPMS holds, while the segment right at the limit is answered
    assertEquals(
        Optional.of(CyclicAggregate.class),
        typeAtTheEndOf(CyclicAggregate.class,
            String.join(".", java.util.Collections.nCopies(AggregateSyncSupport.MAX_DEPTH, "self"))));
    assertTrue(
        typeAtTheEndOf(
            CyclicAggregate.class,
            String.join(".", java.util.Collections.nCopies(AggregateSyncSupport.MAX_DEPTH + 1, "self"))).isEmpty());
    // and nothing to walk at all
    assertTrue(testee.whatTypeAPathEndsAt(PathAggregate.class, List.of(), AggregateSyncMode.FULL).isEmpty());
    assertTrue(testee.whatTypeAPathEndsAt(null, List.of("total"), AggregateSyncMode.FULL).isEmpty());

  }

  @Test
  @DisplayName("The adapter's default decides whether a path has a type at all")
  public void theAdapterDefaultDecidesWhetherAPathHasAType() {

    assertEquals(
        Optional.of(String.class),
        testee.whatTypeAPathEndsAt(PlainAggregate.class, List.of("content"), AggregateSyncMode.FULL));
    assertTrue(
        testee.whatTypeAPathEndsAt(PlainAggregate.class, List.of("content"), AggregateSyncMode.NONE).isEmpty(),
        "an attribute this adapter does not share reaches no BPMS");

  }

  public static class ZonesAggregate {

    public java.util.TimeZone getZone() {
      return java.util.TimeZone.getTimeZone("Europe/Berlin");
    }

    public java.time.ZoneId getPreferredZone() {
      return java.time.ZoneId.of("Europe/Vienna");
    }

    public List<java.util.TimeZone> getOfficeZones() {
      return List.of(java.util.TimeZone.getTimeZone("America/New_York"));
    }

    public ZoneHolder getHome() {
      return new ZoneHolder();
    }

  }

  public static class ZoneHolder {

    public java.util.TimeZone getZone() {
      return java.util.TimeZone.getTimeZone("Asia/Kolkata");
    }

  }

  @Test
  @DisplayName("A time zone is shared as the id of its zone, however deep it sits")
  public void aTimeZoneIsSharedAsTheIdOfItsZone() {

    // the runtime hands out an implementation of its own for a time zone
    // (sun.util.calendar.ZoneInfo), whose getters no application may read. Reading them
    // ended the sync point with an InaccessibleObjectException, measured on 2026-09-16.
    final var shared = full(new ZonesAggregate());

    assertEquals("Europe/Berlin", shared.get("zone"));
    assertEquals("Europe/Vienna", shared.get("preferredZone"));
    assertEquals(List.of("America/New_York"), shared.get("officeZones"), "an element of a collection too");
    assertEquals(Map.of("zone", "Asia/Kolkata"), shared.get("home"), "and an attribute of a nested object");

  }

  @Test
  @DisplayName("A time zone carries nothing a path could read below it")
  public void nothingIsBelowATimeZone() {

    assertEquals(Kind.NOTHING_BELOW, find(ZonesAggregate.class, "zone.rawOffset").kind());
    assertEquals(
        Optional.of(java.util.TimeZone.class),
        typeAtTheEndOf(ZonesAggregate.class, "zone"));

  }

  public static class AggregateWithTextNobodyReadsBack {

    public List<java.util.Locale> getLanguages() {
      return List.of(java.util.Locale.GERMANY);
    }

    public java.net.URI getHomepage() {
      return java.net.URI.create("https://vanillabp.io");
    }

    public java.sql.Timestamp getSignedOn() {
      return java.sql.Timestamp.from(java.time.Instant.parse("2026-09-16T18:15:30Z"));
    }

    public Optional<String> getNote() {
      return Optional.of("a note");
    }

    public java.time.LocalDate getDay() {
      return java.time.LocalDate.parse("2026-09-16");
    }

    public SignedContract getContract() {
      return new SignedContract();
    }

  }

  public static class SignedContract {

    public java.util.Locale getCountersignedIn() {
      return java.util.Locale.GERMANY;
    }

  }

  @Test
  @DisplayName("An attribute whose text nothing reads back is named while the application boots")
  public void whatCannotComeBackIsSaidAtStartup() {

    final var said = whatIsSaidWhileValidating(AggregateWithTextNobodyReadsBack.class);

    assertEquals(5, said.size(), () -> "one word per attribute, and none about the others: "
        + said);
    assertTrue(
        said.stream().anyMatch(message -> message.contains("'languages'") && message.contains("'de_DE'")),
        () -> "an element of a collection is asked about as well: "
            + said);
    assertTrue(
        said.stream().anyMatch(message -> message.contains("'homepage'") && message.contains("java.net.URI")),
        () -> said.toString());
    assertTrue(
        said.stream().anyMatch(message -> message.contains("'signedOn'") && message.contains("java.sql.Timestamp")),
        () -> "a Date subclass is carried, the declared type is not: "
            + said);
    assertTrue(
        said.stream().anyMatch(message -> message.contains("'note'") && message.contains("java.util.Optional")),
        () -> said.toString());
    assertTrue(
        said
            .stream()
            .anyMatch(
                message -> message.contains("'countersignedIn'") && message.contains(SignedContract.class.getName())),
        () -> "a nested object is walked into, and the word names the class it belongs to: "
            + said);
    assertTrue(
        said.stream().noneMatch(message -> message.contains("'day'")),
        () -> "and one the way back reads is no case at all: "
            + said);

  }

  public static class AggregateWithACalendar {

    public java.util.Calendar getSignedOn() {
      return java.util.GregorianCalendar.getInstance();
    }

    @NoSyncWithBPMS
    public java.util.Calendar getNeverShared() {
      return java.util.GregorianCalendar.getInstance();
    }

    public List<java.util.GregorianCalendar> getReminders() {
      return List.of(new java.util.GregorianCalendar());
    }

    public SigningDetails getDetails() {
      return new SigningDetails();
    }

  }

  public static class SigningDetails {

    public java.util.Calendar getCountersignedOn() {
      return java.util.GregorianCalendar.getInstance();
    }

  }

  @Test
  @DisplayName("A Calendar the BPMS would be given stops the boot, wherever it sits")
  public void aSharedCalendarStopsTheBoot() {

    final var refused = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.validateSyncModel(AggregateWithACalendar.class));

    final var message = refused.getMessage();

    assertTrue(
        message.contains("'signedOn'") && message.contains("java.util.Calendar"),
        () -> "the attribute and its type: "
            + message);
    assertTrue(
        message.contains("Share an Instant"),
        () -> "and what to declare instead: "
            + message);
    assertTrue(
        message.contains("debug form"),
        () -> "and what the BPMS would be given today: "
            + message);
    assertTrue(
        message.contains("'reminders'") && message.contains("java.util.GregorianCalendar"),
        () -> "an element of a collection is refused as well, subclass included: "
            + message);
    assertTrue(
        message.contains("'countersignedOn'") && message.contains(SigningDetails.class.getName()),
        () -> "and so is one inside a nested object: "
            + message);
    assertTrue(
        !message.contains("'neverShared'"),
        () -> "an attribute which never travels is no case at all: "
            + message);

  }

  public static class AggregateSharingOnlyItsReference {

    @SyncWithBPMS
    public String getReference() {
      return "R-1";
    }

    public java.util.Calendar getSignedOn() {
      return java.util.GregorianCalendar.getInstance();
    }

  }

  @Test
  @DisplayName("A Calendar nobody shares lets the application boot")
  public void aCalendarNobodySharesIsNoDefect() {

    // the class names what it shares, so everything it does not name stays at home -
    // and the refusal asks the same chain the sync point asks
    assertEquals(
        Set.of("reference"),
        full(new AggregateSharingOnlyItsReference()).keySet(),
        "the calendar does not reach the BPMS");

    testee.validateSyncModel(AggregateSharingOnlyItsReference.class);

  }

  public static class AuditTrail {

    public java.util.Calendar getTouchedOn() {
      return java.util.GregorianCalendar.getInstance();
    }

  }

  public static class AggregateHidingItsAudit {

    public String getReference() {
      return "R-2";
    }

    @NoSyncWithBPMS
    public AuditTrail getAudit() {
      return new AuditTrail();
    }

  }

  @Test
  @DisplayName("A Calendar below an attribute nobody shares lets the application boot")
  public void aCalendarBelowAHiddenAttributeIsNoDefect() {

    assertEquals(
        Set.of("reference"),
        full(new AggregateHidingItsAudit()).keySet(),
        "the audit trail does not reach the BPMS");

    testee.validateSyncModel(AggregateHidingItsAudit.class);

  }

  public static class AggregateShowingTheAuditOnceMore {

    @NoSyncWithBPMS
    public AuditTrail getHiddenAudit() {
      return new AuditTrail();
    }

    public AuditTrail getVisibleAudit() {
      return new AuditTrail();
    }

  }

  @Test
  @DisplayName("A type reached twice is refused for the path which shares it")
  public void aTypeHiddenOnOnePathIsStillRefusedOnTheOther() {

    final var refused = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.validateSyncModel(AggregateShowingTheAuditOnceMore.class));

    assertTrue(
        refused.getMessage().contains("'touchedOn'"),
        () -> "the hidden path must not silence the shared one: "
            + refused.getMessage());
    assertEquals(
        1,
        refused.getMessage().lines().count(),
        () -> "and the same word is not said twice: "
            + refused.getMessage());

  }

  private List<String> whatIsSaidWhileValidating(
      final Class<?> workflowAggregateClass) {

    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(AggregateSyncSupport.class);
    final var recorded = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    recorded.start();
    logger.addAppender(recorded);
    try {
      testee.validateSyncModel(workflowAggregateClass);
    } finally {
      logger.detachAppender(recorded);
    }
    return recorded.list.stream().map(event -> event.getFormattedMessage()).toList();

  }

}
