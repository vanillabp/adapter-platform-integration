package io.vanillabp.bpmsdouble.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import io.vanillabp.bpmsdouble.DummyAdapter;

/**
 * Wires the double's process-service and deployment-service beans. There is ONE element
 * bean per configured adapter id of the dummy type. The imported
 * {@link DummyAdapterBeanRegistrar} registers them. It is the reference for the bean
 * rule of one instance per adapter id.
 * <p>
 * It also registers the adapter's OVERLAY of the shared <code>vanillabp.*</code> tree
 * ({@link DummyAdapterOverlayProperties}). That is the reference for how an adapter adds
 * its own keys at the usual place of each adapter,
 * <code>vanillabp.adapters.&lt;id&gt;.*</code>.
 */
@AutoConfiguration
@EnableConfigurationProperties(DummyAdapterOverlayProperties.class)
@Import(DummyAdapterBeanRegistrar.class)
public class DummyAdapterProcessServiceConfiguration {

  /**
   * Spring builds this auto-configuration once per application. A test never creates
   * it. Putting the double on the test classpath brings it in.
   */
  public DummyAdapterProcessServiceConfiguration() {
  }

  /**
   * See {@link DummyAdapter#PROPERTY_AT_LEAST_ONCE_DELIVERY}.
   */
  public static final String PROPERTY_AT_LEAST_ONCE_DELIVERY = DummyAdapter.PROPERTY_AT_LEAST_ONCE_DELIVERY;

  /**
   * See {@link DummyAdapter#PROPERTY_READ_AGGREGATE_IN_PHASE_TWO}.
   */
  public static final String PROPERTY_READ_AGGREGATE_IN_PHASE_TWO = DummyAdapter.PROPERTY_READ_AGGREGATE_IN_PHASE_TWO;

}
