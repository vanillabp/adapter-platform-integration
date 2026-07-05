package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.vanillabp.integration.processservice.AggregatePersistenceResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;

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
  void genericSpecificityOrdering1() {
    int dx = AggregatePersistenceResolver.distance(X.class, Z.class);
    int dxi = AggregatePersistenceResolver.distance(XI.class, Z.class);
    int dg = AggregatePersistenceResolver.distance(G.class, Z.class);

    assertTrue(dx < dxi, "X must be more specific than XI");
    assertTrue(dxi < dg, "XI must be more specific than G");
  }

  @Test
  void genericSpecificityOrdering2() {
    int dx = AggregatePersistenceResolver.distance(X.class, I.class);
    int dxi = AggregatePersistenceResolver.distance(XI.class, I.class);
    int dg = AggregatePersistenceResolver.distance(G.class, I.class);

    assertTrue(dxi < dx, "XI must be more specific than X");
    assertTrue(dxi < dg, "XI must be more specific than G");
  }

}
