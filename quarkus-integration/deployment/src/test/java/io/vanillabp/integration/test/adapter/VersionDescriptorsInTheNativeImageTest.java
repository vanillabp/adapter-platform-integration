package io.vanillabp.integration.test.adapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The build registers the version descriptor of every VanillaBP part as a resource of the
 * native image, also where the JAR holding it is not indexed by Quarkus.
 * <p>
 * The platform's own descriptor lies in the JAR of the integration SPI, an ordinary JAR
 * which Quarkus does not index. The descriptor of an adapter lies in the application here.
 * A native image carries only the resources it was told about. A binary missing these
 * files cannot tell whether its parts belong together and warns at every start.
 * <p>
 * The build step of the test reads what the build registered. The test itself runs in
 * another class loader, so the list goes from one to the other as a system property.
 */
@ExtendWith(SuppressOutputExtension.class)
public class VersionDescriptorsInTheNativeImageTest {

  private static final String REGISTERED_RESOURCES = "%s.registered"
      .formatted(VersionDescriptorsInTheNativeImageTest.class.getName());

  private static final String DESCRIPTOR_OF_THE_ADAPTER = "META-INF/vanillabp/adapter-fitting.properties";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addAsResource("adapter-fitting/%s".formatted(DESCRIPTOR_OF_THE_ADAPTER), DESCRIPTOR_OF_THE_ADAPTER)
          .addClass(DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .addBuildChainCustomizer(rememberTheRegisteredResources());

  @Test
  public void theDescriptorsOfAllPartsAreResourcesOfTheNativeImage() {

    final var registered = Arrays.asList(System
        .getProperty(REGISTERED_RESOURCES, "")
        .split(","));
    assertTrue(registered.contains(VanillaBpParts.PLATFORM_DESCRIPTOR),
        "the platform's descriptor is not registered, only %s".formatted(registered));
    assertTrue(registered.contains(DESCRIPTOR_OF_THE_ADAPTER),
        "the adapter's descriptor is not registered, only %s".formatted(registered));

  }

  /**
   * @return A build step which writes every resource registered for the native image into a
   *         system property
   */
  private static Consumer<BuildChainBuilder> rememberTheRegisteredResources() {

    return builder -> builder
        .addBuildStep(new BuildStep() { // no lambda possible because BuildSteps need to be final
          @Override
          public void execute(
              final BuildContext context) {
            final List<NativeImageResourceBuildItem> registered = context.consumeMulti(
                NativeImageResourceBuildItem.class);
            System.setProperty(REGISTERED_RESOURCES, String.join(",", registered
                .stream()
                .flatMap(item -> item
                    .getResources()
                    .stream())
                .toList()));
            // a build step has to produce something, or the build leaves it out
            context.produce(new GeneratedResourceBuildItem("version-descriptors-checked", new byte[0]));
          }
        })
        .consumes(NativeImageResourceBuildItem.class)
        .produces(GeneratedResourceBuildItem.class)
        .build();

  }

}
