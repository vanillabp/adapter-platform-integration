![Header](../readme/vanillabp-headline.png)

# VanillaBP schema

The tables which VanillaBP needs. This module is for applications which create their database
schema themselves and do not let the runtime do it. Nothing in this module is code. It ships a
Liquibase changelog and the SQL generated from it.

## Why an artifact of its own

In some applications, the schema is a reviewed and versioned artifact, and a deployment pipeline
applies it. Such an application switches off the table creation of VanillaBP
(`vanillabp.outbox.create-schema=false`) and needs the statements instead. With a separate artifact,
a schema repository can depend on `io.vanillabp:vanillabp-schema` without pulling in the runtime. A
Liquibase `include` reads the files from the JAR on the classpath.

## What is in it

|                                 Path                                  |                          What it is                          |
|-----------------------------------------------------------------------|--------------------------------------------------------------|
| `vanillabp/schema/changelog.xml`                                      | the master: properties and includes, NO changeset of its own |
| `vanillabp/schema/<version>.xml`                                      | the changesets of a released version, immutable and pinned   |
| `vanillabp/schema/<version>.xml.sha256`                               | the checksum which pins it                                   |
| `vanillabp/schema/latest.xml`                                         | the changesets of the version under development              |
| `vanillabp/schema/flyway/<database>/V<version>__vanillabp_schema.sql` | the same statements as SQL, generated at build time          |

It describes four tables:

- the phase-two outbox (`VANILLABP_PHASE_TWO_OUTBOX`). An outbox keeps calls which run only after
  the database transaction has committed.
- the log of processed task deliveries (`VANILLABP_TASK_DELIVERY`)
- the payloads of those phase-two calls which carry one (`VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD`)
- the claim which says who removes the old rows of a store tonight (`VANILLABP_HOUSEKEEPING`)

All four work for a Spring Boot application and for a Quarkus application in the same way, because
both run the same JDBC store. One table is not described: `TXNO_OUTBOX`, the table of the gruelbox
store in `io.vanillabp:gruelbox-phase-two-outbox`. That schema belongs to gruelbox and its own
migration tool.

Every outbox which stores its data with JDBC needs the payload table, and gruelbox does too. A call
which carries bytes stores them there, and its entry names the row (see decision 62 in
`DECISIONS.md`). If the extensions of an application pass no payload, nothing ever writes a row into
this table, and it stays empty.

Its name is the name of the outbox table plus `_PAYLOAD`. So an application which renames the outbox
with `vanillabp.outbox.jdbc.table` has to rename this table too. To do that, it overrides the
property `vanillabp.payload.table` of the changelog. Both tables belong to one outbox. Two
applications may share one schema and keep apart by the name of the outbox. Then they have to keep
the payloads apart as well.

The housekeeping table has one row for each store. The outbox of VanillaBP writes one. The gruelbox
store writes one too: VanillaBP cleans up its payloads, although the entries belong to the library.
Like the delivery table, the housekeeping table does not follow a renamed outbox, because it belongs
to no single store.

All four names are properties of the changelog. So you can rename a table without a copy of your own
of the changelog. `vanillabp.outbox.table`, `vanillabp.payload.table`, `vanillabp.delivery.table`
and `vanillabp.housekeeping.table` match the runtime keys `vanillabp.outbox.jdbc.table`,
`.payload-table`, `.delivery-table` and `.housekeeping-table`. Set the same name on both sides.
Otherwise the runtime looks for a table which the changelog did not build. The generated Flyway
files have the default names. They are generated while this artifact is built, and at that time
nobody knows your names. So an application which renames a table applies the changelog with
Liquibase, or it edits the generated statements before it applies them. See decision 78 in
`DECISIONS.md`.

## Why the SQL is generated and not written

Liquibase describes a column once and knows how each database writes it. If we wrote the SQL by
hand, we would need our own type mapping for six databases, and nobody here tests four of them. A
guess in a schema artifact is worse than no artifact. So we write the changelog, and
`liquibase:updateSQL` generates the statements for each database. This runs offline. The build does
not connect to any database.

Sometimes a database needs something which the mapping does not give us. Then the changelog says so
directly. Today there is one case. MySQL and MariaDB get `datetime(6)` instead of `timestamp`,
through a `modifySql` block. The reason is that MySQL's `TIMESTAMP` ends in 2038 and fills itself
in automatically. The runtime's own DDL (data definition language, the statements which create
tables) does the same there.

## Which databases

|                Database                 | Shipped |                     Tested here                     |
|-----------------------------------------|---------|-----------------------------------------------------|
| H2                                      | yes     | yes, `ChangelogAppliesTest`                         |
| PostgreSQL                              | yes     | yes, `GeneratedSqlOnPostgresIT` against a container |
| MySQL, MariaDB, SQL Server, Oracle, DB2 | yes     | no                                                  |

