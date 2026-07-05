# Upgrade notes

Documents changes that were necessary when upgrading major dependency versions,
so the reasoning can be looked up later (e.g. when upgrading BPMS adapters or
applications built on VanillaBP).

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

