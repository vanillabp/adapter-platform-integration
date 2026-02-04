package io.vanillabp.integration.test.processservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.processservice.AggregatePersistenceResolver;

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

  @Test
  void exact_match_is_zero() {
    Assertions.assertEquals(0,
        AggregatePersistenceResolver.inheritanceDistance(A.class, A.class));
  }

  @Test
  void direct_superclass_distance_is_one() {
    Assertions.assertEquals(1,
        AggregatePersistenceResolver.inheritanceDistance(A.class, B.class));
  }

  @Test
  void deeper_class_hierarchy() {
    Assertions.assertEquals(2,
        AggregatePersistenceResolver.inheritanceDistance(A.class, C.class));
  }

  @Test
  void simple_interface_hierarchy() {
    Assertions.assertEquals(1,
        AggregatePersistenceResolver.inheritanceDistance(IB.class, IC.class));
  }

  @Test
  void deeper_interface_hierarchy() {
    Assertions.assertEquals(2,
        AggregatePersistenceResolver.inheritanceDistance(IA.class, IC.class));
  }

  @Test
  void class_implements_interface_chain() {
    Assertions.assertEquals(1,
        AggregatePersistenceResolver.inheritanceDistance(IC.class, D.class));

    Assertions.assertEquals(2,
        AggregatePersistenceResolver.inheritanceDistance(IB.class, D.class));
  }

  @Test
  void subclass_of_interface_implementor() {
    Assertions.assertEquals(2,
        AggregatePersistenceResolver.inheritanceDistance(IC.class, E.class));

    Assertions.assertEquals(4,
        AggregatePersistenceResolver.inheritanceDistance(IA.class, E.class));
  }

  @Test
  void not_assignable_throws_exception() {
    Assertions.assertEquals(
        Integer.MAX_VALUE,
        AggregatePersistenceResolver.inheritanceDistance(String.class, A.class));
  }

  @Test
  void mixed_paths_choose_minimal_distance() {
    interface IX {
    }
    interface IY extends IX {
    }
    class X implements IY {
    }

    // Two possible paths:
    // X -> IY -> IX  => 2
    // X -> Object -> ...  => irrelevant
    Assertions.assertEquals(2,
        AggregatePersistenceResolver.inheritanceDistance(IX.class, X.class));
  }

}
