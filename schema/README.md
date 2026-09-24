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

|                                 Path                                  |                          What it is                          |
|-----------------------------------------------------------------------|--------------------------------------------------------------|
| `vanillabp/schema/changelog.xml`                                      | the master: properties and includes, NO changeset of its own |
| `vanillabp/schema/<version>.xml`                                      | the changesets of a released version, immutable and pinned   |
| `vanillabp/schema/<version>.xml.sha256`                               | the checksum which pins it                                   |
| `vanillabp/schema/latest.xml`                                         | the changesets of the version under development              |
| `vanillabp/schema/flyway/<database>/V<version>__vanillabp_schema.sql` | the same statements as SQL, generated at build time          |

Three tables are described: the phase-two outbox (`VANILLABP_PHASE_TWO_OUTBOX`), the log of
processed task deliveries (`VANILLABP_TASK_DELIVERY`) and the payloads of the phase-two calls which
carry one (`VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD`). All three serve a Spring Boot and a Quarkus
application alike, because both run the same JDBC store. Not described: `TXNO_OUTBOX`, the table of
the gruelbox store a Spring Boot application can still opt into
(`vanillabp.outbox.gruelbox.enabled`) - that schema belongs to gruelbox and its own migrator.

The payload table is needed by every JDBC-backed outbox, gruelbox included: a call which carries
bytes stores them there and its entry names the row (see decision 62 in `DECISIONS.md`). An
application whose extensions pass no payload never writes a row into it, and the table stays empty.

Its name is the name of the outbox table plus `_PAYLOAD`. An application which renames the outbox
with `vanillabp.outbox.jdbc.table` therefore has to rename this table too, by overriding the
property `vanillabp.payload.table` of the changelog. Both tables belong to one outbox, and two
applications which separate themselves on one schema by the outbox name have to separate the
payloads as well.

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

## Why the versions live in separate files

A changeset which was applied to somebody's database must never change afterwards: Liquibase
compares checksums and refuses to run, and getting an installation back from there is manual work in
someone else's production database. Writing that rule down is not enough in an open-source project,
so the layout enforces it:

- the master changelog carries no changeset at all, only properties and one include per version,
- a released version sits in its own file with a `.sha256` next to it, and `ChangelogRulesTest` fails
  the build when that file no longer matches, when a version file has no checksum, or when a
  changeset appears in the master,
- everything under development goes into `latest.xml`, the only file without a checksum.

All files here declare the same `logicalFilePath`, which is why the release can rename `latest.xml`
to its version: Liquibase identifies a changeset by that logical path, its id and its author, so the
physical file name never reaches a database.

## Releasing, and opening the next version

`schema/bin/schema-version.sh` does both halves and `.github/workflows/release.yaml` calls it:

```bash
schema/bin/schema-version.sh pin 2.0.0     # latest.xml -> 2.0.0.xml, include updated, checksum written
schema/bin/schema-version.sh open 2.1.0    # a new, empty latest.xml, included again
```

## Adding a change in a later version

- Put it into `latest.xml`. Never edit a released file - the build says so if you do.
- The changeset id is `vanillabp-<table>-<version>`, plus a word naming the change where one
  table gets more than one changeset in a version. Its `labels` attribute is that version.
- Add one `liquibase-maven-plugin` execution per database for the new version in `pom.xml`, with
  `labelFilter` set to it. That is what keeps a Flyway file per release holding only that release's
  statements - Flyway applies files, not diffs - and it is deliberately a visible, reviewed change
  rather than a loop over whatever happens to lie around.
- A new table also needs its name in `TABLES_OF_VANILLABP` of `ChangelogAppliesTest`, in this
  README and in the wiki pages of the two platform integrations. The test goes red the moment the
  changelog describes a table that set does not, and its message names the places to write it
  down. A new column or index needs none of that - the test reads those from the changelog.

## How the build produces the SQL

1. `maven-clean-plugin` drops the offline state of Liquibase, which would otherwise report "database
   is up to date" on the second build and write an empty file,
2. `liquibase-maven-plugin` generates one file per database, filtered by the release label,
3. `maven-antrun-plugin` replaces the two header lines which are not reproducible (the generation
   time and the offline URL, which carries an absolute path),
4. the resources plugin copies everything into the artifact.

A build therefore always regenerates the SQL from the changelog. The SQL is not committed, so it
cannot drift away from the changelog - the changelog is what a reviewer reads.

`ChangelogAppliesTest` applies the changelog to H2 and then compares the database with what the
changelog describes. It reads the tables, their columns and their indexes from the changelog
itself, so a changeset which adds a column or an index is checked without anybody writing it into
the test. The table names are the one thing the test does hold, because this README and the wiki
name them too. They are compared against the changelog rather than trusted, so a release which
brings a fourth table turns the test red and the new name reaches all three places.
`GeneratedSqlOnPostgresIT#postgresAcceptsTheGeneratedSql` runs the generated statements against a
PostgreSQL container and asks the changelog for the same names.

Both tests hold the changelog against a database the changelog itself built, which says nothing
about the tables VanillaBP creates while it starts. An application may use either way and move
from one to the other, so the two have to agree. That comparison needs the runtime and this
artifact on one classpath, which is why it lives next to the runtime's own DDL:
`TheRuntimeAndTheChangelogBuildTheSameTablesTest` of `migration-adapter/runtime` builds one
database each way and holds the columns of the one against the other. Both modules read the
changelog with the same code, `ChangelogDescription` of `io.vanillabp:test-utils`.
