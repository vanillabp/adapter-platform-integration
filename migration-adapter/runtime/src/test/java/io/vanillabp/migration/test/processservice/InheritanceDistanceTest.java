package io.vanillabp.migration.test.processservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;

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
        AwareSelection.inheritanceDistance(A.class, A.class));
  }

  @Test
  void direct_superclass_distance_is_one() {
    Assertions.assertEquals(1,
        AwareSelection.inheritanceDistance(A.class, B.class));
  }

  @Test
  void deeper_class_hierarchy() {
    Assertions.assertEquals(2,
        AwareSelection.inheritanceDistance(A.class, C.class));
  }

  @Test
  void simple_interface_hierarchy() {
    Assertions.assertEquals(1,
        AwareSelection.inheritanceDistance(IB.class, IC.class));
  }

  @Test
  void deeper_interface_hierarchy() {
    Assertions.assertEquals(2,
        AwareSelection.inheritanceDistance(IA.class, IC.class));
  }

  @Test
  void class_implements_interface_chain() {
    Assertions.assertEquals(1,
        AwareSelection.inheritanceDistance(IC.class, D.class));

    Assertions.assertEquals(2,
        AwareSelection.inheritanceDistance(IB.class, D.class));
  }

  @Test
  void subclass_of_interface_implementor() {
    Assertions.assertEquals(2,
        AwareSelection.inheritanceDistance(IC.class, E.class));

    Assertions.assertEquals(4,
        AwareSelection.inheritanceDistance(IA.class, E.class));
  }

  @Test
  void not_assignable_throws_exception() {
    Assertions.assertEquals(
        Integer.MAX_VALUE,
        AwareSelection.inheritanceDistance(String.class, A.class));
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
        AwareSelection.inheritanceDistance(IX.class, X.class));
  }

}
