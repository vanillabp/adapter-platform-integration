package jakarta.ejb;

/**
 * The values of {@link TransactionAttribute}, in the order the real enum declares
 * them.
 */
public enum TransactionAttributeType {

  MANDATORY,
  REQUIRED,
  REQUIRES_NEW,
  SUPPORTS,
  NOT_SUPPORTED,
  NEVER

}
