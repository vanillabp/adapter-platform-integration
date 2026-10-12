package io.vanillabp.integration.adapter.spi;

/**
 * The system one configured adapter id works on, as far as it decides whether two ids are
 * really two systems. The core compares the {@link #key()} of every configured id, across all
 * adapter types. Two ids with the same key work on the same system. That is refused at
 * startup, whatever their types are.
 * <p>
 * Ids of one type are compared by their adapter first
 * ({@link AdapterDeploymentService#validateDistinctAdapterInstances(java.util.List)}). The
 * key is what catches two DIFFERENT types on one system. An example: an embedded engine of
 * Camunda 7 and one of a Camunda 7 fork on the same database tables. Each job executor would
 * take the jobs of the other one, and nothing fails at startup.
 * <p>
 * Adapters which can share a system have to build the key the same way. So the key names the
 * kind of system and the values which address it, and no adapter type. For example, the
 * Camunda 7 family builds it from the datasource and the table prefix.
 *
 * @param key What two ids are compared by. Equal keys mean the same system
 * @param description The system in words, for the message which refuses two ids. For
 *        example: <code>datasource 'legacy', table prefix 'OLD_'</code>
 * @param howToSeparate What the developer configures so that each id gets a system of its
 *        own, with the property keys. Use <code>&lt;id&gt;</code> where the adapter id goes
 */
public record AddressedSystem(
                              String key,
                              String description,
                              String howToSeparate) {
}
