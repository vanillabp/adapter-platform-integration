![Header](../readme/vanillabp-headline.png)

# The weekly load run

Once a week, the platform runs a fixed amount of work against the current commit of `main` and
against a baseline. It keeps the numbers, draws a trend, and opens an issue when something moved.
This page says what the run measures, what it does not prove, how to run it on your own machine,
and what to look at when it turns red.

- [What it measures](#what-it-measures)
- [What it does not prove](#what-it-does-not-prove)
- [The parts](#the-parts)
- [Running it yourself](#running-it-yourself)
- [When it turns red](#when-it-turns-red)
- [What the weekly workflow needs](#what-the-weekly-workflow-needs)

## What it measures

Every workflow of the fixed work starts, runs three service tasks, runs one task which the
application completes later, and ends with one message correlation. That is enough to touch every
kind of phase-two operation. The BPMS is the [double](../bpms-double), so the run measures what
VanillaBP costs, and not what a BPMS costs.

The double never delivers work on its own. The harness closes that loop: on every phase-two call it
schedules what the BPMS would do next. So the whole chain runs, from the commit through the outbox
row, the poll, the dispatch, the delivery and the delivery record to the next row. The work runs on
four slots, like the Camunda 8 adapter by default, with a bounded queue in front of them.

One round runs in a fresh JVM and does three things:

1. A small work of 20 workflows. It warms the JVM up, and it counts the database statements of
   one start, one task delivery, one completion and one correlation.
2. A work of 100 workflows. After it, the round reads the live set: the heap in use after a forced
   garbage collection.
3. A work of 1000 workflows, which is timed. After it, the round reads the live set again.

The run keeps these numbers, all of them in the record:

|                             Number                             |                 What turns it red                  |
|----------------------------------------------------------------|----------------------------------------------------|
| statements per start, task, completion and correlation         | any change, at once                                |
| time of the whole work, and wait of an outbox entry            | more than 10 percent slower, in two weeks in a row |
| age of the oldest waiting entry, connections in use and waited | nothing, they are kept for reading                 |
| live set per workflow between the two sizes, share of GC time  | nothing, they are kept for reading                 |
| discarded and blocked outbox entries, concurrent redeliveries  | anything but zero in the candidate                 |
| failed deliveries, and workflows which did not finish          | anything but zero in the candidate                 |

A statement count is the number the run can fail on, because it does not move with the speed of the
runner. A time is judged by the ratio of two medians: the median of the candidate's rounds divided
by the median of the baseline's rounds. Both sides run in the same job, on the same machine, with
their rounds alternating. So whatever the machine does to one side, it does to the other.

Why medians and two weeks: on 2026-09-20, in a development container which ran other builds, the
same work ran nine times in each of two JVMs. It spread by about half between its fastest and its
slowest round. The median of those rounds moved by
five percent and by one percent. A graph of single runs would show a slowdown every few weeks which
nobody caused. One week beyond the threshold is therefore a note in the record and not an alarm.

The live set says whether something stays behind. The difference between the two readings, divided
by the workflows in between, is what one workflow leaves behind. It has to be close to nothing once
the work is done. A slope which is not flat is a leak.

## What it does not prove

The run speaks in factors, never in milliseconds. A factor of 1.0 means that the candidate costs
what the baseline cost, on the same machine and in the same hour. It does not say how fast an
application will be, because the handlers of the harness do nothing and the BPMS answers at once.
Absolute times are in the record, and no graph that the README links shows them. They show the
weather of the runner, and somebody would read the good weeks as a promise.

The run does not measure requests per second of an application endpoint, which is the application's
number. It does not measure the CPU of the runner. And it does not measure the time of a handler,
because the handlers of the harness do nothing.

## The parts

- [`load-harness`](../load-harness) is published as `io.vanillabp:load-harness`. It holds the
  driver, the statement counter, the round and the record. An adapter repository uses the same
  artifact to run the same work against its own BPMS, so no two copies drift apart.
- `load-test/spring-boot` and `load-test/quarkus` are the two applications. Each is a few lines
  which reach its platform. They are built only with the Maven profile `load-test`, so a normal
  build and the pull-request checks do not pay for them.
- [`bin/load-run.sh`](../bin/load-run.sh) builds both sides, starts one PostgreSQL in Docker, runs
  the rounds and appends one row per platform to the record.
- [`bin/render-load-graph`](../bin/render-load-graph) draws the trend of one platform as an SVG
  file. It needs a JDK and nothing else.
- The record is one CSV file per platform in the wiki, under `load/`, next to its graph. The page
  [Load measurement](https://github.com/vanillabp/adapter-platform-integration/wiki/Load-measurement)
  shows the graphs. `wiki/Load-measurement.md` in this directory is the first version of that page,
  which the workflow writes once.

The rounds run on PostgreSQL, not on H2. H2 answers in microseconds and never lets a thread wait
for a connection, so an empty pool would never show. A statement is a statement on both, so the
counts would be the same on H2. The smoke test of each application runs a tiny round on H2, which
proves the wiring in the build and measures nothing.

## Running it yourself

You need a JDK 21 or newer, Docker and git. From the root of this repository:

```bash
bin/load-run.sh --baseline origin/main --record-dir target/load-record
```

This builds the current checkout and `origin/main` into two Maven repositories below
`target/load-run`, so your own `~/.m2` stays untouched. Then it runs five rounds per side and
platform, and writes `target/load-record/spring-boot.csv`, `quarkus.csv` and their graphs. Expect a
quarter of an hour. `--platforms spring-boot` runs one platform only. `--workflows 20` makes every
work smaller, which is enough to see whether a statement count moved.

To run a single round of one application against a database of your own:

```bash
./mvnw -Pload-test -pl load-harness,load-test,load-test/spring-boot -am install -DskipTests
LOAD_JDBC_URL=jdbc:counting:postgresql://localhost:5432/load LOAD_JDBC_USER=postgres LOAD_JDBC_PASSWORD=secret \
  java -jar load-test/spring-boot/target/load-test-spring-boot.jar --load.output=round.properties
```

The Quarkus application takes the same arguments: `java -jar
load-test/quarkus/target/quarkus-app/quarkus-run.jar --load.output=round.properties`. The keys
of a round, `load.workflows`, `load.slots`, `load.phase-two-delay` and the others, are described in
`RoundSettings` of the harness. `load.phase-two-delay=PT0.005S` asks what a slow BPMS makes of the
same work. `LOAD_AT_LEAST_ONCE=false` turns off the delivery record, which the weekly run keeps on.

## When it turns red

The issue names the platform and the reasons, and links the run. The rounds and the verdict are an
artifact of that run.

- **A statement count moved.** Somebody added or removed a statement in one of the four operations.
  Run the same commit and its parent with `--workflows 20` and compare the counts. If the change is
  wanted, say so in the commit. The weekly run stays red until the baseline moves past it: to the
  next release tag, or, before the first release, to a new commit in `PINNED_BASELINE` of the
  workflow.
- **A number which has to be zero is not.** The candidate discarded or blocked an outbox entry, ran
  a handler twice, failed a delivery, or did not finish a workflow. The log of the round says which
  workflow. That is a defect, however fast the run was.
- **A time missed the threshold twice.** Look at the graph first. A step in both platforms at the
  same week, with a new runner model in the record, is the machine. A step in one platform is a
  change of that platform. Run both commits on your machine with `--rounds 9` to see whether it
  holds.

A week which proved nothing, because the run broke before it judged a platform, gets an issue too.
It looks different from a red week on purpose.

## What the weekly workflow needs

The workflow `.github/workflows/load-run.yaml` runs on Monday night and on demand. It needs:

- the secrets `VANILLABP_USER_NAME` and `VANILLABP_USER_TOKEN`, which may push to the wiki. The
  token of a workflow run cannot write a wiki, not even the wiki of its own repository,
- Docker on the runner, which `ubuntu-latest` has,
- nothing on `main`: the run writes to the wiki only, so the ruleset of `main` needs no exception,
  and a row starts no build.

