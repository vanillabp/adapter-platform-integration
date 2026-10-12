package org.springframework.transaction.annotation;

/**
 * The propagation values of the transaction annotation of Spring, in the order in which the
 * real enum declares them. See {@link Transactional} for why this stand-in exists.
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
