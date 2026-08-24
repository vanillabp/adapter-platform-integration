package io.vanillabp.integration.test.processservice;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.runtime.workflowtask.SpringTransactionSupport;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentService;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer;
import io.vanillabp.integration.test.adapter.TestMigratableProcessService;
import io.vanillabp.integration.test.samples.springtransactional.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Spring's <code>&#64;Transactional</code> only creates a transaction boundary on Quarkus
 * when the extension {@code quarkus-spring-tx} is part of the application, which maps it
 * onto the JTA annotation at build time. This module does not depend on that extension, so
 * the annotation is inert here and the application HAS to boot with it on a
 * <code>&#64;WorkflowTask</code> method: failing the boot over an annotation
 * that does nothing would hit exactly the applications sharing modules with a Spring code
 * base, i.e. VanillaBP's migration audience. The developer gets a warning instead, whose
 * wording is asserted by the core's unit tests.
 * <p>
 * With the extension present the same annotation IS a defect, which is what the
 * build-time detection in {@code TransactionAnnotationsBuildStepProcessor} decides.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SpringTransactionAnnotationOnQuarkusTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.springtransactional")
          .addPackage("org.springframework.transaction.annotation")
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  ProcessService<Aggregate> processService;

  @Inject
  SpringTransactionSupport springTransactionSupport;

  @Test
  public void anInertSpringAnnotationDoesNotFailTheBoot() {

    Assertions.assertFalse(
        springTransactionSupport.honored(),
        "quarkus-spring-tx is not a dependency of this module");
    Assertions.assertNotNull(processService);

  }

}
