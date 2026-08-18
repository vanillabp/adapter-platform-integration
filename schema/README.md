![Header](../readme/vanillabp-headline.png)

# VanillaBP schema

The tables VanillaBP needs, for applications which create their database schema themselves instead
of letting the runtime do it. Nothing in this module is code: it ships a Liquibase changelog and the
SQL generated from it.

## Why an artifact of its own

An application whose schema is a reviewed, versioned artifact applied by a deployment pipeline
switches VanillaBP's table creation off (`vanillabp.outbox.create-schema=false`) and needs the
statements instead. A separate artifact means a schema repository can depend on
`io.vanillabp:vanillabp-schema` without pulling the runtime, and a Liquibase `include` reaches into
the JAR from the classpath.

## What is in it

|                                 Path                                  |                      What it is                       |
|-----------------------------------------------------------------------|-------------------------------------------------------|
| `vanillabp/schema/changelog.xml`                                      | the changelog, database-agnostic, the source of truth |
| `vanillabp/schema/flyway/<database>/V<version>__vanillabp_schema.sql` | the same statements as SQL, generated at build time   |

Two tables are described: the phase-two outbox (`VANILLABP_PHASE_TWO_OUTBOX`) and the log of
processed task deliveries (`VANILLABP_TASK_DELIVERY`). Not described: `TXNO_OUTBOX` of the Spring
Boot integration - that schema belongs to gruelbox and its own migrator.

## Why the SQL is generated and not written

Liquibase describes a column once and knows how every database spells it. Writing the SQL by hand
would mean writing our own type mapping for six databases, four of which nobody here tests - and a
guess in a schema artifact is worse than no artifact. So the changelog is authored, and
`liquibase:updateSQL` generates the statements per database, offline: no database is contacted while
building.

Where a database needs something the mapping does not give us, the changelog says so explicitly.
The one case today: MySQL and MariaDB get `datetime(6)` instead of `timestamp`, through a
`modifySql` block, because MySQL's `TIMESTAMP` ends in 2038 and auto-initializes - which is also
what the runtime's own DDL does there.

## Which databases

|                Database                 | Shipped |                     Tested here                     |
|-----------------------------------------|---------|-----------------------------------------------------|
| H2                                      | yes     | yes, `ChangelogAppliesTest`                         |
| PostgreSQL                              | yes     | yes, `GeneratedSqlOnPostgresIT` against a container |
| MySQL, MariaDB, SQL Server, Oracle, DB2 | yes     | no                                                  |

The four untested ones ship because the Camunda 7 engine serves them, so VanillaBP is never the
narrower of the two. Their files say in their header that they were generated, and the wiki says
which ones a test covers.

## Adding a change in a later version

- Never edit or renumber a changeset which was released. Append a new one.
- Its id is `vanillabp-<table>-<version>`, its `labels` attribute is that version.
- Add one `liquibase-maven-plugin` execution per database for the new version in `pom.xml`, with
  `labelFilter` set to it. That is what keeps a Flyway file per release holding only that release's
  statements - Flyway applies files, not diffs.

## How the build produces the SQL

1. `maven-clean-plugin` drops the offline state of Liquibase, which would otherwise report "database
   is up to date" on the second build and write an empty file,
2. `liquibase-maven-plugin` generates one file per database, filtered by the release label,
3. `maven-antrun-plugin` replaces the two header lines which are not reproducible (the generation
   time and the offline URL, which carries an absolute path),
4. the resources plugin copies everything into the artifact.

A build therefore always regenerates the SQL from the changelog. The SQL is not committed, so it
cannot drift away from the changelog - the changelog is what a reviewer reads.
