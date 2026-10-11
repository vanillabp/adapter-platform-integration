# Contributing

This repository is the platform integration of VanillaBP. Its platform-neutral core turns the
annotations of [`spi-for-java`](https://github.com/vanillabp/spi-for-java) into calls to a BPMS
(business process management system, the engine which runs the BPMN processes). Around the core sit
the parts which connect it to Spring Boot and to Quarkus. The BPMS adapters live in repositories of
their own and plug in here. The [wiki](https://github.com/vanillabp/adapter-platform-integration/wiki)
describes what VanillaBP does for the people who use it. This file is for somebody who changes the
code.

[`README.md`](./README.md#rules-and-decisions-worth-knowing-before-contributing) holds the rules a
change to this repository follows. It says where a feature belongs, which SPI it may touch, how
configuration is validated, what the tests have to prove, and how to build.
[`UPGRADE.md`](./UPGRADE.md) says what an application has to do when it moves from VanillaBP 1. A
change between two snapshots of 2.0 gets no entry there.

[`DECISIONS.md`](./DECISIONS.md) holds the decisions which several places rely on. It is the only
thing the code may cite. Read it before you change behaviour. If your change would make an entry
untrue, ask before you write the change. An entry is superseded, not edited. It keeps its number,
and the entry which replaces it takes the next free number. [`AGENTS.md`](./AGENTS.md) says the same
in the form an agent reads.

If your change touches the adapter SPI, it also touches
[`migration-adapter/ADAPTER-AUTHORS.md`](./migration-adapter/ADAPTER-AUTHORS.md). A team which
builds an adapter outside this workspace builds against that document. It is the one place which
describes the SPI as a whole, and not method by method. So when you change what an adapter
implements, calls or promises, the change is finished only when the document describes it.

Render a changed picture before you commit it. `bin/render-diagrams.sh` draws every Mermaid block
of the repository. It fails where a block does not parse, and a semicolon inside a note or a message
is enough for that. [`diagrams/README.md`](./diagrams/README.md) describes this trap and why it is
easy to walk into. A pull request which touches a Markdown file runs the same script. A block which
does not parse does not give a smaller picture. It gives an error message where the picture was.
That happened here twice, and both times nobody saw it for weeks. The script is not part of the
Maven build, because it downloads a headless browser the first time it runs. A local build has to
work without a network.

## Getting started

Start from the [development workspace](https://github.com/vanillabp/development-workspace). Clone it
with `git clone --recurse-submodules`. This repository builds on `spi-for-java`, and the workspace
holds both side by side. The [README of the
workspace](https://github.com/vanillabp/development-workspace#readme) says how to build them in
order without published snapshots. It also says how to open a pull request, with or without the
right to push here, and how a coding agent can take a bug off your hands. The rest of this file is
about this repository.

## Building and testing

You need a JDK 21 or newer, and `spi-for-java`. Build and install `spi-for-java` in the workspace
(see [Getting started](#getting-started)), or use its
[published snapshot](#snapshots-on-maven-central). The workflows build with the JDK named in
`.github/workflows`, which is 25 today. Build with that one if you want to see what the pipeline
sees. The class files stay at Java 21 either way, because the property `version.java` in the root
`pom.xml` says so. Then run this from the root of this repository:

```bash
./mvnw spotless:apply
./mvnw install
```

Use `install`, not `package`. The Quarkus tests load their modules from the local Maven repository.
So after `package`, they test the module from the run before. Use `install` alone, never
`install verify`. `install` already runs every phase of `verify`, and with both goals you get every
compiler warning twice. [`README.md`](./README.md#building) says the same and gives the reasons. The
`README.md` of each module describes that module.

`spotless:check -N` at the root is not the whole check. A green run there says less than it seems.
`-N` builds the root module alone. The POM and Markdown formatters use the patterns `**/pom.xml` and
`**/*.md`, so they walk the whole tree from where they start. At the root they cover all 73 POMs and
all 20 Markdown files. The Java formatter has no such pattern. It reads the source folders of the
module it runs in, and the root module has none. So a root run never opens a single one of the 1195
Java files. Measured on 2026-09-25: an unused import in `spring-boot-integration/runtime` passed the
root run and failed `spotless:check -pl spring-boot-integration/runtime`.

The rules themselves are complete. `removeUnusedImports` is configured, and the `check` goal runs in
the phase `process-sources` of every module. So a plain `./mvnw install` from the root finds the
unused import, and so does the pipeline. Only the shortcut misleads. Run `spotless:apply` without
`-N` before you commit. Read a `-N` run as a statement about POMs and Markdown only.

The tests which start a database in a container need Docker, MongoDB above all. The rest runs
without it. The version of each such image is pinned. So a run says what it ran against, and a new
release of the image cannot change a result overnight. The pins live in `ContainerImages` of
[`test-utils`](./test-utils), one field per image. A test reads the field and does not write a
version into its own source. MongoDB is `mongo:8.2` there, not 8.0. Version 8.0 refuses to start on
a Linux kernel 6.19 or newer ([SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912)). The
container then stops right after it started, and the failing test says nothing about the reason. A
machine which needs another version passes `-Dmongodb.image=mongo:7.0` and leaves the sources
alone. The blueprints repository reads its images the same way.

A full build is big, so count the builds a machine runs at once. We measured this on 2026-09-20 in
the development container, which has 16 GB of memory and 1 GB of swap. One `./mvnw test` over all 73
modules used up to 6.3 GB across its Java processes and left 6.6 GB free. Two at once used 10.8 GB
and every megabyte of the swap. Three took the machine down to 613 MB in ten minutes. At that point
the kernel starts to kill whatever is largest, and that is usually somebody else's build. So:

- one full build at a time while a BPMS cluster runs anywhere on the machine,
- two when no cluster runs and whoever else works there knows about it,
- never three.

A build of a single module is small and does not count.

Nothing limits the heap of a forked test JVM. So each one may grow to a quarter of the machine's
memory, 3.9 GB in that container, long before it needs that much. Where a build has to share a
machine, `MAVEN_OPTS=-Xmx1g _JAVA_OPTIONS=-XX:MaxRAMPercentage=10 ./mvnw test` halves the memory
the build uses. A single build is not measurably slower. The setting stays out of the POMs on
purpose. A runner has its machine to itself. If a test one day needs more memory, it should not fail
there for a reason which has nothing to do with the test.

Narrow a build down with `-pl` or `-rf`, never with `-Dtest`. `-pl` names the modules to build, and
`-rf` restarts the reactor at one of them. A `-Dtest` filter works differently. It reaches every
module of the run. Inside a module it replaces the patterns which Surefire uses to find tests:
`**/Test*.java`, `**/*Test.java`, `**/*Tests.java` and `**/*TestCase.java`. Then every compiled class
is a candidate, nested or top-level. A helper class which is written to fail then runs on its own,
and the build turns red for something which is not a defect.

`test-utils` shows it. `./mvnw test -pl test-utils -Dtest='!SomeTest'` ends with three errors in
`SuppressOutputExtensionTest`. Each of the three is a nested class, and the tests around it measure
exactly that failure. Without the filter, the same module is green.

On 2026-09-20 we tried two ways to hide those helpers from the filter. Neither works. The first was
to move them out of their test class, into top-level classes whose names the default patterns do
not match. That changes nothing, because the filter has replaced those patterns, and what is left
scans every class. The second was a Surefire `<excludes>`. That changes nothing either, because
`-Dtest` overrides the excludes together with the includes. Without the filter, the same exclude
empties the module. With the filter, it is ignored.

A JUnit tag on each helper plus `<excludedGroups>` does work, because that filter sits in JUnit and
not in the scan. We did not take it. Every helper of this kind would have to carry the tag, and
nothing fails when somebody writes the next one without it. And the filter would still scan every
class of every module it reaches.

A feature is proven by one acceptance test per platform, against the published
[BPMS double](./bpms-double). Coverage is measured separately per platform, because a Spring test
never covers Quarkus code. `test-coverage-report/coverage-gate` is the last module of the reactor.
It fails below 85 percent of covered instructions, while the rule is 90. It judges what a run built.
The reports are written in the `verify` phase. So a build which stops at `package` prints one line
per platform which says that coverage was not checked. It does not fail over a file it never wrote.

A test may wait as a guard, but the wait is never its assertion. On a machine which runs a few
builds at once, a test JVM is held back by a quarter of a second at a time, again and again. So no
fixed window of a few hundred milliseconds can show that something was fast enough. Instead, a test
reads what the code promises from a fact, such as the moments a poller polled or the number of
probes an adapter answered. The wait around that fact is then generous, and a slow machine makes a
test slower, not red.
[`DueEntryPollerTest`](./migration-adapter/runtime/src/test/java/io/vanillabp/migration/test/outbox/DueEntryPollerTest.java)
is the shape to copy. Where a number really does mean something, a line next to it says what it
means: which configured interval it is three times, or which window it stays below.

## Snapshots on Maven Central

If you clone only this repository, Maven takes `spi-for-java` from the snapshot repository of Maven
Central, `https://central.sonatype.com/repository/maven-snapshots/`. Every push to `main` of
`spi-for-java` publishes a snapshot there. The parent `io.vanillabp:release-parent` names that
repository. Reading it needs no login and no token.

Every push to `main` of this repository publishes its own snapshot to the same place.

## What a POM hands an application

A tool which only translates our source belongs in scope `provided`. The scope is written where the
module which uses the tool declares it. Lombok is such a tool, and so is an annotation processor. An
application asked for a workflow engine. Every jar it did not ask for is one more thing to ship, and
one more thing to answer a CVE report (a published security flaw) about.

`<optional>true</optional>` in a `dependencyManagement` does not help. Maven copies a managed
version, scope and exclusions into a dependency, but it leaves the optional flag behind. So the POM
we publish says nothing at all about that dependency. That is how Lombok reached the runtime
classpath of every application. Nobody saw it here, because most modules which use Lombok did not
declare it themselves. They compiled against the copy which another module passed on.
[`PublishedPomsTest`](./migration-adapter/runtime/src/test/java/io/vanillabp/migration/test/PublishedPomsTest.java)
reads the POMs of this repository, which are the files we publish. It fails when a build tool is
declared in a way which hands it to an application.

## A POM comment says what this POM does

A comment in a POM says what this POM sets, and why that differs from the default. It does not
repeat what the parent sets. `io.vanillabp:release-parent` is released on its own schedule. So a
copy of its settings in a child POM here goes out of date, and nothing notices. The javadoc comments
of three POMs in this organisation did exactly that. They said the parent switched the javadoc check
off, after a release of the parent had switched it on.

Sometimes a module really does rely on what its parent does or does not do. Then say so as an
assumption, and name the version you checked it against, as `bom/pom.xml` does. The next reader can
then check it again with one command, `mvn help:effective-pom`, and does not have to believe a
sentence.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
So write plain English: short sentences, everyday words, one thought per sentence. The section
[How we write](./AGENTS.md#how-we-write) in `AGENTS.md` holds the rule, with examples from this
repository. It holds for every English text here and in the
[wiki](https://github.com/vanillabp/adapter-platform-integration/wiki), the javadoc, the commit
message and the pull request included.

Two of the rules named above are easy to forget while you write code. So the next two sections spell
them out again.

## A promise is part of the behavior

> A javadoc, `README.md` or wiki sentence which promises behavior is part of the behavior. Either a
> test fails when it stops being true, or the sentence says that it is an assumption and what would
> prove it wrong. A story which changes behavior reads the claims about that behavior again before
> it is done.

Why this is a rule and not just good advice: three defects in a row were already described in a
javadoc, correctly and in detail, and nobody could act on it. A claim is written once, by somebody
who knows it is true at that moment. Then nothing reads it again when the code moves, and nothing
fails when it stops being true. We went through both SPIs, the adapters' decisions and all four
wikis once, to start from a clean state. Keeping it clean cannot be done in one pass.

What this asks for in practice:

- Name the test in the claim where the test is not obvious. `see FooTest#bar` is enough.
- Write the test where the claim matters and is cheap to guard. That usually means a unit test, or
  one case added to a test which already starts what is needed.
- Write "assumption" where it is one, and say what would prove it wrong. Do not promise something
  which nothing checks.
- Delete the sentence where it only decorates and promises nothing.
- Keep a javadoc citation inside what its reader can reach. An adapter author outside this
  workspace opens the published API and nothing else. A story number, a prompt, a skill or a roadmap
  entry is a dead end there. Name the method, the type or the decision instead. Where a javadoc
  names something in the source, use `{@link}` and do not write the name out. Then the next rename
  takes the sentence along.
- Answer the line `wiki pages re-read:` of the
  [pull request template](./.github/pull_request_template.md). Write `none` where the change touched
  nothing a wiki page states. When nobody asks the question, the wikis fall behind by up to two
  weeks. That is how a user came to read the old transaction model of Camunda 7 after it had
  changed. A review round which answered `none` at least answered it.

Measurements are not claims. A number describes a measured past. So it needs its context (version,
setup, date), not a test.

One part of this is a job for a machine after all. Two tools read the javadoc, and each one sees a
part the other misses. The compiler checks every class for a broken reference or broken HTML,
package-private ones included, because every module compiles with `-Xdoclint:all,-missing`. The
javadoc plugin runs in every build here. It checks what the published documentation shows, so it
starts at protected and stops there. One thing below protected is shown as well: the fields which a
serializable class carries into its serialized form. That is why a private field of an exception
needs a comment too.

A missing comment breaks the build. Everything this repository publishes has a comment now, and the
plugin fails on a warning so that it stays that way. Write the sentence. Do not switch the check
off. And write the sentence a reader needs. Three kinds of people read what this repository
publishes: the author of an application, whoever writes the next BPMS adapter, and whoever writes an
extension of the deployment pipeline. `@return the value` is the same gap, only longer. A module
which publishes nothing sets `maven.javadoc.skip`, so nobody asks a test module for comments.
Generated code is left out by the name its generator gives it.

The javadoc plugin cannot see an accessor which Lombok generates. The plugin reads the source, and
Lombok writes bytecode. So a published comment names a property in words, and does not link a
getter which is not in the file. A published class which got its constructor from Lombok writes that
constructor out. Otherwise the documentation shows a constructor without parameters which does not
exist.

Headings depend on where the comment is. In the javadoc of a type, a heading is `<strong>`, because
the plugin rejects `<h4>` there as a heading out of sequence. In the javadoc of a method, `<h4>` is
fine.

Two javadoc blocks in a row are the gap which neither tool sees. Javadoc keeps the last block before
an element and drops the earlier ones without a word. So a comment which somebody wrote and kept up
to date appears nowhere. `bin/check-orphaned-javadoc.sh` finds that shape. A block it reports
describes something, usually the element next to it. So move it back there, and do not delete it.

Neither check sees the same name written as prose, and neither says whether a sentence is true. So
the rest has no tooling, on purpose. A lint over words like "never" or "always" gives noise and a
false sense of safety. The habit is what does the work.

## A decision is superseded, never edited away

> A numbered entry of `DECISIONS.md` is changed or replaced only after asking. Where a change makes
> an entry untrue, the question comes before the change. Once the answer is yes, the same commit
> leaves the old entry standing, marked as superseded and naming its successor. The new decision
> gets the next free number.

Why this is a rule and not just good advice: a citation is written into the code once and read
years later, sometimes from a release which is no longer built here. If you renumber or rewrite an
entry, every one of those pointers leads to a text which no longer says what the reader came for.
And nothing fails when that happens. Superseding costs one paragraph and keeps the trail intact.

The opposite case is just as much a finding: an entry which nothing cites, or one whose reason fits
into a comment at the one place which needs it. A decision gets a number when several places rely on
it, and a copy of the explanation in each place would go out of date.

### How a citation is written

A citation of a numbered entry is the whole phrase `decision 7 in the repository's DECISIONS.md`.
Often a decision has no number yet while somebody writes the branch which needs it. Then it lives in
`DECISIONS.pending/<story>.md`, and that file is how javadoc cites it, inside a `{@code}` tag. There
is only one way to write it, on purpose: the story which hands out the number has to find every
citation with one search.

A number in angle brackets used to be the other way, and it is not allowed any more. Javadoc wants
the brackets escaped, the escaped form is long, and the formatter breaks it over two lines. Two
citations written like that survived the story which numbered their decisions. They stayed in the
tree for weeks, because a search reads one line at a time.

`bin/check-decision-citations.sh` does not. It joins every line with the ones after it, and it takes
off the leading `*` of a continued comment. So a wrapped citation is one string again. The script
reports three things: a spelling which is not the file name, a file which is gone because the
decision got its number, and a number which no entry of `DECISIONS.md` has. `--self-test` wraps a
citation of each kind over two lines and checks that the script still finds them. Both run on every
pull request. Write an example in a document with a placeholder where the number goes, because the
check reads an example as a citation.

## A change names who has to follow

> A change which an application can see says who has to follow it, in the same pull request. Those
> places are the BPMS adapters, the blueprints, both wikis and the Business Cockpit. Where nobody has
> to follow, the answer is "nobody", and it is written down all the same.

Why this is a rule and not just good advice: the story which replaced the outbox store of the Spring
Boot integration said nothing about the rest of the workspace. Two persistence blueprints stopped
compiling and stayed broken for a day. Three adapters kept reading a database table which no longer
existed. That showed up on the red build of a pull request which had nothing to do with the outbox.
The red build is not the damage. The damage is the time until somebody looks. A broken blueprint is
what the next reader copies, and a red build holds up work which was ready. A week later, the rename
of the payload store did name its followers. The four blueprints it touched were changed while the
rename was still cheap.

An application sees more than the adapter SPI. It sees a property name, a default value, the name of
a table or a collection, a published artifact, a log line somebody searches for. Each of those is
written down again in repositories which this build never compiles.

Finding out costs a minute. Update the other clones first, because a clone from last week answers
for last week. Then search them for the name you are about to change. Run this from the root of this
repository, where `..` holds the clones next to each other:

```bash
grep -rIl --exclude-dir=.git --exclude-dir=target VANILLABP_PHASE_TWO_OUTBOX .. | cut -d/ -f2 | sort -u
```

The names which come back are the repositories which have to follow. Run it once for each name the
change touches. A hit in a wiki clone counts as much as one in a source file. A sentence which
describes the old behaviour is wrong the moment the change is merged.

Write that list on the line `who has to follow:` of the
[pull request template](./.github/pull_request_template.md). A repository outside this one follows
in a pull request of its own. An adapter can only follow once the new snapshot of this repository is
published. Writing it down keeps it from being forgotten in between.

## Opening a pull request

Work on a branch of your own, and keep one subject per pull request. Fill in the
[template](./.github/pull_request_template.md), the line `wiki pages re-read:` included. Write `none`
where your change touched nothing a wiki page states. An unanswered line is not an answer.

Check the numbers your branch hands out before you open the pull request. Another branch may have
taken your decision number while you were writing. Once a pull request is merged, nobody can correct
a `see decision 7` in a Java file on GitHub any more:

```bash
bin/check-decision-numbers.sh
```

Two workflows answer a pull request. *Build and publish snapshots* builds and tests everything. It
publishes nothing from a branch, on purpose: there is one snapshot version per module, so a branch
which published would overwrite what `main` published. *Checks* reads the citations of every
decision and looks for a dropped javadoc block. Where a Markdown file changed, it also renders every
Mermaid block of the repository, because a block which does not parse shows an error message where
the picture should be. A red check is a finding about your change. Read the log and fix what it
says. Do not push again just to see whether it goes away.

If this is somebody's first pull request in the repository, a maintainer approves the first run of
the workflows. A pull request from a fork builds and tests like any other, because the snapshots it
needs can be read without a login. It cannot publish anything, because GitHub gives it no secrets of
this repository. [What the build of a pull request can
do](https://github.com/vanillabp/development-workspace#what-the-build-of-a-pull-request-can-do) says
more.

`main` has a ruleset which requires three green checks before a merge:

- `publish`, the build of the first workflow,
- `orphaned-javadoc-check`, which looks for the dropped javadoc block described above,
- `diagrams-verified`, the one name which answers on every pull request, whether there was a diagram
  to render or not.

While one of them is red, GitHub does not offer the merge. `decision-citation-check` runs next to
them. It is not required, but a red one there is a finding all the same.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE). By contributing, you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
