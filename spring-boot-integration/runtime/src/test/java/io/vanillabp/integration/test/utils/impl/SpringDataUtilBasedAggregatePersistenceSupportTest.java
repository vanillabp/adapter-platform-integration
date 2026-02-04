package io.vanillabp.integration.test.utils.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;

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
  public void testgetAggregateClass() {

    final var support = new SpringDataUtilBasedAggregatePersistenceSupport<>(
        springDataUtil, String.class);

    final var clasz = support.getAggregateClass();

    assertNotNull(clasz);
    assertEquals(String.class, clasz);

  }

}
