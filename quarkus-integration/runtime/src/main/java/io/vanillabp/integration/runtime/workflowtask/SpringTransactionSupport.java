package io.vanillabp.integration.runtime.workflowtask;

/**
 * Whether the extension {@code quarkus-spring-tx} is part of this application, decided at
 * build time and registered as a synthetic CDI bean. It is the answer to whether Spring's
 * {@code @Transactional} creates a transaction boundary on Quarkus, which the core's
 * startup check of <code>&#64;WorkflowTask</code> methods needs to know (story 40b).
 *
 * @param honored <code>true</code> if Spring's annotation is mapped onto JTA
 */
public record SpringTransactionSupport(
                                       boolean honored) {
}
