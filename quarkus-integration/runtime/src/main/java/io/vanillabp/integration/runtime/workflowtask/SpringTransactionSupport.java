package io.vanillabp.integration.runtime.workflowtask;

/**
 * Whether the extension {@code quarkus-spring-tx} is part of this application. This is
 * decided at build time and registered as a synthetic CDI bean. It tells whether Spring's
 * {@code @Transactional} starts a transaction on Quarkus. The core's startup check of
 * <code>&#64;WorkflowTask</code> methods needs to know that.
 *
 * @param honored <code>true</code> if Spring's annotation is mapped onto JTA
 */
public record SpringTransactionSupport(
                                       boolean honored) {
}
