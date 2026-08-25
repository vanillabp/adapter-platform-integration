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
- delete the sentence where it decorates and promises nothing.

Measurements are not claims: a number is a statement about a measured past, so it needs its context
(version, setup, date) rather than a test.

There is deliberately no tooling for this. A lint over words like "never" or "always" produces noise
and a false sense of safety, and the habit is what does the work.

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
