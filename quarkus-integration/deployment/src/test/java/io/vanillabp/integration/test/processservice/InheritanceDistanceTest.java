package io.vanillabp.integration.test.processservice;


import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.deployment.processservice.AggregatePersistenceResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;


@ExtendWith(SuppressOutputExtension.class)
public class InheritanceDistanceTest {

  interface IA {
  }

  interface IB extends IA {
  }

  interface IC extends IB {
  }

  static class A {
  }

  static class B extends A {
  }

  static class C extends B {
  }

  static class D implements IC {
  }

  static class E extends D {
  }

  interface IX {
  }

  interface IY extends IX {
  }

  static class X implements IY {
  }

  static IndexView index = indexOf(
      IA.class,
      IB.class,
      IC.class,
      A.class,
      B.class,
      C.class,
      D.class,
      E.class,
      IX.class,
      IY.class,
      X.class
  );

  @Test
  void exact_match_is_zero() {
    Assertions.assertEquals(0,
        dist(index, A.class, A.class));
  }

  @Test
  void direct_superclass_distance_is_one() {
    Assertions.assertEquals(1,
        dist(index, A.class, B.class));
  }

  @Test
  void deeper_class_hierarchy() {
    Assertions.assertEquals(2,
        dist(index, A.class, C.class));
  }

  @Test
  void simple_interface_hierarchy() {
    Assertions.assertEquals(1,
        dist(index, IB.class, IC.class));
  }

  @Test
  void deeper_interface_hierarchy() {
    Assertions.assertEquals(2,
        dist(index, IA.class, IC.class));
  }

  @Test
  void class_implements_interface_chain() {
    Assertions.assertEquals(1,
        dist(index, IC.class, D.class));

    Assertions.assertEquals(2,
        dist(index, IB.class, D.class));
  }

  @Test
  void subclass_of_interface_implementor() {
    Assertions.assertEquals(2,
        dist(index, IC.class, E.class));

    Assertions.assertEquals(4,
        dist(index, IA.class, E.class));
  }

  @Test
  void not_assignable_throws_exception() {
    Assertions.assertEquals(Integer.MAX_VALUE,
        dist(index, String.class, A.class));
  }

  @Test
  void mixed_paths_choose_minimal_distance() {
    // Two possible paths:
    // X -> IY -> IX  => 2
    // X -> Object -> ...  => irrelevant
    Assertions.assertEquals(2,
        dist(index, IX.class, X.class));
  }

  static IndexView indexOf(
      Class<?>... classes) {
    Indexer indexer = new Indexer();

    try {
      for (Class<?> clazz : classes) {
        indexer.indexClass(clazz);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return indexer.complete();

  }

  private static int dist(
      IndexView index,
      Class<?> clazz,
      Class<?> aggregateClazz) {

    DotName info = DotName.createSimple(clazz.getName());
    DotName aggregate = DotName.createSimple(aggregateClazz.getName());

    return AggregatePersistenceResolver.inheritanceDistance(index, info, aggregate);
  }

}
