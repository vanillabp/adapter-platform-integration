package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.jandex.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.deployment.processservice.AggregatePersistenceResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
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

  /** An interface of the application which has nothing to do with persistence. */
  interface Marker {
  }

  /** An aggregate which no persistence above is written for. */
  static class Unrelated {
  }

  /** Reaches {@link AggregatePersistenceAware} one step up, and first passes an interface which does not. */
  static class SubOfX extends X implements Marker {
  }

  /** Reaches {@link AggregatePersistenceAware} one step up, and has no interface of its own. */
  static class PlainSubOfX extends X {
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

  @Test
  void anInterfaceWithoutThePersistenceTypeDoesNotHideTheStepToTheSuperclass() throws Exception {
    IndexView index = indexOf(
        Z.class,
        Marker.class,
        AggregatePersistenceAware.class,
        X.class,
        SubOfX.class
    );

    DotName z = DotName.createSimple(Z.class.getName());

    assertEquals(0, dist(index, X.class, z));
    assertEquals(1000, dist(index, SubOfX.class, z), "SubOfX is one step further away than X");
  }

  @Test
  void aSubclassOfAPersistenceForAnotherAggregateDoesNotFit() throws Exception {
    IndexView index = indexOf(
        Z.class,
        Unrelated.class,
        Marker.class,
        AggregatePersistenceAware.class,
        X.class,
        SubOfX.class,
        PlainSubOfX.class
    );

    DotName unrelated = DotName.createSimple(Unrelated.class.getName());

    assertEquals(Integer.MAX_VALUE, dist(index, X.class, unrelated));
    assertEquals(Integer.MAX_VALUE, dist(index, SubOfX.class, unrelated));
    assertEquals(Integer.MAX_VALUE, dist(index, PlainSubOfX.class, unrelated));
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
