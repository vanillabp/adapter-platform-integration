# Contributing

The rules a change to this repository follows are in
[`README.md`](./README.md#rules-and-decisions-worth-knowing-before-contributing): where a feature
belongs, which SPI it may touch, how configuration is validated, what the tests have to prove, and
how to build. Breaking changes and their reasoning are recorded in [`UPGRADE.md`](./UPGRADE.md).

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

Two of those rules are easy to lose sight of while writing code, so they are spelled out here as
well.

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
