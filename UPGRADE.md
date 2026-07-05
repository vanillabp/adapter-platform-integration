# Upgrade notes

Documents changes that were necessary when upgrading major dependency versions,
so the reasoning can be looked up later (e.g. when upgrading BPMS adapters or
applications built on VanillaBP).

## Quarkus 3.26.4 → 3.37.1 (2026-07-05)

Version bump in `quarkus-integration/pom.xml` (`quarkus.version`). One real change was
required:

### Config root phase changed to RUN_TIME

`QuarkusMigrationAdapterProperties` was declared as
`@ConfigRoot(phase = BUILD_AND_RUN_TIME_FIXED)`. Values of such config roots are read
at **build time** and frozen. The workflow-module-specific config files
(`<module-id>.properties/.yaml`) are added by generated config builders to the
static-init/runtime config only — they are never part of the build-time
configuration.

Up to Quarkus 3.26 this worked **by accident**: the `@ConfigMapping` instance was
re-populated at static init against the full config (including the module config
sources). Since the Quarkus config optimization that introduced the generated
`SharedConfig` class (mapping instances are created once from build-time values and
reused in static-init and runtime config via `withMappingInstance`), the mapping's
`workflowModules()` map stayed empty at runtime, and all `QuarkusProdModeTest`s that
actually launch the application failed with:

```
IllegalStateException: No workflow-modules configured! Add properties sections
'vanillabp.workflow-modules.<id>' ...
```

Fix (also semantically the right choice, because VanillaBP configuration such as
adapter endpoints must be overridable per environment, e.g. via environment
variables):

- `QuarkusMigrationAdapterProperties`: `ConfigPhase.BUILD_AND_RUN_TIME_FIXED` →
  `ConfigPhase.RUN_TIME`
- `ConfigBuildStepProcessor.buildMigrationAdapterProperties`:
  `@Record(ExecutionTime.STATIC_INIT)` → `@Record(ExecutionTime.RUNTIME_INIT)`
  (the synthetic `MigrationAdapterProperties` bean was already `setRuntimeInit()`)

Diagnosis hint for similar problems: decompile
`io/quarkus/runtime/generated/StaticInitConfig*.class` and `SharedConfig.class` from
the `generated-bytecode.jar` of a prod-mode build — they show which config builders,
sources and mapping instances are actually wired.

## Spring Boot 3.5.x → 4.1.0 (2026-07-05)

Spring Boot 4 modularized the formerly monolithic `spring-boot-autoconfigure` and
`spring-boot-test-autoconfigure` artifacts: technology-specific auto-configurations and
test slices now live in their own modules with new package names. The starters
(`spring-boot-starter-data-jpa`, `spring-boot-starter-data-mongodb`,
`spring-boot-starter-test`) kept their names and pull the new modules in transitively —
**except test slices**, which now require an explicit dependency.

### Version bumps

- `spring-boot-integration/pom.xml`: `spring-boot-dependencies` BOM 3.5.5 → 4.1.0.
- `test-utils/pom.xml` (plain-Java module with optional Spring deps, no BOM):
  `spring-beans`/`spring-context` 6.2.14 → 7.0.8 (Spring Framework 7),
  `spring-boot`/`spring-boot-test` 3.5.5 → 4.1.0.

### Moved classes (import changes)

|                 Class                  |                Old package (Boot 3.x)                 |                 New package (Boot 4.x)                 |         New module          |
|----------------------------------------|-------------------------------------------------------|--------------------------------------------------------|-----------------------------|
| `@EntityScan`                          | `org.springframework.boot.autoconfigure.domain`       | `org.springframework.boot.persistence.autoconfigure`   | `spring-boot-persistence`   |
| `HibernateJpaAutoConfiguration`        | `org.springframework.boot.autoconfigure.orm.jpa`      | `org.springframework.boot.hibernate.autoconfigure`     | `spring-boot-hibernate`     |
| `MongoClientSettingsBuilderCustomizer` | `org.springframework.boot.autoconfigure.mongo`        | `org.springframework.boot.mongodb.autoconfigure`       | `spring-boot-mongodb`       |
| `@DataJpaTest`                         | `org.springframework.boot.test.autoconfigure.orm.jpa` | `org.springframework.boot.data.jpa.test.autoconfigure` | `spring-boot-data-jpa-test` |

Affected files (test code only — main code was not affected):

- `spring-boot-integration/runtime/src/test/.../JpaSpringDataUtilTest.java`
- `spring-boot-integration/runtime/src/test/.../MongoDbSpringDataUtilTest.java`
- `spring-boot-integration/integration-tests/main-integration-test/src/test/.../AdapterConfigurationTest.java`

### New dependency required

Test slices are no longer part of `spring-boot-starter-test`. For `@DataJpaTest` the
artifact `org.springframework.boot:spring-boot-data-jpa-test` (scope `test`) was added
to `spring-boot-integration/runtime/pom.xml`. (`@DataMongoTest` would analogously
require `spring-boot-data-mongodb-test` — not needed so far.)

### Not affected

- `SslAutoConfiguration` stayed in `spring-boot-autoconfigure`.
- Core auto-configuration mechanics (`@AutoConfiguration`, `@ConditionalOnClass`,
  `AutoConfiguration.imports`, `EnvironmentPostProcessor` via `spring.factories`) —
  unchanged, no code changes needed.
- Bean-definition registration (`BeanDefinitionRegistryPostProcessor`,
  `ResolvableType`-based generic `ProcessService` beans) — unchanged.
- JPA/Hibernate usage (`PersistenceUnitUtil`, `Hibernate.unproxy`) under Hibernate 7 —
  unchanged.
- Spring Boot 4.1.0 manages JUnit Jupiter **6.0.x** and Mockito 5.23; tests kept
  running without changes (root `mockito.version` is upgraded separately).

