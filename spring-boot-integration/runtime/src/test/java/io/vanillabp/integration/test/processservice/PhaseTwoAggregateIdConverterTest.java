package io.vanillabp.integration.test.processservice;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseTwoCall;
import io.vanillabp.integration.adapter.spi.PhaseTwoOperation;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * Tests the aggregate-ID converter registered with the {@link PhaseTwoRouter} by
 * {@link ProcessServiceSpringBean}: Spring-Data managed aggregates get their ID
 * converted back to the ID type, while aggregates NOT managed by Spring Data (custom
 * {@link AggregatePersistenceAware} implementations - where
 * {@code SpringDataUtil.getIdType} throws) have the serialized String passed through
 * instead of blocking the outbox entry permanently.
 */
@ExtendWith(MockitoExtension.class)
public class PhaseTwoAggregateIdConverterTest {

  @Mock
  private MigratableProcessService<Object> migratableProcessService;

  @Mock
  private AggregatePersistenceAware<Object> aggregatePersistenceAware;

  @Mock
  private ObjectProvider<SpringDataUtil> springDataUtilProvider;

  @Mock
  private SpringDataUtil springDataUtil;

  private final PhaseTwoRouter router = new PhaseTwoRouter();

  @BeforeEach
  public void buildProcessService() {

    lenient().when(migratableProcessService.getAdapterId()).thenReturn("test-adapter");
    when(springDataUtilProvider.getIfAvailable()).thenReturn(springDataUtil);

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();

    new ProcessServiceSpringBean<>(
        "test-module", "TestProcess", Object.class, properties, aggregatePersistenceAware, List
            .of(migratableProcessService), null, router, springDataUtilProvider);

  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName("An aggregate-ID type not convertible from/to String fails the startup")
  public void unconvertibleAggregateIdTypeFailsAtStartup() {

    // story 26c: the ID crosses the outbox serialized as a String - an
    // unconvertible ID type fails at bean creation with a guiding message
    when(springDataUtil.getIdType(Object.class)).thenAnswer(invocation -> java.io.InputStream.class);

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();

    final var exception = org.junit.jupiter.api.Assertions.assertThrowsExactly(
        IllegalStateException.class,
        () -> new ProcessServiceSpringBean<>(
            "test-module", "TestProcess", Object.class, properties, aggregatePersistenceAware, List
                .of(migratableProcessService), null, router, springDataUtilProvider));

    org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains(Object.class.getName()));
    org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("round-trip"));
    org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("AggregatePersistenceAware"));

  }

  private PhaseTwoCall startWorkflowCall(
      final String serializedAggregateId) {

    return new PhaseTwoCall(
        PhaseTwoOperation.START_WORKFLOW, "test-module", "TestProcess", serializedAggregateId, "test-adapter", Map
            .of());

  }

  @Test
  @DisplayName("The serialized ID of a Spring-Data aggregate is converted back to the ID type")
  public void springDataAggregateIdIsConverted() {

    when(springDataUtil.getIdType(Object.class)).thenAnswer(invocation -> Long.class);

    router.dispatch(startWorkflowCall("42"));

    verify(migratableProcessService).startWorkflowPhaseTwo("test-module", "TestProcess", aggregatePersistenceAware,
        42L);

  }

  @Test
  @DisplayName("For a non-Spring-Data aggregate the serialized ID is passed through instead of failing")
  public void nonSpringDataAggregateIdIsPassedThrough() {

    when(springDataUtil.getIdType(Object.class))
        .thenThrow(new IllegalArgumentException("Type 'Object' is not an entity!"));

    router.dispatch(startWorkflowCall("custom-id-4711"));

    // the dispatch must NOT fail (which would block the outbox entry permanently) -
    // the custom persistence layer is responsible for the serialized form
    verify(migratableProcessService).startWorkflowPhaseTwo(
        "test-module", "TestProcess", aggregatePersistenceAware, "custom-id-4711");

  }

}
