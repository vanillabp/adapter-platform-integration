# What was found while building, and is not the story which found it

Until 2.0 is out a find does not become a roadmap line unless it holds the release up. It becomes a
paragraph here, with its numbers, and it becomes a line when somebody works on that area again. The
measurement is kept, the release list does not grow.

## A class-level javadoc may not carry `<h4>`, and nothing says so

Found on 2026-10-03 while three types were added. The javadoc gate of this repository refuses
`heading used out of sequence: <H4>, compared to implicit preceding heading: <H1>` for a `<h4>` in
the javadoc of a TYPE, while the same heading in the javadoc of a METHOD passes - that is where
`TaskDeliveryLog#recordOfTask` and `MigrationProcessService#reportOpenTasksNobodyRemembers` use it.
The reason is the level javadoc renders each block at, and it is invisible until the compiler says
it.

The habit of the repository already matches: a type uses `<strong>What this costs</strong>` and a
method uses `<h4>`. Nothing writes that down, so the next type with three sections fails the same
build. One sentence in `CONTRIBUTING.md` next to the javadoc rules would end it.

## A changed `additional-spring-configuration-metadata.json` is only picked up by a compile

Found on 2026-10-03 in `spring-boot-integration/runtime`. `EveryKeyOfASectionIsDescribedTest` reads
the GENERATED `META-INF/spring-configuration-metadata.json`, which the annotation processor
produces and the additional file is merged into. The processor runs with the compiler, so a build
which only changed the JSON recompiles nothing, the merged file keeps the old content and the test
fails although the key is described. It passes after any main source is touched.

It cost one confused build. The test cannot tell the two cases apart from where it stands, so what
would help is its own failure message saying it: if the key was just added, recompile the main
sources, because the merged file is generated. `EveryPropertyIsOfferedByTheIdeTest` next to it reads
the additional file directly and went green immediately, which is what made the pair look
contradictory.

## The test-utils reader of the delivery log counted every row

Found on 2026-10-03 while a second kind of row was added to that table.
`TaskDeliveryLogReader#deliveries()` read the whole table, so a row which is not a delivery would
have appeared in the counts of every test which asserts how many deliveries its handlers produced -
in this repository, in the three adapter repositories and in the blueprints, none of which this
build compiles. The reader filters on the kind now and offers `workflowStarts()` beside it.

What is left of the find is the shape: a reader which answers "everything in the table" ages badly
the moment the table carries something else. The two outbox readers next to it are worth the same
look before anything is added to those tables.

## `bin/check-decision-citations.sh` reports itself in this development container, and not in CI

Found on 2026-10-03, and it is an environment artifact rather than a break: the script leaves itself
out by comparing `readlink -f` of each candidate against the path it computed for itself, and that
path is computed with `$(cd "$(dirname "$0")" && pwd)`. Where `CDPATH` is set - it is, in this
container - `cd` prints the directory it changed to, so the path comes out as two lines and matches
nothing. The script then reads its own self-test fixtures as citations and ends with 1. A pristine
`origin/main` does it too, and a GitHub runner, where `CDPATH` is unset, does not.

So a red run of this script on a developer machine says nothing until `CDPATH= bin/...` says the
same. `cd -- "$(dirname "$0")"` with `CDPATH=` in front of it would end the difference, and the
other scripts under `bin` which compute a path the same way are worth the same look.
