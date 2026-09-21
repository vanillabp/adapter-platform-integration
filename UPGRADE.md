# Upgrade notes

Contributor-facing list of what a VanillaBP 1 application has to do, organised per version line. It
describes the step to the release, not how the release was built - the development history is in
git. These entries feed the user-facing
[migration guide](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1),
which is the narrated version for users and wins where the two disagree. The same file exists for
[spi-for-java](https://github.com/vanillabp/spi-for-java/blob/main/UPGRADE.md), for the
[Camunda 7 adapter](https://github.com/vanillabp/camunda7-adapter/blob/main/UPGRADE.md) and for the
[Camunda 8 adapter](https://github.com/vanillabp/camunda8-adapter/blob/main/UPGRADE.md). What your
BPMS asks of you on top of this is in the file of its adapter.

An entry is owed where a version-1 application behaves differently or has to change something.
Everything else belongs in the wiki, in [`DECISIONS.md`](./DECISIONS.md) or nowhere.

## 2.0

The sections stand in the order an upgrade is carried out.

### The runtime and the dependencies

|             |   version 1   | version 2 |
|-------------|---------------|-----------|
| Java        | 17            | 21        |
| Spring Boot | 3.5           | 4.1       |
| Quarkus     | not supported | 3.37      |

Spring Boot 4 split the one artifact `spring-boot-autoconfigure` into a module per technology, so a
few imports of your own code move. `@EntityScan` comes from
`org.springframework.boot.persistence.autoconfigure` now, `HibernateJpaAutoConfiguration` from
`org.springframework.boot.hibernate.autoconfigure` and `MongoClientSettingsBuilderCustomizer` from
`org.springframework.boot.mongodb.autoconfigure`. The test slices left `spring-boot-starter-test`
and each needs a dependency of its own, `@DataJpaTest` for instance
`org.springframework.boot:spring-boot-data-jpa-test`. That is Spring's change rather than
VanillaBP's, so Spring Boot's own migration guide is what describes it.

A module of your application which must not depend on a BPMS depends on
`io.vanillabp:vanillabp-spring-boot-support`, where version 1 had `io.vanillabp:spring-boot-support`,
and there is `io.vanillabp:vanillabp-quarkus-support` next to it. Both carry the VanillaBP API such
a module compiles against and bring the integration SPI
(`io.vanillabp:vanillabp-integration-spi`) along, which is where the interfaces live your
application may implement. The adapter artifacts were renamed as well and the file of your adapter
names them.

Persistence needs no dependency change. Version 1 stored workflow aggregates through Spring Data,
JPA or MongoDB, and version 2 does that out of the box. On Quarkus the built-in support is wider: a
Panache repository, a Panache active record and a Spring Data repository are all served.

### Workflow modules are declared by a file

Version 1 read the id of a workflow module from `spring.application.name`, from the name of the
module's configuration file, or from a static bean method handing over a `WorkflowModuleProperties`.
Version 2 reads it from a text file `META-INF/workflow-module` whose content is the id, one file per
module, so both platforms find a module the same way.

```
src/main/resources/META-INF/workflow-module      # content: loan-approval
```

`WorkflowModuleProperties` does not exist any more, so the bean method has to go and a project
keeping it does not compile. The properties class it named stays as it is, because binding a
module's own configuration has not changed. Keep the ids you had: they name BPMS tenants and
resource directories, and a new id detaches the running workflows of that module from their
configuration.

An application without any such file is one single global workflow module, which is what an
application with one use case was in version 1 as well.

### Configuration

#### `default-adapter` becomes `prioritized-adapters`

Version 1 named the one adapter of a module. Version 2 names an order of adapters, because several
BPMS may serve one application, and that is what makes a BPMS migration a matter of configuration.
The key sits at the same three levels it did.

```yaml
# before
vanillabp:
  default-adapter: camunda7
  workflow-modules:
    ride:
      default-adapter: camunda7
      workflows:
        RideProcess:
          default-adapter: camunda7

# after
vanillabp:
  prioritized-adapters:
    - camunda7
  workflow-modules:
    ride:
      prioritized-adapters:
        - camunda7
      workflows:
        RideProcess:
          prioritized-adapters:
            - camunda7
```

The first entry starts new workflows and the others are asked about workflows started earlier. The
most specific non-empty value wins as a whole, so a workflow's list replaces the module's instead of
being merged into it.

#### An adapter id names one BPMS instance

In version 1 an adapter's settings lived per workflow module
(`vanillabp.workflow-modules.<m>.adapters.<adapter>.*`) and the connection to the engine was
configured through the BPMS' own Spring integration. In version 2 everything one BPMS instance needs
lives in the section of its adapter id, `vanillabp.adapters.<id>.*`, and no BPMS-specific Spring
configuration is involved.

```yaml
vanillabp:
  prioritized-adapters:
    - camunda8
  adapters:
    camunda8:                 # the id; 'type' may be left out because the id IS the type
      mode: self-managed
      rest-address: http://localhost:8080
      job-timeout: PT5M       # adapter-wide, overridable per module, workflow and task
```

An id which is not an adapter type needs `vanillabp.adapters.<id>.type`. That is how two instances
of one BPMS are told apart, and two ids of one type additionally have to be distinguishable by
something the adapter decides, a second address or a second datasource for instance. Version 1 had
one adapter and its id was its type, so keeping the type as the id is the smallest step. Pick the
names with care all the same: VanillaBP persists the adapter id, so renaming one later leaves
workflows nothing serves any more.

An adapter-specific value stays resolvable most specific first, across task, workflow, workflow
module and adapter, so `vanillabp.workflow-modules.<m>.adapters.<id>.<key>` still wins over
`vanillabp.adapters.<id>.<key>`.

#### Key by key

|                             version 1                             |                       version 2                       |                                                    note                                                     |
|-------------------------------------------------------------------|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `vanillabp.default-adapter`                                       | `vanillabp.prioritized-adapters`                      | a list now, at all three levels                                                                             |
| `vanillabp.workflow-modules.<m>.default-adapter`                  | `vanillabp.workflow-modules.<m>.prioritized-adapters` |                                                                                                             |
| `vanillabp.workflow-modules.<m>.workflows.<w>.default-adapter`    | the same key below `workflows.<w>`                    |                                                                                                             |
| `vanillabp.workflow-modules.<m>.adapters.<id>.resources-location` | unchanged and optional now                            | derived as `classpath*:<module>/processes/<adapter>`                                                        |
| the connection keys of the BPMS' own Spring integration           | `vanillabp.adapters.<id>.*`                           | the exact keys are the adapter's business and stand in its wiki                                             |
| `vanillabp.workflow-modules.<m>.adapters.<bpms>.<key>`            | `vanillabp.adapters.<id>.<key>`                       | the module level is still there and still wins, it is no longer the place a value has to be written         |
| `…adapters.<bpms>.tenant-id`                                      | `vanillabp.adapters.<id>.tenant-id`                   | the tenant belongs to the BPMS instance now. It only names the tenant, and without it the module id is used |
| `…adapters.<bpms>.use-tenants: false`                             | `vanillabp.adapters.<id>.name-clash-avoidance: none`  | one setting replaces it, see below                                                                          |
| `vanillabp.allow-connectors`                                      | `vanillabp.adapters.<id>.allow-connectors`            | a Camunda 8 concept, so the key moved under the adapter                                                     |
| `vanillabp.resilience.*`                                          | gone                                                  | it was mapped and validated and never read. Retry settings come back per adapter with their first consumer  |

#### Keeping workflow modules apart

Version 1 isolated a workflow module through a BPMS tenant and let you switch that off per module
(`use-tenants`). Version 2 makes it one setting with three values, resolvable per workflow, workflow
module and adapter.

```yaml
vanillabp:
  adapters:
    camunda7:
      name-clash-avoidance: by-adapter   # a tenant per workflow module, version 1's behaviour
      # name-clash-avoidance: use-prefix # no tenant, VanillaBP prefixes the identifiers instead
      # name-clash-avoidance: none       # nothing is scoped, the old 'use-tenants: false'
```

`by-adapter` is the default because that is what version 1 deployed on both Camunda adapters, so an
application which configures nothing keeps its tenants and finds the workflows it started under
version 1. What a BPMS needs for it is the BPMS' business: Camunda 7 needs nothing, a Camunda 8
cluster needs multi-tenancy and the tenant, and the Process-Engine-API has no isolation of its own
and refuses the mode while deploying.

Switching the mode is a migration in the BPMS rather than a property change, because the identifiers
the BPMS knows change with it and workflows started earlier would no longer be found. The way to do
it is a second adapter id which differs only in this setting, put first in `prioritized-adapters`,
and the wiki page [BPMS migration](https://github.com/vanillabp/adapter-platform-integration/wiki/BPMS-migration)
walks through it.

Under `use-prefix` VanillaBP rewrites the identifiers of a workflow module: BPMN process ids, call
activity references, message and signal names, escalation and error codes, and task definitions.
Your business code, your `ProcessService` calls, your BPMN files and your configuration keep the
plain names. Two new startup checks come with the mode: a workflow module id must not contain the
separator `__`, and two processes must not end up with the same prefixed identifier.

#### What you may delete

An application with one adapter dependency and one workflow module needs no `vanillabp.*` property
at all, because the classpath is the configuration. The adapter section, the module section and
`resources-location` are all derived, and only what the BPMS itself needs is left. Where several
BPMS serve the application, `vanillabp.prioritized-adapters` alone is enough as long as the ids are
adapter types. Everything you configured explicitly keeps working and is never overruled by a
derived value.

#### A workflow module's own files are defaults

A file named after a workflow module (`loan-approval.yaml`, `loan-approval-prod.properties`) carries
defaults, and everything the application configures wins over it, whichever file the application
uses. That is what version 1 did, and both platforms answer it that way now. From strongest to
weakest: system properties, environment variables, the application's own configuration wherever it
lives, `{module}-{profile}`, `{module}`. Inside a module the profile variant still beats the plain
file.

Version 2 adds one thing: these values are proper configuration properties now. Version 1 kept them
out of the environment, so `${...}` and `@Value` could see them while `Environment#getProperty` and
`@ConfigurationProperties` could not. A workflow module can therefore be bound to a
`@ConfigurationProperties` class of its own.

#### The outbox and the delivery log

An adapter whose BPMS is remote cannot take part in your local transaction, so a workflow is started
in two phases through a transaction outbox. Coming from version 1 that is new, and it is asked of an
application on Camunda 7 as well, which version 1 never did. Three tables are created in the database
your workflow aggregates live in: the outbox store, the payloads of the calls which carry one, and
the log of processed task deliveries which keeps a redelivered task from running your handler twice.
A payload lies beside its entry rather than inside it, which is why it has a table of its own.

`vanillabp.outbox.create-schema` defaults to `true`, so an application which configures nothing gets
its tables on the first boot. An application whose schema is a reviewed artifact sets the switch to
`false` and applies the statements itself. They ship as `io.vanillabp:vanillabp-schema`, which holds
a Liquibase changelog and generated Flyway scripts per database and pulls no runtime along. With the
switch off the startup checks that the tables are there and names the one which is missing.

The names are `VANILLABP_PHASE_TWO_OUTBOX`, `VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD` and
`VANILLABP_TASK_DELIVERY`. An application which renames the outbox through
`vanillabp.outbox.jdbc.table` renames the payload table with it, because that name is the outbox
name plus a suffix unless `vanillabp.outbox.jdbc.payload-table` says otherwise. On MongoDB the
same rule applies to the collections.

Where the first adapter of the priority list needs the outbox and none can be resolved, the
application does not boot and the message names what to add. The transaction the outbox entry rides
in is checked in the same moment. What the outbox guarantees, and what it does not, is on the wiki
page [Spring Boot integration](https://github.com/vanillabp/adapter-platform-integration/wiki/Spring-Boot-integration#what-the-outbox-guarantees).

#### What the startup says about a configuration

A defect in the configuration shows up while the application starts rather than at the first
workflow. An adapter section which is absent lets the application boot and earns a message naming
the keys to add, a section which contradicts itself ends the boot naming what is missing. Messages
name property keys and never values, so nothing echoes a credential.

An environment variable cannot introduce an adapter id or a workflow module id which no
configuration file declares. A `VANILLABP_*` variable whose id segment matches nothing configured
ends the startup with a message, because it looks like an override and is none.

### Your code

#### Three `ProcessService` overloads are gone

The overloads taking a message object, whose class simple name became the message name, were
removed. Name the message instead. What version 1 and version 2 share is that message content never
travels to the BPMS, so incorporate the data into the aggregate before you correlate.

```java
// before
processService.correlateMessage(ride, rideConfirmation);
processService.correlateMessage(ride, rideConfirmation, correlationId);
processService.startWorkflowByMessage(ride, rideRequested);

// after
processService.correlateMessage(ride, "RideConfirmation");
processService.correlateMessage(ride, "RideConfirmation", correlationId);
processService.startWorkflowByMessage(ride, "RideRequested");
```

`@BpmnProcess.primary()` is gone as well: whether a process is the primary one follows from where it
is declared. Both changes belong to `spi-for-java` and its own
[upgrade notes](https://github.com/vanillabp/spi-for-java/blob/main/UPGRADE.md) carry them with the
version line they came in. A hand-written `ProcessService` of your own keeps compiling: the query
methods and `startWorkflowByMessage(A, String)` are `default` methods now, and only
`getWorkflowModuleId()` stays abstract.

#### One class declares the processes of one workflow aggregate

`@WorkflowService.secondaryBpmnProcesses` is honoured now, where the version-1 platform integration
ignored it. Every BPMN process id a class declares is wired for task processing, and a secondary
entry has to name an explicit `bpmnProcessId`.

An application which spread the processes of one workflow aggregate over several classes, each with
its own `bpmnProcess`, has to move them together. One class declares the process `startWorkflow`
starts and the others belong into its `secondaryBpmnProcesses`; handlers may still live in separate
classes as long as each of them declares that same `bpmnProcess`. Version 2 says so while the
application starts, where version 1 picked one of the processes by the order the classes happened to
be found in.

#### Remove `@Transactional` from workflow services

Version 1 asked you to write `@Transactional(noRollbackFor = TaskException.class)` on a workflow
service. VanillaBP 2 owns the transaction around a `@WorkflowTask` method itself, load the aggregate,
invoke, save, and the rule that a `TaskException` commits the aggregate changes while completing the
task with a BPMN error is built in.

The version-1 line still boots, because the startup check accepts a rollback rule which excludes
`TaskException`. What ends the boot is an annotation joining VanillaBP's transaction without such a
rule, since a `TaskException` would then throw the aggregate changes away. The check also sees an
annotation inherited from a superclass or an interface and one hidden inside an annotation of your
own. Where the annotation sits on a bean your handler calls, no check can see it and the task fails
at runtime with a message naming the same remedies.

Look at what else the class does before you delete the line. Small applications keep the
`@WorkflowTask` methods and the business methods their API calls in one class, and deleting the
annotation there takes the transaction away from the business methods too. Every call into
`ProcessService` needs an active transaction, so a `startWorkflow` from an endpoint fails once the
annotation is gone, and nothing reports it: the application starts and only the paths entered from
your API break. Split the class or keep the version-1 annotation on it.

A `javax.transaction.Transactional` carried over from Spring Boot 2 is read by neither Spring
Framework 7 nor Quarkus. It does nothing at all, and the startup warns about it so the transaction
boundary you believe in does not stay imaginary.

#### A workflow service is found because it is a bean

VanillaBP does not scan the classpath for classes carrying `@WorkflowService` any more. It walks the
bean definitions of the application and keeps the ones whose class carries the annotation, which is
what makes a start noticeably faster.

An application whose workflow service is no Spring bean therefore gets no `ProcessService` for it.
Such a class could never serve a task anyway, because the handler object is resolved through the
bean factory, and what the application meets is a warning naming the BPMN process nobody serves and
the file it came from. Make the class a bean, with `@Service` or with a `@Bean` method whose return
type is the workflow service class. A workflow service registered by one profile only is found while
that profile is active, which is the point of reading the bean definitions of this run.

`@WorkflowService` belongs on the class holding the `@WorkflowTask` methods. On an interface, or on
an annotation of your own composing it, both platforms refuse it with a message naming the interface
and the classes implementing it. A class inheriting the annotation from an annotated superclass is
the supported way to declare something for several classes, and the class VanillaBP works with is
the subclass.

While the application starts it reports the handler methods it cannot see: a `@WorkflowTask`,
`@WorkflowStartedByBpms` or `@WorkflowEnded` method which is not public, and a method overriding an
annotated one without repeating the annotation, which Java never inherits. Neither was ever wired,
in version 1 no more than now, and the report names the method and says what to do about it.

#### The workflow aggregate says what the BPMS sees

`@SyncWithBPMS` and `@NoSyncWithBPMS` are real now. Version 1 documented them and never shipped
them, so an aggregate carrying no annotation hands every attribute to the BPMS, and everything
hanging on those attributes with it. That is what your application brings with it: no annotations,
so everything is shared.

What is shared by default has not changed. Every adapter still shares everything an aggregate holds
unless the aggregate says otherwise, so you do not have to touch your Java code for this.

What is new is that version 2 does not start such a workflow before you have said so. The message
names the workflow, the aggregate and the attributes which would travel, and it offers two ways on.
The recommended one is to say what the models really need: `@NoSyncWithBPMS` on the aggregate class,
`@SyncWithBPMS` on each attribute a BPMN expression reads. An aggregate which keeps a single
attribute back starts without anything else. The other way is the permission, for the case where the
models really may read everything:

```yaml
vanillabp:
  workflow-modules:
    my-module:
      workflows:
        MyProcess:
          allow-full-sync-with-bpms: true
```

Write it before you upgrade and the upgrade does not begin with a failed start. It is one line of
configuration. An application which is happy to share everything keeps its code as it is and says so
once per workflow. The permission belongs to the workflow and is not inherited: the same line at a
workflow module, at the application or in an adapter section is refused with a message saying where
it goes. A permission from above would cover the next workflow somebody adds, and that is the
workflow nobody looked at. An aggregate holding nothing but its id needs neither way, because that
value reaches the BPMS in any case. The reasoning is decision 66 in
[`DECISIONS.md`](./DECISIONS.md).

Two rules are worth knowing before you annotate the first attribute. Every attribute inherits the
behaviour of its owner until it says otherwise, and the class mode is derived from the attributes
where the class itself says nothing: attributes marked `@SyncWithBPMS` mean the class shares nothing
else, attributes marked `@NoSyncWithBPMS` mean it shares everything else. Mixing both on the
attributes of a class which states no mode ends the boot naming the class and the attributes.
Nothing is ever read back: a process variable never updates the aggregate, and the only variables
VanillaBP reads are those a `@TaskParam` asks for.

#### A `java.util.Calendar` is refused, a `Date` travels as an instant

An attribute of type `java.util.Calendar` cannot be shared. The text a `Calendar` prints is the
debug form of its implementation, 769 characters naming every field of it. No model reads a point in
time out of that and nothing reads it back into a `@TaskParam`, so version 2 refuses it: an
application whose aggregate shares such an attribute does not start, and the message names the
attribute, its class
and its type. Share an `Instant` instead, and a `TimeZone` or a `ZoneId` next to it where the zone
matters. Where no model needs the attribute, `@NoSyncWithBPMS` is the answer and the application
starts. A `Calendar` inside a nested object or as the element of a collection is refused the same
way. Look for one before you upgrade:

```
grep -rn "java.util.Calendar" --include="*.java" .
```

A `java.util.Date` travels as the instant it holds rather than as the text a `Date` prints.

| what the aggregate holds |      what version 1 wrote       |   what version 2 writes    |
|--------------------------|---------------------------------|----------------------------|
| a `Date` at 21:55:30.123 | `Wed Sep 16 21:55:30 CEST 2026` | `2026-09-16T19:55:30.123Z` |

The milliseconds survive where the old text dropped them, and the text no longer depends on the
server which wrote it. A BPMN expression comparing the old text reads something else from now on, so
read such an expression as a date, or share a `String` your own code writes and keep the form in your
hand. A `java.util.TimeZone` travels as the id of its zone, `Europe/Berlin`, and a parameter
declared `TimeZone` reads it back. Every other
attribute whose text nothing reads back is named while the application boots, a `java.util.Locale`
or a `java.net.URI` among them, and that one is a warning. The reasoning is decision 58 and decision
59 in [`DECISIONS.md`](./DECISIONS.md), and
[`migration-adapter/README.md`](./migration-adapter/README.md) lists the types which travel both
ways.

#### A value which does not fit its `@TaskParam`

A `@TaskParam`, a parameter of a `@WorkflowStartedByBpms` method and an attribute of an aggregate a
BPMS-initiated start builds all get their value through one conversion. It converts a number through
its decimal form and hands the result over only where it reads back as the same number. Where it
does not, the invocation fails with a message naming the value, the type and the method, and the
BPMS raises the incident it raises for any other failing task.

Version 1 refused the same pairs, so this is a return rather than a new rule. It bound the parameter
by raw reflection, and a pair reflection could not satisfy threw `argument type mismatch` with no
cause, no parameter name and no method name. What version 1 accepted is accepted here as well.

A value which travels as text is served where version 1 could not serve it. An aggregate shares an
enum as the name of its constant and a value type such as a `UUID`, a `LocalDate` or a `Duration` as
the text that type writes itself, and a parameter declaring one of those types receives the value
now. A workaround which declares a `String` and parses it in the handler keeps working, so nothing
has to change on upgrading. `java.util.Calendar` and `java.util.Locale` are the exception: their
text does not carry the value back, they stay refused, and the message names the type to declare
instead. The measurement is decision 57 and the reasoning decision 55 in
[`DECISIONS.md`](./DECISIONS.md).

#### `version` decides which method serves a task

The `version` attribute of `@WorkflowTask` exists since version 1 and was never read there, so every
method served every version. Version 2 reads it, on `@WorkflowStartedByBpms` and `@WorkflowEnded` as
well, and matches it against the version of the deployed process definition as the BPMS counts it.
That is never a version your application invents. A specification may also name a version tag of the
model, and `>=3` and `<=3` work as version 1's README described them.

`@BpmnProcess(version = ...)` is the fallback of the three annotations. A method naming no version
serves the range its process was declared with, and a method naming one keeps it word by word.
Version 1 read that attribute nowhere either, so an application which wrote it believing it worked
has to look at every `@BpmnProcess` it declares: the methods of such a class now serve what the class
says and stop being called outside that range.

Three things change for an application which already carries the attribute anywhere. Ranges which
were meant to be disjoint really are disjoint, so a version served by no method fails the delivery
instead of running the first one. A BPMS which reports no version at all reaches methods without the
attribute only. And two methods wired to one BPMN element with overlapping ranges end the boot naming
both. An application which never wrote the attribute sees no change, because the default `*` leaves
every method serving every version.

### Behaviour under unchanged code

#### A workflow is started in two phases

`startWorkflow` no longer talks to a remote BPMS inside your transaction. An outbox entry rides your
transaction and the workflow is created after the commit, so a rolled-back transaction cannot leave
a workflow behind any more. In exchange the workflow is visible in the BPMS a moment later, and the
start is at-least-once: a duplicate is unlikely, because VanillaBP probes before it dispatches an
entry a second time, and it is not impossible. Never build on a start which happens exactly once.

Camunda 7 works that way too now. It delivers tasks inside the engine's transaction, and every
operation which progresses a workflow runs after your commit so it can be repeated when it loses a
concurrency conflict.

#### A repeated delivery does not run your handler again

A remote BPMS hands a task out again whenever it did not learn the result, after a crash between
your commit and the report to the BPMS for instance. Version 1 passed that straight through and
every handler had to guard itself. Version 2 records each processed delivery in the transaction of
the handler and answers a repeated delivery from that record.

Keep the guards in your handlers. They still cover what a record cannot: two deliveries running at
the same time, and everything a handler does outside its transaction. They also carry the tasks the
upgrade left open. A task which was already open before the new version first ran has no record, so
its next delivery runs the `@WorkflowTask` method a second time, and nothing can repair that: a
record would have to claim the handler ran and left the task open, and an activated job which is
still there may equally be a handler which crashed halfway. The startup counts those tasks where
both the store and the BPMS can answer, and the number falling to zero is when the guards have
carried the case.

`vanillabp.adapters.<id>.deduplicate-deliveries` switches the record off, per workflow module,
workflow and task as well. Where the aggregates live in a persistence VanillaBP brings no store for,
one warning per BPMN process names the bean to provide and the property to set, and the application
boots and behaves as version 1 did.

#### An operation finds its BPMS by asking

`completeTask`, `cancelTask`, the user-task operations and `correlateMessage` ask the prioritized
adapters which of them holds the workflow, and they remember the answer. The task operations usually
skip the question, because the record written when the task was delivered names the BPMS which
handed it out.

A task or a workflow no BPMS knows raises a guiding `TaskNotFoundException` or
`WorkflowNotFoundException`, and one already completed makes the operation a warned no-op. While a
BPMS is unreachable the operation fails rather than falling back to another BPMS, because quietly
starting to use the wrong one would be worse.

An operation right after a start is the case a remote BPMS is slow with. A BPMS which answers from
an eventually consistent read model reports a workflow it created moments ago as unknown, so
VanillaBP waits out the window the adapter names before it gives up, and it does so only where it
knows which adapter holds the workflow. An application on several nodes should therefore provide a
shared election cache, see [what operations has to watch](#what-operations-has-to-watch).

The ids the viewer API hands out are namespaced `<adapter id>#<BPMS specific id>` and opaque. Pass
them back unchanged and never parse or compose one.

#### One error contract for `@WorkflowTask` methods

Every BPMS follows the same rule now. A normal return completes the task. A `TaskException` becomes
a BPMN error with the aggregate changes committed. Any other exception rolls the transaction back and
leaves the retry to the BPMS.

#### Decision tables are deployed with the workflow module

A workflow module may put `.dmn` files next to its BPMN files, and the boot deploys them to the same
BPMS, in the same deployment, with the same tenant. An application which already keeps such files at
its `resources-location` and deploys them by a mechanism of its own now deploys them twice. Both
Camunda engines answer a redeployment of an unchanged file by keeping what they have, so this is
usually invisible, but an application deploying a different version of a decision by its own
mechanism has two writers and has to pick one. An application without DMN files there notices
nothing.

Under `use-prefix` the decision ids of those files are rewritten like the process ids, and the
reference of a business rule task with them. A business rule task pointing at a decision this module
does not deploy therefore breaks under that mode. Deploy the decision with the module, or scope the
module by tenant, where nothing is rewritten.

#### Two writers on one workflow aggregate

A BPMN process holding more than one token has two branches writing the same aggregate, one in the
transaction VanillaBP owns for its task and one in the transaction your application opens around its
API call. A persistence layer writing the whole record loses what the branch committing first wrote.
VanillaBP does not resolve that, it makes it visible, which version 1 did not.

Your application sees two messages and gets no new property. A warning per BPMN process whose model
can produce concurrent tokens while its workflow aggregate has no version attribute names the
elements and the ways out. An error names a commit of a transaction VanillaBP owns which failed on a
version conflict, and the exception is passed on unchanged so the BPMS applies its retry semantics.
There is deliberately no retry inside the framework: it would repeat whatever the handler did before
the commit failed, a call to a remote API for instance, while hiding that anything went wrong. The
wiki page
[Workflow aggregates](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates#two-writers-on-one-aggregate)
describes the four ways an application can avoid the collision.

#### What the startup says about the versions your BPMS still holds

The first boot of the upgraded application reads the process versions its BPMS still holds, asks
whether this application serves their task definitions and counts the workflows running on them.
Version 1 said nothing of the sort, so what an upgrade brings along is named for the first time: a
task of an older version which no `@WorkflowTask` method serves is a warning, and an error where
workflows still run on that version.

`vanillabp.adapters.<id>.outfaded-versions` names the versions you decided to stop serving, in the
grammar of the `version` attribute, and `outfaded-versions-in-use` says whether a workflow still
running on one of them is worth a log line or the end of the boot. Both are resolvable per workflow
module and workflow.

A workflow module may rename a BPMN process and keep serving the workflows which still run under the
old name, which version 1 could do as well by declaring the old id as a secondary process. The check
covers that case now, so the first boot after the upgrade says more about a renamed process than any
boot before it. Mind the numbering while you fade those versions out: every BPMN process id is
counted from one by the BPMS, so version 2 of the old id and version 2 of the new one are different
models, and `outfaded-versions` for the old ones belongs at the workflow level under the old id. The
wiki page
[Renaming a BPMN process](https://github.com/vanillabp/adapter-platform-integration/wiki/Renaming-a-BPMN-process)
walks through both ways of doing it.

#### What your running workflows bring with them

Whether a feature of version 2 reaches a workflow which was already running depends on where the
BPMS keeps what the feature hangs on. Camunda 7 attaches everything while its engine parses a process
definition, so every version the engine holds is reached, the ones version 1 deployed included.
Camunda 8 writes its listeners into the model it deploys and a running instance stays on the version
it was started on, so a workflow which was already running never gets them. Wherever a feature rests
on something written into the model, that is the line along which it holds or does not.

The stores start empty and none of them needs adopting. Outbox entries are written per operation, so
a workflow started under version 1 needs none. The election cache is empty after any restart and its
entries are hints, so the first operation on each workflow pays one probing walk. The check for a
renamed adapter id stays quiet, and rightly so, because version 1 persisted no adapter id: it had one
adapter and its id was its type.

Two things an upgrading application should look at in its own code, both on Camunda 8. Version 1
handed the whole workflow aggregate to every command, so instances started back then carry a
variable for everything its JSON serialization could read. A `@JsonProperty` renaming the aggregate's
id attribute breaks every probe for such an instance, because version 1 wrote the JSON name and
version 2 reads the name the persistence layer reports. And an attribute version 1 serialized as a
field keeps its last version-1 value for good, because version 2 reads getters only. Give such an
attribute a getter and share it.

### What operations has to watch

The outbox is the one new thing in the picture. Entries which cannot be dispatched are retried with
a growing distance, `vanillabp.outbox.attempt-frequency` to the first retry and doubling up to
`vanillabp.outbox.max-attempt-frequency`, and an entry is blocked after
`vanillabp.outbox.block-after-attempts` tries. A blocked entry waits for somebody, so watching for
one is an operations duty. Dispatched entries are marked done and cleaned up after
`vanillabp.outbox.retention`, seven days by default.

The entries are dispatched on a few threads of their own, `vanillabp.outbox.dispatch-threads`, four
by default. Which thread takes an entry is decided by the workflow aggregate, so what belongs to one
workflow keeps its order while different workflows travel at the same time. Version 1 progressed a
workflow in the thread which was already there, a job executor thread on Camunda 7 or the committing
thread on Camunda 8, so this is a new place where your database connections are used. Count the
threads into the pool of the database your workflow aggregates live in.

The records of processed task deliveries have a retention of their own,
`vanillabp.delivery.retention`, which follows the outbox retention where it is not set. The two
windows point in different directions: on the outbox side the retention only decides how long a
dispatched entry stays readable during support, while on the delivery side it decides whether a
redelivery arriving later runs your `@WorkflowTask` method a second time. An installation which
lowered the outbox retention to keep its table small was shortening a correctness window with the
same hand, and it can now keep the small table and give the records the period they need.

An application on several nodes should provide one bean implementing
`io.vanillabp.integration.spi.WorkflowAdapterCache`, so the nodes share which BPMS holds which
workflow. The built-in cache is in memory and bounded by
`vanillabp.workflow-adapter-cache.max-entries` and `.time-to-live`. Entries are hints, so losing one
costs an extra probing walk and never correctness. VanillaBP deliberately ships no distributed
implementation.

Where Micrometer is on the classpath VanillaBP publishes what it does with your work under
`vanillabp.*`, and where your platform has a health endpoint the adapters contribute what they know
about their BPMS under the name `vanillabp`. Every task delivery and every dispatch runs inside a
set of MDC keys naming the adapter, the workflow module, the BPMN process, the workflow aggregate,
the task and the delivery. The wiki page
[Observability](https://github.com/vanillabp/adapter-platform-integration/wiki/Observability) names
every meter and the question it answers, and carries a log pattern to paste.
