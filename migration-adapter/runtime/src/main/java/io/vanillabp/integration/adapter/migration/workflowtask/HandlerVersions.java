package io.vanillabp.integration.adapter.migration.workflowtask;

/**
 * What one annotated handler method of one BPMN process is worth to the startup check for
 * old process versions: whether it serves any version of that process worth serving, and
 * how to name it if it serves none.
 * <p>
 * The method's identity travels with the verdict. The same method is registered for every
 * BPMN process its class declares, with a version range per process. A workflow module whose
 * process was RENAMED needs that. The methods are registered for the new id and for the old
 * one. A method which serves the versions of either does its job. So "this method never
 * runs" is a statement about the whole workflow module, and it cannot be made per process.
 *
 * @param method The method's identity, the same for every BPMN process for which it is
 *          registered
 * @param description The method with its version range and where that range came from,
 *          ready to be put into a message
 * @param servesAVersion Whether it serves a version of that BPMN process worth serving
 */
public record HandlerVersions(
                              String method,
                              String description,
                              boolean servesAVersion) {
}
