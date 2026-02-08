package io.vanillabp.integration.test.adapter;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

@ExtendWith(SuppressOutputExtension.class)
public class AdapterConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  /**
   * ProcessService<Aggregate> should be created using dummy adapter configured in application.yaml
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testAdapterConfiguration() {

    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(WorkflowModuleConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                HibernateJpaAutoConfiguration.class, JpaSpringDataUtilConfiguration.class,
                DummyAdapterConfiguration.class, WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:testdb",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=none"
        )
        .run(context -> {

          final var sampleProcessServiceNames = context.getBeanNamesForType(
              ResolvableType.forClassWithGenerics(ProcessService.class,
                  Aggregate.class));
          Assertions.assertNotEquals(0, sampleProcessServiceNames.length);
          final var sampleProcessService = context.getBean(sampleProcessServiceNames[0], ProcessService.class);

          Assertions.assertNotNull(sampleProcessService);
          Assertions.assertInstanceOf(ProcessServiceSpringBean.class, sampleProcessService);

          @SuppressWarnings("unchecked")
          final var processServiceBean = (ProcessServiceSpringBean<Aggregate>) sampleProcessService;
          final var migrationProcessService = processServiceBean.getMigrationProcessService();

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
