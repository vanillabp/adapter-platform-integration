package io.vanillabp.integration.test.adapters;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;

import io.vanillabp.adapters.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;

/*
 * @SpringBootTest
 * 
 * @ContextConfiguration(classes = {
 * DummyAdapterConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class
 * })
 * //@TestPropertySource("classpath:application.yaml")
 */
public class AdapterConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  /*
  @Autowired
  private ProcessService<Aggregate> sampleProcessService;
  
  @Autowired
  private SpringBootMigrationAdapterProperties properties;
  */
  /**
   * ProcessService<Aggregate> should be created using dummy adapter configured in application.yaml
   */
  @Test
  public void testAdapterConfiguration() {

    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(WorkflowModuleConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(DummyAdapterConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          final var sampleProcessServiceNames = context.getBeanNamesForType(
              ResolvableType.forClassWithGenerics(ProcessService.class,
                  Aggregate.class));
          final var sampleProcessService = context.getBean(sampleProcessServiceNames[0], ProcessService.class);

          Assertions.assertNotNull(sampleProcessService);
          Assertions.assertInstanceOf(MigrationProcessService.class, sampleProcessService);

          @SuppressWarnings("unchecked")
          final var migrationProcessService = (MigrationProcessService<Aggregate>) sampleProcessService;
          final var workflowAggregateClass = migrationProcessService.getWorkflowAggregateClass();
          Assertions.assertNotNull(workflowAggregateClass);
          Assertions.assertEquals(Aggregate.class, workflowAggregateClass);

          final var adaptersConfigured = migrationProcessService.getAdapters();
          Assertions.assertNotNull(adaptersConfigured);
          Assertions.assertEquals(1, adaptersConfigured.size());

          final var adapter = adaptersConfigured.keySet().iterator().next();
          Assertions.assertNotNull(adapter);
          Assertions.assertEquals("test", adapter);

          final var adapterType = adaptersConfigured.get(adapter);
          Assertions.assertNotNull(adapterType);
          Assertions.assertEquals("dummy", adapterType);

          final var prioritizedAdapters = migrationProcessService.getPrioritizedAdapters();
          Assertions.assertNotNull(prioritizedAdapters);
          Assertions.assertEquals(1, prioritizedAdapters.size());
          final var defaultAdapter = prioritizedAdapters.getFirst();
          Assertions.assertNotNull(defaultAdapter);
          Assertions.assertEquals("test", defaultAdapter);

        });

  }

}
