# A delivery nobody deduplicates is written down too, under a key which deduplicates nothing

The record of a task delivery answers three questions and only one of them is the deduplication:
which BPMS holds the task, which kind of id its id is, and whether this delivery was answered
before. Until now the third question decided whether the first two were written down at all. An
adapter which reported no delivery id got no row, so nothing could be read back about its tasks.

Camunda 7 is that adapter. `Camunda7UserTaskInvocationContext.getDeliveryId()` answers `null` on
purpose: a transaction of the engine creates every user task the token reaches, and the id comes
into being while it is created, so there is nothing a repetition could be recognised by.
`TaskDeliveryKey.of` answered `null` for a blank id, `DeliveryRecords.keyFor` passed it on, and
`MigrationProcessService` read that as "no delivery log for this delivery". Measured on 2026-10-01:
a user-task delivery reached its handler, the table held no row for it, and `completeTask` with
that user task's id answered with the list of three guesses. Which is exactly the mistake the kind
of task was written down for one row earlier.

So the row is written. The absence of a delivery id takes the deduplication away and nothing else.

## The key says what the row is

`TaskDeliveryKey.of` answers a `TaskDeliveryIdentity` now: the key plus whether a repetition is
recognised by it. Where the adapter reports no id the key keeps the shape it always had and carries
`(not-deduplicated)` with a random value where the id would stand:

```
c7|orders|Approval|CREATED|(not-deduplicated)7f3c…
```

The row is unique, so the primary key of the table and the `_id` of a MongoDB document hold without
a word of new code in any store. Nothing is ever looked up by such a key either, because the core
only reads a key it can build a second time. And whoever opens the table reads at the key itself
that this row answers no repetition, instead of having to know which adapter reports ids.

A key which simply left the last field empty was the other candidate. It is shorter and it is a
lie: two deliveries of two tasks would collide on it, and the second one would be read as a
repetition of the first.

## What the switch still turns off

`deduplicate-deliveries` set to `false` remains the one case which writes nothing at all. The
property says the handlers of this application are idempotent themselves, and the BPMS behind it
may well repeat a delivery - a row per repetition would show one task as often as it was handed
out to everything which reads the open work of a workflow. An adapter which reports no id repeats
nothing, so one delivery is one row there.

That is the whole reason the two cases are told apart rather than merged. Both look like "nothing
is deduplicated" from the configuration, and only one of them can afford a row per delivery.

## What the retention does with such a row

The same thing it does with every other row, by the same statement: `LAST_SEEN_AT` plus
`vanillabp.delivery.retention`. What differs is where the clock starts. A deduplicable row of an
open task is kept alive by every redelivery it answers, so its clock starts when the BPMS stops
handing that task out. A row nobody deduplicates gets no redelivery, so its clock runs from the
moment the handler ran.

Nothing about correctness hangs on that number here, which is the point. For a deduplicable row
the retention IS the deduplication window and a row deleted too early runs business code twice.
Here the row answers no repetition in the first place, so a deleted one costs the saved BPMS round
trip of a task operation and the sharper sentence of a failure which names the kind of an id. An
installation whose user tasks stay open longer than seven days and which wants both raises the
retention; the startup says nothing about it, because there is nothing wrong to report.

Keeping such a row alive as long as its task is open was the alternative. It would mean the
retention asking which rows are open and never deleting those, and a task nobody ever completes
would then keep its row for good - an unbounded table in exchange for a sharper error message.

## What the startup check says

`JdbcTaskDeliveryStore` used to explain the table by the deduplication alone: "VanillaBP remembers
every task delivery it processed in it, so a BPMS repeating a delivery is answered from it instead
of running the handler twice." That is now half the truth, and the half which does not apply to
Camunda 7 at all. The message names the second purpose as well, so an operator asked to create the
table learns why an embedded engine needs it too.

Nothing new is reported. The warning about a missing store still fires only where an adapter may
repeat a delivery and deduplication is on, because its whole text is about running a handler twice.
An application whose only adapter reports no delivery id loses the two answers without a store, and
that is worth no boot-time warning: it has no way of knowing which of them it will ever ask for.
