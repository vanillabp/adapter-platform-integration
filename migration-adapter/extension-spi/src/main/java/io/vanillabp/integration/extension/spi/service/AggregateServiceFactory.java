package io.vanillabp.integration.extension.spi.service;

/**
 * Lets an extension offer a service of its own, the way VanillaBP offers
 * {@code ProcessService<A>}: one bean per workflow-aggregate class, which you inject with
 * the aggregate as its type argument
 * (<code>&#64;Inject BusinessCockpitService&lt;Loan&gt;</code>).
 * <p>
 * An extension adds the factory as a bean. On Spring Boot it is an ordinary bean, on
 * Quarkus its own extension produces it. VanillaBP then creates one service per
 * workflow-aggregate class of the application. It uses the same set of classes the
 * {@code ProcessService} beans are built for: the aggregates of the scanned
 * <code>&#64;WorkflowService</code> classes.
 * <p>
 * <b>Injecting it is optional.</b> Unlike for {@code ProcessService}, nothing requires the
 * injection point. If an application never asks for the service, none is built.
 *
 * @param <S> The service interface the extension offers, e.g.
 *          {@code BusinessCockpitService}
 */
public interface AggregateServiceFactory<S> {

  /**
   * The interface the application injects. Its only type argument has to be the workflow
   * aggregate, because that is what the injection point names.
   *
   * @return The service interface
   */
  Class<S> getServiceInterface();

  /**
   * Builds the service of one workflow-aggregate class. It is called once per aggregate,
   * when the application injects the service for the first time.
   *
   * @param context The aggregate, its workflow and what a service usually needs
   * @return The service
   */
  S createService(
      AggregateServiceContext context);

}
