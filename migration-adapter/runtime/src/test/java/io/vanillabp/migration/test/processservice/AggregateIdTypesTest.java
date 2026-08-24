package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.AggregateIdTypes;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.persistence.Id;

/**
 * The ID's name and value are determined by the same walk as its type,
 * because the persistence implementations VanillaBP provides on Quarkus answer
 * {@code getAggregateIdName}, {@code getAggregateIdType} and {@code getAggregateId}
 * from it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateIdTypesTest {

  public static class AnnotatedField {

    @Id
    private String loanRequestId;

    public AnnotatedField(
        final String loanRequestId) {
      this.loanRequestId = loanRequestId;
    }

  }

  public static class AnnotatedGetter {

    private Long key;

    public AnnotatedGetter(
        final Long key) {
      this.key = key;
    }

    @Id
    public Long getKey() {
      return key;
    }

  }

  public static class FieldNamedId {

    private Integer id;

    public FieldNamedId(
        final Integer id) {
      this.id = id;
    }

  }

  public static class GetterNamedGetId {

    public String getId() {
      return "from-getter";
    }

  }

  public static class WithoutAnyId {

    private String content = "no id at all";

  }

  @Test
  @DisplayName("An annotated field contributes name, type and value - also a private one")
  public void annotatedFieldAnswersEverything() {

    assertEquals(Optional.of("loanRequestId"), AggregateIdTypes.determineIdName(AnnotatedField.class));
    assertEquals(Optional.of(String.class), AggregateIdTypes.determineIdType(AnnotatedField.class));
    assertEquals("4711", AggregateIdTypes.readId(new AnnotatedField("4711")));

  }

  @Test
  @DisplayName("An annotated getter contributes its property name, not the method name")
  public void annotatedGetterContributesThePropertyName() {

    assertEquals(Optional.of("key"), AggregateIdTypes.determineIdName(AnnotatedGetter.class));
    assertEquals(Optional.of(Long.class), AggregateIdTypes.determineIdType(AnnotatedGetter.class));
    assertEquals(42L, AggregateIdTypes.readId(new AnnotatedGetter(42L)));

  }

  @Test
  @DisplayName("Without an annotation a member named 'id' answers, field before getter")
  public void memberNamedIdAnswers() {

    assertEquals(Optional.of("id"), AggregateIdTypes.determineIdName(FieldNamedId.class));
    assertEquals(Optional.of(Integer.class), AggregateIdTypes.determineIdType(FieldNamedId.class));
    assertEquals(7, AggregateIdTypes.readId(new FieldNamedId(7)));

    assertEquals(Optional.of("id"), AggregateIdTypes.determineIdName(GetterNamedGetId.class));
    assertEquals("from-getter", AggregateIdTypes.readId(new GetterNamedGetId()));

  }

  @Test
  @DisplayName("An ID not yet assigned is null, an aggregate without any ID property is a guiding failure")
  public void missingIdIsReportedDifferentlyFromAnUnsetId() {

    assertNull(AggregateIdTypes.readId(new AnnotatedField(null)));

    assertEquals(Optional.empty(), AggregateIdTypes.determineIdName(WithoutAnyId.class));
    assertEquals(Optional.empty(), AggregateIdTypes.determineIdType(WithoutAnyId.class));
    final var failure = assertThrows(
        IllegalStateException.class,
        () -> AggregateIdTypes.readId(new WithoutAnyId()));
    assertTrue(failure.getMessage().contains(WithoutAnyId.class.getName()), failure.getMessage());
    assertTrue(failure.getMessage().contains("AggregatePersistenceAware"), failure.getMessage());

  }

}
