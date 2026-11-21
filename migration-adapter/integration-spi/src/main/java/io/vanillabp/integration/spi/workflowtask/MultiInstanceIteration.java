package io.vanillabp.integration.spi.workflowtask;

/**
 * One iteration of a multi-instance element which a task runs in, as a BPMS reports it.
 * <p>
 * A BPMS adapter answers it as <code>MultiInstanceValue</code> of the adapter SPI, which
 * implements this interface. An extension which receives such an answer, for example from
 * an API of a BPMS adapter, reads it through this interface. It never names
 * <code>MultiInstanceValue</code>. So a parameter of an extension is written as
 * <code>Map&lt;String, ? extends MultiInstanceIteration&gt;</code>.
 */
public interface MultiInstanceIteration {

  /**
   * The current element of the iteration. A <code>&#64;MultiInstanceElement</code>
   * parameter gets it.
   *
   * @return The element, or <code>null</code> where the model names none
   */
  Object element();

  /**
   * The current index of the iteration. A <code>&#64;MultiInstanceIndex</code> parameter
   * gets it.
   *
   * @return The index
   */
  int index();

  /**
   * The number of iterations. A <code>&#64;MultiInstanceTotal</code> parameter gets it.
   *
   * @return The number of iterations
   */
  int total();

}
