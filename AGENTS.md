# Working on adapter-platform-integration

This repository holds the platform-neutral core of VanillaBP 2, plus the Spring Boot and Quarkus
integrations. A feature is built in `migration-adapter`. Both platforms get it from there.

Read [`README.md`](./README.md) first. It says which module a change belongs in, which SPI (service
provider interface, the set of interfaces the other side implements) it may touch, and how to
build. [`CONTRIBUTING.md`](./CONTRIBUTING.md) holds the rules which are easy to forget while you
write code. [`UPGRADE.md`](./UPGRADE.md) says what an application has to do when it moves from
VanillaBP 1 to 2.

A change to the adapter SPI touches a second document.
[`migration-adapter/ADAPTER-AUTHORS.md`](./migration-adapter/ADAPTER-AUTHORS.md) is what an adapter
team outside this workspace builds against. That team gets the document as its guide. So when you
change what an adapter implements, calls or promises, the change is finished only when the document
describes it.

## How we write

Most people who read this repository read English as a second language, and so does the
maintainer. Long sentences, rare words and stacked nouns slow them down. Write so that
nobody has to read a sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice.
The common word instead of the rare one: `use` instead of `leverage`, `about` instead of
`regarding`, `so` instead of `consequently`, `has` instead of `possesses`. A technical term
stays a technical term, but say what it means the first time it turns up, and write an
abbreviation out once. If a sentence trips you up when you read it aloud, rewrite it.

