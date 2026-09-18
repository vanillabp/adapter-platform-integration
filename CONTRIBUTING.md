# Contributing

This repository is the platform integration of VanillaBP: the platform neutral core which turns the
annotations of [`spi-for-java`](https://github.com/vanillabp/spi-for-java) into calls to a BPMS, plus
the Spring Boot and Quarkus glue around it. The BPMS adapters live in repositories of their own and
plug in here. What VanillaBP does for the people using it is described in the
[wiki](https://github.com/vanillabp/adapter-platform-integration/wiki); this file is for somebody
changing the code.

The rules a change to this repository follows are in
[`README.md`](./README.md#rules-and-decisions-worth-knowing-before-contributing): where a feature
belongs, which SPI it may touch, how configuration is validated, what the tests have to prove, and
how to build. [`UPGRADE.md`](./UPGRADE.md) says what an application upgrading from VanillaBP 1
has to do, and a change between two snapshots of 2.0 earns no entry there.

[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places rely on, and it is the only
thing the code is allowed to cite. Read it before you change behaviour, and where your change would
make an entry untrue, ask before you write the change: an entry is superseded rather than edited,
keeps its number, and the successor takes the next free one.
[`AGENTS.md`](./AGENTS.md) says the same in the form an agent reads.

If your change touches the adapter SPI, it touches
[`migration-adapter/ADAPTER-AUTHORS.md`](./migration-adapter/ADAPTER-AUTHORS.md) as well. That
document is what a team building an adapter outside this workspace implements against, and it is
the one place where the SPI is described as a whole rather than method by method. A change to what
an adapter implements, calls back or promises is not finished until it says the new thing.

A changed picture is rendered before it is committed. `bin/render-diagrams.sh` draws every Mermaid
block of the repository and fails where one does not parse, which is what a semicolon inside a note
or a message does; [`diagrams/README.md`](./diagrams/README.md) has that trap written down together
with the reason it is easy to walk into. A pull request touching a Markdown file runs the same
script, because a block which does not parse is not a smaller picture but an error message where
the picture was, and both times that happened here nobody saw it for weeks. The script is not part
of the Maven build: it pulls a headless browser on first use, and a local build has to work without
a network.

## Building and testing

Java 21, and `spi-for-java` installed into the local Maven repository first. Then, from the root of
this repository:

```bash
./mvnw spotless:apply
./mvnw install
```

`install` and not `package`: the Quarkus tests load their modules from the local Maven repository, so
a module which was only packaged is the one from the run before. `install` alone and never
`install verify`, because `install` already runs every phase `verify` has and naming both reports
every compiler warning twice. [`README.md`](./README.md#building) says the same with the reasoning
around it, and the modules are described in the `README.md` of each module.

Docker is needed for the tests which start a database in a container, MongoDB above all. The rest
runs without it. A feature is proven by an acceptance test per platform, against the published
[BPMS double](./bpms-double), and coverage is measured separately per platform because a Spring test
never covers Quarkus code. `test-coverage-report/coverage-gate` is the last module of the reactor and
fails below 85 percent of covered instructions, while the rule is 90.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
Long sentences, rare words and stacked nouns slow them down. Write so that nobody has to read a
sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice. The common
word instead of the rare one: `use` instead of `leverage`, `about` instead of `regarding`, `so`
instead of `consequently`. A technical term stays a technical term, but say what it means the first
time it turns up, and write an abbreviation out once. If a sentence trips you up when you read it
aloud, rewrite it.

This holds for every English text here, the javadoc, the commit message and the pull request
included. Nothing a program reads is renamed for the sake of language: type and method names,
configuration keys and artifact coordinates stay as they are, because code in other repositories
points at them.

Two of the rules named above are easy to lose sight of while writing code, so they are spelled out
here as well.

## A promise is part of the behavior

> A javadoc, `README.md` or wiki sentence which promises behavior is part of the behavior. Either a
> test fails when it stops being true, or the sentence says that it is an assumption and what would
> disprove it. A story which changes behavior re-reads the claims about that behavior before it is
> done.

Why it is a rule rather than good advice: three defects in a row were already described, correctly
and in detail, in a javadoc nobody could act on. A claim is written once, by somebody who knows it is
true at that moment, and then nothing re-reads it when the code moves and nothing fails when it stops
being true. Both SPIs, the adapters' decisions and all four wikis were walked once to start from
a clean state; keeping it clean is the part which cannot be done in one pass.

What this asks for in practice:

- name the test in the claim where the test is not obvious (`see FooTest#bar` is enough);
- write the test where the claim is load-bearing and cheap to guard, which usually means a unit test
  or one case added to a test which already boots what is needed;
- say "assumption" where it is one, together with what would disprove it, rather than promising
  something nothing checks;
- delete the sentence where it decorates and promises nothing;
- keep a javadoc citation inside what its reader can reach. An adapter author outside this
  workspace opens the published API and nothing else, so a story number, a prompt, a skill or a
  roadmap entry is a dead end there; name the method, the type or the decision instead. Where a
  javadoc names something in the source, `{@link}` it rather than writing it out, so the next
  rename takes the sentence along;
- answer the line `wiki pages re-read:` of the
  [pull request template](./.github/pull_request_template.md), with `none` where the change touched
  nothing a wiki page states. The wikis lag by up to two weeks whenever nobody is asked the
  question, which is how a user came to read the transaction model of Camunda 7 from before it
  changed; a review round which answered `none` at least answered it.

Measurements are not claims: a number is a statement about a measured past, so it needs its context
(version, setup, date) rather than a test.

One part of it is a machine's job after all: every module compiles with `-Xdoclint:reference`, so a
`{@link}` pointing at a method which was renamed or removed fails the build and a parameter
documented twice is warned about. What that check cannot see is the same name written as prose, and
it says nothing about whether a sentence is true, so the rest stays deliberately without tooling. A
lint over words like "never" or "always" produces noise and a false sense of safety, and the habit
is what does the work.

## A decision is superseded, never edited away

> A numbered entry of `DECISIONS.md` is changed or replaced only after asking. Where a change makes
> one untrue, the question comes before the change. Once the answer is yes, the same commit leaves
> the old entry standing, marked as superseded and naming its successor, and gives the new decision
> the next free number.

Why it is a rule rather than good advice: a citation is written into the code once and read years
later, sometimes from a release which is no longer built here. Renumbering or rewriting an entry
turns every one of those pointers into something which resolves to text that no longer says what
the reader was sent for, and nothing fails when it happens. Superseding costs one paragraph and
keeps the trail intact.

The mirror image is just as much a finding: an entry which nothing cites, or one whose reasoning
fits into a comment at the single place which needs it. A decision earns a number when several
places rely on it and copying the explanation to each of them would rot.

## Opening a pull request

Work on a branch of your own and keep one subject per pull request. Fill in the
[template](./.github/pull_request_template.md), the line `wiki pages re-read:` included. It takes
`none` where your change touched nothing a wiki page states, and an unanswered line is not an answer.

Check the numbers your branch hands out before you open it. Another branch may have taken the
decision number you used while you were writing, and once a pull request is merged a
`see decision 7` in a Java file can no longer be corrected on GitHub:

```bash
bin/check-decision-numbers.sh
```

Two workflows answer a pull request. *Publish to GitHub Packages* builds and tests everything and
publishes nothing from a branch, which is deliberate: there is one `2.0.0-SNAPSHOT` per module, so a
branch which published would overwrite what `main` published. *Checks* runs where a Markdown file
changed and renders every Mermaid block of the repository, because a block which does not parse
shows an error message where the picture should be. A red check is a finding about your change. Read the
log and fix what it says rather than pushing again to see whether it goes away.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE), and by contributing you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
