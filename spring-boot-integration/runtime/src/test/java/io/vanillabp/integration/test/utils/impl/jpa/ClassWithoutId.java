package io.vanillabp.integration.test.utils.impl.jpa;

import lombok.Getter;
import lombok.Setter;

/**
 * An aggregate which names no ID at all. It is not a JPA entity, because Hibernate
 * refuses such a mapping at startup and the message under test is the one VanillaBP
 * writes for an application which wires its persistence itself.
 */
@Getter
@Setter
public class ClassWithoutId {

  private String entityValue;

}
