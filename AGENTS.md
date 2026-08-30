# Working on adapter-platform-integration

The platform-neutral core of VanillaBP 2 plus the Spring Boot and Quarkus integrations. A feature
is implemented in `migration-adapter` and reaches both platforms from there.

Read [`README.md`](./README.md) first: it says which module a change belongs in, which SPI it may
touch, and how to build. [`CONTRIBUTING.md`](./CONTRIBUTING.md) carries the one rule which is easy
to lose sight of while writing code, and [`UPGRADE.md`](./UPGRADE.md) records every breaking change
with its reasoning.

Changing the adapter SPI touches a second document:
[`migration-adapter/ADAPTER-AUTHORS.md`](./migration-adapter/ADAPTER-AUTHORS.md) is what an adapter
team outside this workspace implements against. It is the guide such a team is handed, so a change
to what an adapter implements, calls back or promises is not finished until that document says the
new thing.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places in this repository rely on. It
is the ONLY thing the code is allowed to cite, in the plain greppable form
`see decision 7 in the repository's DECISIONS.md`, and only entries of THIS repository.

Read it before you change behaviour. An entry is not background reading, it is the reason the code
around it looks the way it does, so a change which contradicts one is wrong until the entry says
otherwise.

**A decision is changed or replaced only after asking.** Where your change would make an entry
untrue, stop and put the question to the maintainer before you write the change. If the answer is
yes, the same commit updates the log: the old entry STAYS, marked as superseded and naming the
entry which replaced it, and the new decision takes the next free number. Numbers are never reused
and never renumbered, because a citation in an older release still points at them. Editing an
entry until its old text is gone is never the way.

Adding an entry has the same rule. A decision earns a number when several places rely on it and
copying the explanation to each of them would rot; anything smaller is a comment where it belongs,
and anything larger is documentation.

## What code may point at

Nothing which a later change can invalidate without anything noticing: no story or prompt number,
no issue or pull-request number, no chat transcript, no person. Those record a conversation at a
point in time. A decision entry lives next to the code and is overhauled in the same commit, which
is what makes it citable.

Where a name can carry the reason, the name is the better fix. Where it cannot, a comment says why
in its own words, complete where it stands. Only what several places have to carry becomes an
entry in the log.

Commit messages and pull-request descriptions may cite whatever they like. They are records of a
point in time themselves.
