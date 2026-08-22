package io.vanillabp.integration.test.utils.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class SpringDataUtilBasedAggregatePersistenceSupportTest {

  @Captor
  private ArgumentCaptor<String> springDataUtilCaptor;

  @Mock
  private SpringDataUtil springDataUtil;

  @Mock
  private CrudRepository<String, Object> repository;

  @Test
  public void testGetAggregateId() {

    when(springDataUtil.getId(any())).thenReturn("4711");

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class);

    final var id = support.getAggregateId("0815");

    assertNotNull(id);
    assertEquals("4711", id);
    verify(springDataUtil, times(1)).getId(springDataUtilCaptor.capture());
    assertEquals("0815", springDataUtilCaptor.getValue());

  }

  @Test
  public void testSaveAggregate() {

    when(repository.save(any())).thenReturn("4711");
    when(springDataUtil.getRepository("0815")).thenAnswer(answer -> repository);

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class);

    final var saved = support.save("0815");

    assertNotNull(saved);
    assertEquals("4711", saved);
    verify(springDataUtil, times(1)).getRepository(springDataUtilCaptor.capture());
    assertEquals("0815", springDataUtilCaptor.getValue());

  }

  @Test
  public void testGetAggregateIdName() {

    when(springDataUtil.getIdName(String.class)).thenReturn("id");

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class);

    final var idName = support.getAggregateIdName();

    assertEquals("id", idName);

  }

  /**
   * Story 114: the ID type is where the core asks while the application starts, so it is
   * where a missing repository has to speak up. It used to answer <code>null</code>, which
   * is the contract's "a custom layer owns the serialized form" - the application then
   * booted and failed at the first task delivery.
   */
  @Test
  @DisplayName("An aggregate without a repository is reported, naming the aggregate, its module and the ways out")
  public void aMissingRepositoryIsReportedWhileStarting() {

    when(springDataUtil.getRepository(String.class))
        .thenThrow(new IllegalStateException("No Spring Data repository defined for 'java.lang.String'!"));

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class, "loan-approval");

    final var reported = assertThrows(IllegalStateException.class, support::getAggregateIdType);

    assertTrue(
        reported.getMessage().contains("java.lang.String"),
        () -> "the aggregate is named: "
            + reported.getMessage());
    assertTrue(
        reported.getMessage().contains("loan-approval"),
        () -> "and the workflow module it belongs to: "
            + reported.getMessage());
    assertTrue(
        reported.getMessage()
            .contains("Spring Data repository") && reported.getMessage().contains(AggregatePersistenceAware.class
                .getName()) && reported.getMessage().contains(SpringDataUtil.class.getName()),
        () -> "and all three ways out: "
            + reported.getMessage());
    assertNotNull(reported.getCause(), "what Spring Data said stays readable");

  }

  /**
   * The other half of the same narrowing: a repository which cannot say what the ID type
   * is keeps answering <code>null</code>. That is a contract answer, not a defect - a
   * custom {@link SpringDataUtil} implementation owning the serialized form is the case.
   */
  @Test
  @DisplayName("An undeterminable ID type stays the contract's null")
  public void anUndeterminableIdTypeStaysNull() {

    when(springDataUtil.getRepository(String.class)).thenAnswer(answer -> repository);
    when(springDataUtil.getIdType(String.class))
        .thenThrow(new IllegalStateException("Type 'java.lang.String' is not an entity!"));

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class, "loan-approval");

    assertNull(
        support.getAggregateIdType(),
        "the aggregate has a repository, so what its ID type is remains that layer's business");

  }

  @Test
  @DisplayName("The ID type is what Spring Data says it is")
  public void theIdTypeComesFromSpringData() {

    when(springDataUtil.getRepository(String.class)).thenAnswer(answer -> repository);
    when(springDataUtil.getIdType(String.class)).thenAnswer(answer -> Long.class);

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class, "loan-approval");

    assertEquals(Long.class, support.getAggregateIdType());

  }

  @Test
  public void testgetAggregateClass() {

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class);

    final var clasz = support.getAggregateClass();

    assertNotNull(clasz);
    assertEquals(String.class, clasz);

  }

}