The untested ones ship because the Camunda 7 engine supports them. So VanillaBP never supports fewer
databases than the engine. Their files say in their header that they were generated, and the wiki
says which ones a test covers.

Oracle and DB2 count the length of a `VARCHAR` in bytes, the others count characters. So the keys
VanillaBP builds (`IDEMPOTENCY_KEY`, `DEDUP_KEY`, `DELIVERY_KEY`) are measured in bytes of UTF-8,
and a key which is too long is hashed. It fits on every database. `AGGREGATE_ID` holds your ID as
it is, and it is never cut or hashed, because somebody reads it during support. On Oracle and DB2
an ID of up to 1024 characters with characters outside ASCII (an umlaut, an emoji) can be longer
than 1024 bytes, and then the insert fails. If your IDs can be like that, widen the column in a
migration of your own. On Oracle you can also give the column character semantics
(`VARCHAR2(1024 CHAR)`), and on DB2 a column in `CODEUNITS32`.

## Why the versions live in separate files

Once a changeset was applied to somebody's database, it must never change. Otherwise Liquibase
compares the checksums and refuses to run. Repairing the installation from there is manual work in
somebody else's production database. In an open-source project, it is not enough to write that rule
down. So the layout of the files enforces it:

- the master changelog has no changeset at all, only properties and one include for each version,
- a released version sits in its own file with a `.sha256` next to it. `ChangelogRulesTest` fails
  the build when that file no longer matches, when a version file has no checksum, or when a
  changeset shows up in the master,
- all work in progress goes into `latest.xml`, the only file without a checksum.

All files here declare the same `logicalFilePath`. That is why the release can rename `latest.xml`
to its version. Liquibase knows a changeset by that logical path, its id and its author. So the
real file name never reaches a database.

## Releasing, and opening the next version

`schema/bin/schema-version.sh` does both halves, and `.github/workflows/release.yaml` calls it:

```bash
schema/bin/schema-version.sh pin 2.0.0     # latest.xml -> 2.0.0.xml, include updated, checksum written
schema/bin/schema-version.sh open 2.1.0    # a new, empty latest.xml, included again
```

## Adding a change in a later version

- Put it into `latest.xml`. Never edit a released file. The build tells you if you do.
- The changeset id is `vanillabp-<table>-<version>`. If one table gets more than one changeset in a
  version, add a word which names the change. Its `labels` attribute is that version.
- In `pom.xml`, add one `liquibase-maven-plugin` execution for each database for the new version,
  with `labelFilter` set to that version. This way, the Flyway file of a release holds only the
  statements of that release. Flyway applies files, not diffs. This is on purpose a visible change
  which somebody reviews, not a loop over whatever files happen to be there.
- A new table also needs its name in `TABLES_OF_VANILLABP` of `ChangelogAppliesTest`, in this
  README and in the wiki pages of the two platform integrations. The test turns red as soon as the
  changelog describes a table which is not in that set. Its message names the places where you
  write it down. A new column or index needs none of this. The test reads them from the changelog.

## How the build produces the SQL

1. `maven-clean-plugin` deletes the offline state of Liquibase. Otherwise Liquibase would report
   "database is up to date" on the second build and write an empty file,
2. `liquibase-maven-plugin` generates one file per database, filtered by the release label,
3. `maven-antrun-plugin` replaces the two header lines which are not reproducible (the generation
   time and the offline URL, which carries an absolute path),
4. the resources plugin copies everything into the artifact.

So every build generates the SQL again from the changelog. The SQL is not committed, so it cannot
drift away from the changelog. A reviewer reads the changelog.

`ChangelogAppliesTest` applies the changelog to H2. Then it compares the database with what the
changelog describes. It reads the tables, their columns and their indexes from the changelog
itself. So when a changeset adds a column or an index, the test checks it, and nobody has to write
it into the test. The test holds only the table names, because this README and the wiki name them
too. The test does not trust them, it compares them with the changelog. So a release which brings
a fifth table turns the test red, and the new name gets into all three places.
`GeneratedSqlOnPostgresIT#postgresAcceptsTheGeneratedSql` runs the generated statements against a
PostgreSQL container and asks the changelog for the same names.

Both tests compare the changelog with a database which the changelog itself built. That says
nothing about the tables which VanillaBP creates when it starts. An application may use either way
and move from one to the other. So the two ways have to agree. This comparison needs the runtime
and this artifact on one classpath. That is why it lives next to the runtime's own DDL.
`TheRuntimeAndTheChangelogBuildTheSameTablesTest` of `migration-adapter/runtime` builds one
database each way and compares their columns. Both modules read the changelog with the same code,
`ChangelogDescription` of `io.vanillabp:test-utils`.
