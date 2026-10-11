# What was found while building, and is not the story which found it

Until 2.0 is out, a find becomes a roadmap line only if it holds up the release. Otherwise it
becomes a paragraph here, with its numbers. It becomes a line when somebody works on that area
again. So we keep the measurement, and the release list does not grow.

## A class-level javadoc may not carry `<h4>`, and nothing says so

Found on 2026-10-03 while three types were added. The javadoc gate of this repository rejects a
`<h4>` in the javadoc of a TYPE with
`heading used out of sequence: <H4>, compared to implicit preceding heading: <H1>`. The same
heading in the javadoc of a METHOD passes. `TaskDeliveryLog#recordOfTask` and
`MigrationProcessService#reportOpenTasksNobodyRemembers` use it there. The reason is the heading
level at which javadoc renders each block. You cannot see this until the compiler tells you.

The repository already does it the right way: a type uses `<strong>What this costs</strong>`, and a
method uses `<h4>`. But nothing writes this rule down. So the next type with three sections fails
the build in the same way. One sentence in `CONTRIBUTING.md` next to the javadoc rules would fix
that.

## A changed `additional-spring-configuration-metadata.json` is only picked up by a compile

Found on 2026-10-03 in `spring-boot-integration/runtime`. `EveryKeyOfASectionIsDescribedTest` reads
the GENERATED file `META-INF/spring-configuration-metadata.json`. The annotation processor produces
this file and merges the additional file into it. The processor runs with the compiler. So when a
build changed only the JSON file, nothing is compiled again. The merged file keeps the old content,
and the test fails although the key is described. It passes as soon as any main source changes.

This cost one confusing build. From where it runs, the test cannot tell the two cases apart. What
would help is a failure message which says it: if you just added the key, compile the main sources
again, because the merged file is generated. `EveryPropertyIsOfferedByTheIdeTest` next to it reads
the additional file directly and was green at once. That is why the two tests looked as if they
contradicted each other.

## The test-utils reader of the delivery log counted every row

Found on 2026-10-03 while a second kind of row was added to that table.
`TaskDeliveryLogReader#deliveries()` read the whole table. So a row which is not a delivery would
have shown up in the counts of every test which checks how many deliveries its handlers produced.
Those tests are in this repository, in the three adapter repositories and in the blueprints, and
this build compiles none of the others. The reader now filters on the kind of row and offers
`workflowStarts()` next to it.

What is left of the find is the pattern. A reader which answers "everything in the table" breaks as
soon as the table holds something else. The two outbox readers next to it deserve the same check
before anything is added to their tables.

## `bin/check-decision-citations.sh` reports itself in this development container, and not in CI

Found on 2026-10-03. It is a side effect of the environment, not a real break. The script leaves
itself out by comparing `readlink -f` of each candidate with the path it computed for itself. It
computes that path with `$(cd "$(dirname "$0")" && pwd)`. If `CDPATH` is set, `cd` prints the
directory it changed to. `CDPATH` is set in this container. So the path comes out as two lines and
matches nothing. The script then reads its own self-test fixtures as citations and ends with 1. A
clean `origin/main` does the same. A GitHub runner does not, because `CDPATH` is not set there.

So a red run of this script on a developer machine means nothing until `CDPATH= bin/...` gives the
same result. `cd -- "$(dirname "$0")"` with `CDPATH=` in front of it would remove the difference.
The other scripts under `bin` which compute a path the same way deserve the same check.
