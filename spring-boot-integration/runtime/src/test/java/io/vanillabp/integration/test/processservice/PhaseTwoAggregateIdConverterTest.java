package io.vanillabp.integration.test.processservice;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Tests the aggregate-ID handling of outbox dispatches through the
 * {@link PhaseTwoRouter}: the ID type comes from the aggregate's
 * {@link AggregatePersistenceAware} (Spring Data based implementations answer via
 * Spring Data), the conversion itself lives in the core. Aggregates whose ID type is
 * not determinable (custom persistence - {@code getAggregateIdType()} returns
 * {@code null}) have the serialized String passed through instead of blocking the
 * outbox entry permanently; an ID type known to not round-trip fails at startup.
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoAggregateIdConverterTest {

  @Mock
  private MigratableProcessService<Object> migratableProcessService;

  @Mock
  private AggregatePersistenceAware<Object> aggregatePersistenceAware;

  private final PhaseTwoRouter router = new PhaseTwoRouter();

  @BeforeEach
  public void mockProcessService() {

    lenient().when(migratableProcessService.getAdapterId()).thenReturn("test-adapter");

  }

  private MigrationAdapterProperties buildProperties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
    return properties;

  }

  /**
   * What the adapter was asked to do. A mocked adapter answers a default method the way
   * it answers every other one - with <code>null</code> - so it would contribute no
   * phase operation at all and the core would refuse every call. What it answers here
   * writes down the aggregate ID phase two was handed, which is what this test is about.
   */
  private final java.util.List<Object> phaseTwoAggregateIds = new java.util.ArrayList<>();

  private void adapterRecordsItsOperations() {

    org.mockito.Mockito
        .lenient()
        .when(migratableProcessService.phaseOperations())
        .thenReturn(
            java.util.Map
                .of(
                    io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
                    io.vanillabp.integration.adapter.spi.PhaseOperationHandler
                        .of(
                            request -> {
                            },
                            request -> phaseTwoAggregateIds.add(request.workflowAggregateId()))));

  }

  private void buildProcessService() {

    adapterRecordsItsOperations();

    new ProcessServiceSpringBean<>(
        "test-module", "TestProcess", Object.class, buildProperties(), aggregatePersistenceAware, List
            .of(migratableProcessService), null, router, null, null);

  }

  private PhaseTwoCall startWorkflowCall(
      final String serializedAggregateId) {

    return PhaseTwoCall
        .of(
            PhaseOperation.START_WORKFLOW, "test-module", "TestProcess", serializedAggregateId, "test-adapter", Map
                .of());

  }

  @Test
  @DisplayName("An aggregate-ID type not convertible from/to String fails the startup")
  public void unconvertibleAggregateIdTypeFailsAtStartup() {

    // The ID crosses the outbox serialized as a String - an
    // unconvertible ID type fails at bean creation with a guiding message
    when(aggregatePersistenceAware.getAggregateIdType()).thenAnswer(invocation -> java.io.InputStream.class);

    final var exception = org.junit.jupiter.api.Assertions.assertThrowsExactly(
        IllegalStateException.class,
        this::buildProcessService);

    org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains(Object.class.getName()));
    // phrases spanning the text block's line breaks: guards against broken
    // continuations gluing words together or inserting indentation whitespace
    org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("converted from/to String!"));
    org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("round-trip losslessly"));
    org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("AggregatePersistenceAware"));
    org.junit.jupiter.api.Assertions.assertFalse(
        exception.getMessage().contains("  "),
        "message must not contain consecutive spaces");

  }

  @Test
  @DisplayName("The serialized ID of a Spring-Data aggregate is converted back to the ID type")
  public void springDataAggregateIdIsConverted() {

    when(aggregatePersistenceAware.getAggregateIdType()).thenAnswer(invocation -> Long.class);

    buildProcessService();
    router.dispatch(startWorkflowCall("42"));

    org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(42L), phaseTwoAggregateIds);

  }

  @Test
  @DisplayName("For an aggregate without determinable ID type the serialized ID is passed through instead of failing")
  public void nonSpringDataAggregateIdIsPassedThrough() {

    // custom persistence: getAggregateIdType() returns null by contract - the
    // custom persistence layer is responsible for the serialized form
    when(aggregatePersistenceAware.getAggregateIdType()).thenReturn(null);

    buildProcessService();

    // the dispatch must NOT fail (which would block the outbox entry permanently)
    router.dispatch(startWorkflowCall("custom-id-4711"));

    org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("custom-id-4711"), phaseTwoAggregateIds);

  }

}