This holds for every English text here: the README files, `DECISIONS.md`, this file, the
Javadoc and comments which explain something, in the main code and in the tests, and the texts
of commits and pull requests. It holds for the
[wiki](https://github.com/vanillabp/adapter-platform-integration/wiki) as well, because the wiki
clone has no `AGENTS.md` of its own.

Nothing a program reads is renamed for the sake of language. Class and method names,
configuration keys, artifact coordinates and the headlines of decision log entries stay as
they are, because code, tests and other repositories point at them.

Before and after, from this repository. The first pair comes from the top of this file:

> It is the guide such a team is handed, so a change to what an adapter implements, calls back or
> promises is not finished until that document says the new thing.

becomes

> That team gets the document as its guide. So when you change what an adapter implements, calls or
> promises, the change is finished only when the document describes it.

The next pair comes from the section about `UPGRADE.md` further down:

> A change between two snapshots of 2.0 earns no entry, however much work it was: it would ask the
> reader to follow how the release was built instead of carrying out their own upgrade, and git
> holds that history anyway.

becomes

> A change between two snapshots of 2.0 gets no entry, however much work it was. The reader wants to
> do their own upgrade. They do not want to follow how the release was built, and git keeps that
> history anyway.

The last pair comes from the section about the decision log:

> An entry is not background reading, it is the reason the code around it looks the way it does,
> so a change which contradicts one is wrong until the entry says otherwise.

becomes

> An entry is not background reading. It is the reason why the code around it looks the way it
> does. So a change which contradicts an entry is wrong until the entry says otherwise.

## Before you start a full build

You may not be alone on the machine. "Building and testing" in
[`CONTRIBUTING.md`](./CONTRIBUTING.md#building-and-testing) says how many full builds the machine
carries at once, and what to give Maven when it has to share. It also shows the measurement behind
both numbers. Read it before you start `./mvnw install` at the root. When the memory runs out, the
build which gets killed is not always the one which started last.

## What belongs in `UPGRADE.md`

[`UPGRADE.md`](./UPGRADE.md) describes the step from VanillaBP 1 to the 2.0 release and nothing
else. It needs an entry where a version-1 application behaves differently or has to change
something. A change between two snapshots of 2.0 gets no entry, however much work it was. The
reader wants to do their own upgrade. They do not want to follow how the release was built, and
git keeps that history anyway.

What does not go there still has a place. The end state of a new feature belongs in the wiki,
because users read the wiki. A reason which several places in this repository rely on belongs in
[`DECISIONS.md`](./DECISIONS.md). Anything else belongs in the commit message and nowhere else.

The file is ordered by version line first and then by topic. No heading carries a date. Its half
for users is the wiki page
[Migrating from version 1](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1).
Where the two disagree, the wiki page wins.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions which several places in this repository rely
on. It is the ONLY thing the code may cite. The code cites it in the plain form
`see decision 7 in the repository's DECISIONS.md`, which a search finds, and only entries of THIS
repository.

Read it before you change behaviour. An entry is not background reading. It is the reason why the
code around it looks the way it does. So a change which contradicts an entry is wrong until the
entry says otherwise.

**A decision is changed or replaced only after asking.** If your change would make an entry untrue,
stop. Ask the maintainer before you write the change. If the answer is yes, the same commit updates
the log. The old entry STAYS. It is marked as superseded and names the entry which replaced it. The
new decision takes the next free number. Numbers are never reused and never renumbered, because a
citation in an older release still points at them. Never edit an entry until its old text is gone.

The same rule holds for a new entry. A decision gets a number when several places rely on it, and
a copy of the explanation in each place would go out of date. Anything smaller is a comment in the
place it belongs. Anything larger is documentation.

## Who has to follow

A change which an application can see does not stop at this repository. The BPMS adapters, the
blueprints, both wikis and the Business Cockpit write the same property names, default values and
table names down again, and this build compiles none of them. Name the repositories which have to
follow in the pull request. Write `nobody` where none has to.

This search answers the question. Run it from the root of this repository, with the other clones
next to it and up to date:

```bash
grep -rIl --exclude-dir=.git --exclude-dir=target <the name you are changing> .. | cut -d/ -f2 | sort -u
```

[`CONTRIBUTING.md`](./CONTRIBUTING.md#a-change-names-who-has-to-follow) says why this is a rule,
and what it cost the two times nobody did it.

## Before you open a pull request

A number your branch hands out can be taken by the time you open the pull request. Another branch
was open at the same time and got there first. So check your numbers against `origin/main` and
against every open pull request, before the pull request exists.

It went wrong twice on 2026-09-13, in the Business Cockpit repository. Two branches claimed one
number, and they had to become 19 and 20. Two more claimed the next number, and they had to become
21 and 22. Both times it showed up at the merge, which is the worst moment for it. A merge happens
on GitHub, and nobody can change a `see decision 21` in a Java file there.

The check:

```bash
bin/check-decision-numbers.sh
```

The script reports and changes nothing. By hand it is:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^### [0-9]+\. '  # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+### [0-9]+\. '                   # for each open pull request
```

`gh pr diff` takes no path argument, so the grep does the filtering.

If your number is taken, your entry gets the next free one. Then correct every citation of it in
the code and in the documentation.

Read each citation before you change it. Not every `see decision <n>` in the branch is about your
decision. A branch can cite a number which somebody else handed out long ago, and that citation
stays as it is. A search and replace over the branch turns a right reference into a wrong one.

None of this breaks the rule that a number is never renumbered. That rule is about a merged number,
because a citation in a released artifact points at it. Until the pull request is merged, nothing
outside the branch has seen the number. So correcting it costs no more than the branch.

Check every other running number the same way. The story prompts are such a series. They are kept
outside this repository, so check them where they are kept.

After you renumber, run `bin/check-decision-citations.sh`. It says whether each of your citations
still points at something.

`bin/check-orphaned-javadoc.sh` finds a javadoc block which stands directly in front of a second
one. Javadoc drops such a block without a word, so its text appears nowhere. Run the script when
you wrote or moved a comment. If it reports a block, move the block back onto the element it
describes. Do not delete it.

## The extension double exists twice

`SampleExtension` of `spring-boot-integration/integration-tests/outbox-jpa-integration-test` has a
copy in `vanillabp/gruelbox-phase-two-outbox`. The module it lives in publishes nothing, and a
repository outside this one cannot depend on it. A change here does not reach that copy. So read
the copy before you decide that a difference between the two is on purpose. The decision is to
keep the copy, not to publish the double.

## What code may point at

Code points at nothing which a later change can make wrong without anybody noticing. So no story or
prompt number, no issue or pull-request number, no chat transcript and no person. Those record a
conversation at one point in time. A decision entry lives next to the code, and the same commit
which changes the code updates the entry. That is why code may cite it.

Where a name can carry the reason, the name is the better fix. Where it cannot, a comment says why,
in its own words and complete where it stands. Only what several places have to carry becomes an
entry in the log.

A citation of a numbered entry is the whole phrase `decision 7 in the repository's DECISIONS.md`.
A decision which still waits for its number is cited by its file,
`DECISIONS.pending/<story>.md`, in javadoc inside a `{@code}` tag. That is the only way to write
it, so the story which numbers the decision finds every citation with one search. A number in
angle brackets is not a citation any more. Javadoc wants the brackets escaped, the formatter then
breaks the escaped form over two lines, and a search which reads lines walks past it.
`bin/check-decision-citations.sh` finds all of that, wrapped or not, and it runs on every pull
request. An example in a document writes a placeholder where the number goes, because the check
cannot tell an example from a citation.

Commit messages and pull-request descriptions may cite whatever they like. They record a point in
time themselves.
