package org.springframework.transaction.annotation;

/**
 * The propagation values of Spring's transaction annotation, in the order the real
 * enum declares them. See {@link Transactional} for why this stand-in exists.
 */
public enum Propagation {

  REQUIRED,
  SUPPORTS,
  MANDATORY,
  REQUIRES_NEW,
  NOT_SUPPORTED,
  NEVER,
  NESTED

}
