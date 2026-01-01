package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.jandex.*;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.deployment.processservice.AggregatePersistenceResolver;
import io.vanillabp.spi.process.AggregatePersistenceAware;

class AggregatePersistenceResolverTest {

  /* -------------------------------------------------- */
  /* Test types                                         */
  /* -------------------------------------------------- */

  interface Z {
  }

  interface I extends Z {
  }

  static class X implements AggregatePersistenceAware<Z> {
    @Override
    public Class<Z> getAggregateClass() {
      return null;
    }

    @Override
    public Z save(
        Z aggregate) {
      return null;
    }

    @Override
    public Object getAggregateId(
        Z aggregate) {
      return null;
    }
  }

  static class XI implements AggregatePersistenceAware<I> {
    @Override
    public Class<I> getAggregateClass() {
      return null;
    }

    @Override
    public I save(
        I aggregate) {
      return null;
    }

    @Override
    public Object getAggregateId(
        I aggregate) {
      return null;
    }
  }

  @SuppressWarnings("rawtypes")
  static class G implements AggregatePersistenceAware {
    @Override
    public Class getAggregateClass() {
      return null;
    }

    @Override
    public Object save(
        Object aggregate) {
      return null;
    }

    @Override
    public Object getAggregateId(
        Object aggregate) {
      return null;
    }
  }

  /* -------------------------------------------------- */
  /* Test                                               */
  /* -------------------------------------------------- */

  @Test
  void genericSpecificityOrdering1() throws Exception {
    IndexView index = indexOf(
        Z.class,
        I.class,
        AggregatePersistenceAware.class,
        X.class,
        XI.class,
        G.class
    );

    DotName z = DotName.createSimple(Z.class.getName());

    int dx = dist(index, X.class, z);
    int dxi = dist(index, XI.class, z);
    int dg = dist(index, G.class, z);

    assertTrue(dx < dxi, "X must be more specific than XI");
    assertTrue(dxi < dg, "XI must be more specific than G");
  }

  @Test
  void genericSpecificityOrdering2() throws Exception {
    IndexView index = indexOf(
        Z.class,
        I.class,
        AggregatePersistenceAware.class,
        X.class,
        XI.class,
        G.class
    );

    DotName i = DotName.createSimple(I.class.getName());

    int dx = dist(index, X.class, i);
    int dxi = dist(index, XI.class, i);
    int dg = dist(index, G.class, i);

    assertTrue(dxi < dx, "XI must be more specific than X");
    assertTrue(dxi < dg, "XI must be more specific than G");
  }

  /* -------------------------------------------------- */
  /* Helpers                                            */
  /* -------------------------------------------------- */

  private static int dist(
      IndexView index,
      Class<?> clazz,
      DotName aggregate) {

    ClassInfo info = index.getClassByName(DotName.createSimple(clazz.getName()));

    return AggregatePersistenceResolver.distance(index, info, aggregate);
  }

  private static IndexView indexOf(
      Class<?>... classes) throws Exception {
    Indexer indexer = new Indexer();

    for (Class<?> c : classes) {
      indexer.indexClass(c);
    }

    return indexer.complete();
  }
}
