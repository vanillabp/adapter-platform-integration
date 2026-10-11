# Upgrade notes

This list is for contributors. It says what a VanillaBP 1 application has to do, sorted by version
line. It describes the step to the release, not how the release was built. Git has the development
history. These entries feed the
[migration guide](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1)
for users. The guide tells the same story in a narrative form, and where the two disagree, the guide
wins. The same file exists for
[spi-for-java](https://github.com/vanillabp/spi-for-java/blob/main/UPGRADE.md), for the
[Camunda 7 adapter](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/blob/main/UPGRADE.md) and for the
[Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/blob/main/UPGRADE.md).
What your BPMS (business process management system) asks of you on top of this is in the file of its
adapter.

An entry is needed where a version-1 application behaves differently or has to change something.
Everything else belongs in the wiki, in [`DECISIONS.md`](./DECISIONS.md), or nowhere.

## 2.0

The sections follow the order in which you carry out an upgrade.

### The runtime and the dependencies

|             |   version 1   | version 2 |
|-------------|---------------|-----------|
| Java        | 17            | 21        |
| Spring Boot | 3.5           | 4.1       |
| Quarkus     | not supported | 3.37      |

Spring Boot 4 split the single artifact `spring-boot-autoconfigure` into one module per technology.
So a few imports in your own code move:

* `@EntityScan` now comes from `org.springframework.boot.persistence.autoconfigure`,
* `HibernateJpaAutoConfiguration` from `org.springframework.boot.hibernate.autoconfigure`,
* `MongoClientSettingsBuilderCustomizer` from `org.springframework.boot.mongodb.autoconfigure`.

The test slices moved out of `spring-boot-starter-test`, and each needs a dependency of its own. For
`@DataJpaTest`, for example, that is `org.springframework.boot:spring-boot-data-jpa-test`. This is a
change of Spring, not of VanillaBP, so Spring Boot's own migration guide describes it.

Some modules of your application must not depend on a BPMS. In version 1 such a module depended on
`io.vanillabp:spring-boot-support`. Now it depends on `io.vanillabp:vanillabp-spring-boot-support`,
and next to it there is `io.vanillabp:vanillabp-quarkus-support`. Both carry the VanillaBP API which
such a module compiles against. Both also bring the integration SPI (service provider interface,
`io.vanillabp:vanillabp-integration-spi`) along. It holds the interfaces your application may
implement. The adapter artifacts were renamed as well, and the file of your adapter names them.

Persistence needs no change of dependencies. Version 1 stored workflow aggregates through Spring
Data, JPA or MongoDB, and version 2 does that out of the box. On Quarkus the built-in support covers
more: a Panache repository, a Panache active record and a Spring Data repository all work.

### Workflow modules are declared by a file

Version 1 read the id of a workflow module from `spring.application.name`, from the name of the
module's configuration file, or from a static bean method which handed over a
`WorkflowModuleProperties`. Version 2 reads it from the text file `META-INF/workflow-module`. The
content of the file is the id, and there is one file per module. So both platforms find a module the
same way.

```
src/main/resources/META-INF/workflow-module      # content: loan-approval
```

`WorkflowModuleProperties` no longer exists. So the bean method has to go, and a project which keeps
it does not compile. The properties class it named stays as it is, because binding a module's own
configuration has not changed. Keep the ids you had. They name BPMS tenants and resource
directories. A new id cuts the running workflows of that module off from their configuration.

An application without any such file is one single global workflow module. In version 1 an
application with one use case was the same.

### Configuration

#### `default-adapter` becomes `prioritized-adapters`

Version 1 named the one adapter of a module. Version 2 names an ordered list of adapters, because
several BPMS may serve one application. This turns a BPMS migration into a matter of configuration.
The key sits at the same three levels as before.

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

The first entry starts new workflows. The others are asked about workflows started earlier. The most
specific value which is not empty wins as a whole. So a workflow's list replaces the module's list
and is not merged into it.

#### An adapter id names one BPMS instance

In version 1 an adapter's settings lived per workflow module
(`vanillabp.workflow-modules.<m>.adapters.<adapter>.*`). The connection to the engine was configured
through the BPMS' own Spring integration. In version 2 everything one BPMS instance needs lives in
the section of its adapter id, `vanillabp.adapters.<id>.*`. No Spring configuration specific to the
BPMS is involved.

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

An id which is not an adapter type needs `vanillabp.adapters.<id>.type`. This is how two instances
of one BPMS are told apart. Two ids of one type also have to differ in something the adapter
decides, for example a second address or a second datasource. Version 1 had one adapter, and its id
was its type. So the smallest step is to keep the type as the id. Still, choose the names with care.
VanillaBP stores the adapter id, so if you rename one later, nothing serves those workflows any more.

An adapter-specific value is still resolved with the most specific place first, across task,
workflow, workflow module and adapter. So `vanillabp.workflow-modules.<m>.adapters.<id>.<key>` still
wins over `vanillabp.adapters.<id>.<key>`.

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

Version 1 kept a workflow module apart through a BPMS tenant, and let you switch that off per module
(`use-tenants`). Version 2 makes it one setting with three values. You can set it per workflow,
workflow module and adapter.

```yaml
vanillabp:
  adapters:
    camunda7:
      name-clash-avoidance: by-adapter   # a tenant per workflow module, version 1's behaviour
      # name-clash-avoidance: use-prefix # no tenant, VanillaBP prefixes the identifiers instead
      # name-clash-avoidance: none       # nothing is scoped, the old 'use-tenants: false'
```

`by-adapter` is the default, because version 1 deployed this way on both Camunda adapters. So an
application which configures nothing keeps its tenants and finds the workflows it started under
version 1. What a BPMS needs for this mode is up to the BPMS. Camunda 7 needs nothing. A Camunda 8
cluster needs multi-tenancy and the tenant. The Process-Engine-API has no isolation of its own and
refuses the mode during deployment.

Switching the mode is a migration in the BPMS, not a change of a property. The identifiers the BPMS
knows change with it, and workflows started earlier would no longer be found. To switch, add a second
adapter id which differs only in this setting, and put it first in `prioritized-adapters`. The wiki
page [BPMS migration](https://github.com/vanillabp/adapter-platform-integration/wiki/BPMS-migration)
walks you through it.

Under `use-prefix` VanillaBP rewrites these identifiers of a workflow module: BPMN process ids, call
activity references, message and signal names, escalation and error codes, and task definitions.
Your business code, your `ProcessService` calls, your BPMN files and your configuration keep the
plain names. The mode brings two new checks at startup. A workflow module id must not contain the
separator `__`. And two processes must not end up with the same prefixed identifier.

#### What you may delete

An application with one adapter dependency and one workflow module needs no `vanillabp.*` property
at all, because the classpath is the configuration. The adapter section, the module section and
`resources-location` are all derived. Only what the BPMS itself needs is left. If several BPMS serve
the application, `vanillabp.prioritized-adapters` alone is enough, as long as the ids are adapter
types. Everything you configured explicitly keeps working. A derived value never overrules it.

#### A workflow module's own files are defaults

A file named after a workflow module (`loan-approval.yaml`, `loan-approval-prod.properties`) carries
defaults. Everything the application configures wins over it, no matter which file the application
uses. Version 1 did the same, and now both platforms work this way. From strongest to weakest:
system properties, environment variables, the application's own configuration wherever it lives,
`{module}-{profile}`, `{module}`. Inside a module the profile variant still beats the plain file.

Version 2 adds one thing: these values are now real configuration properties. Version 1 kept them
out of the environment. So `${...}` and `@Value` could see them, but `Environment#getProperty` and
`@ConfigurationProperties` could not. Now a workflow module can be bound to a
`@ConfigurationProperties` class of its own.

#### The outbox and the delivery log

An adapter whose BPMS is remote cannot take part in your local transaction. So a workflow is started
in two phases, through a transaction outbox. This is new compared to version 1, and applications on
Camunda 7 need it too. Four tables are created in the database of your workflow aggregates:

* the outbox store,
* the payloads of the calls which carry one,
* the log of processed task deliveries, which keeps a redelivered task from running your handler
  twice,
* the claim which says which node removes the old rows tonight.

A payload lies beside its entry, not inside it. That is why it has a table of its own.

Version 1 had no outbox. On Camunda 8 a listener completed the job after your transaction had
committed. So nothing about that call was written down, and a node which died in between lost it.
Version 1 left no table of two-phase calls behind, so there is nothing to drain or carry over.

`vanillabp.outbox.create-schema` defaults to `true`. So an application which configures nothing gets
its tables on the first boot. If your schema is a reviewed artifact, set the switch to `false` and
apply the statements yourself. They ship as `io.vanillabp:vanillabp-schema`. It holds a Liquibase
changelog and generated Flyway scripts per database, and it pulls in no runtime. With the switch
off, the startup checks that the tables are there and names the one which is missing. It also names
any column which a later version of VanillaBP added to a table you created earlier.

The names are `VANILLABP_PHASE_TWO_OUTBOX`, `VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD` and
`VANILLABP_TASK_DELIVERY`. If an application renames the outbox through
`vanillabp.outbox.jdbc.table`, the payload table is renamed with it. Its name is the outbox name plus
a suffix, unless `vanillabp.outbox.jdbc.payload-table` says otherwise. The delivery table is a store
of its own and gets its name from `vanillabp.outbox.jdbc.delivery-table`. On MongoDB the same rules
apply to the collections.

The first adapter of the priority list may need the outbox while none can be resolved. Then the
application does not boot, and the message names what to add. The transaction which carries the
outbox entry is checked at the same moment. What the outbox guarantees, and what it does not, is on
the wiki page [Spring Boot integration](https://github.com/vanillabp/adapter-platform-integration/wiki/Spring-Boot-integration#what-the-outbox-guarantees).

#### What the startup says about a configuration

A defect in the configuration shows up while the application starts, not at the first workflow. If
an adapter section is missing, the application still boots, and a message names the keys to add. If
a section contradicts itself, the boot ends, and the message names what is missing. Messages name
property keys and never values, so no credential is ever printed.

An environment variable cannot add an adapter id or a workflow module id which no configuration file
declares. Take a `VANILLABP_*` variable whose id segment matches nothing configured. It ends the
startup with a message, because it looks like an override but is none.

### Your code

#### Three `ProcessService` overloads are gone

The overloads which took a message object were removed. They used the simple class name of that
object as the message name. Name the message instead. In version 1 and version 2 alike, message
content never travels to the BPMS. So put the data into the aggregate before you correlate.

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

`@BpmnProcess.primary()` is gone as well. Where a process is declared now decides whether it is the
primary one. Both changes belong to `spi-for-java`. Its own
[upgrade notes](https://github.com/vanillabp/spi-for-java/blob/main/UPGRADE.md) list them under the
version line they came in. A `ProcessService` you wrote by hand keeps compiling. The query methods
and `startWorkflowByMessage(A, String)` are now `default` methods, and only `getWorkflowModuleId()`
stays abstract.

#### One class declares the processes of one workflow aggregate

`@WorkflowService.secondaryBpmnProcesses` now takes effect. The version-1 platform integration
ignored it. Every BPMN process id a class declares is wired for task processing. A secondary entry
has to name an explicit `bpmnProcessId`.

Some applications spread the processes of one workflow aggregate over several classes, each with its
own `bpmnProcess`. They have to bring them together. One class declares the process which
`startWorkflow` starts, and the others belong into its `secondaryBpmnProcesses`. Handlers may still
live in separate classes, as long as each of them declares that same `bpmnProcess`. Version 2 tells
you about this while the application starts. Version 1 picked one of the processes, based on the
order in which the classes happened to be found.

#### A BPMN process belongs to one workflow aggregate

Classes of different workflow aggregates may no longer declare the same BPMN process in one workflow
module. It does not matter how each of them declares it, as `bpmnProcess` or in
`secondaryBpmnProcesses`. If two classes named the same `bpmnProcess`, version 1 logged a warning and
kept the class it found first. Version 2 stops the start. On Quarkus it stops already while the
application is built. The message names both classes, both aggregates and how each one declares the
process.

Decide what the process is:

- A workflow of its own: only classes of its own aggregate declare it, and the other class does not
  list it in `secondaryBpmnProcesses`. A call activity may still call it. Then the mappings of the
  call activity in your BPMS set up the data which the called workflow gets. But it is usually
  better to start and synchronise two workflows with messages.
- A step of another workflow: only the class of that workflow lists it in `secondaryBpmnProcesses`,
  and no class of another aggregate declares it.

Classes of one aggregate may still share a process. The wiki page
[Workflow tasks](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-tasks#a-process-belongs-to-one-workflow-aggregate)
has the details.

#### Remove `@Transactional` from workflow services

Version 1 asked you to write `@Transactional(noRollbackFor = TaskException.class)` on a workflow
service. VanillaBP 2 owns the transaction around a `@WorkflowTask` method itself: load the aggregate,
call the method, save. One rule is built in. A `TaskException` commits the changes of the aggregate
and completes the task with a BPMN error.

The version-1 line still boots, because the startup check accepts a rollback rule which excludes
`TaskException`. The boot ends if an annotation joins VanillaBP's transaction without such a rule,
because a `TaskException` would then throw the changes of the aggregate away. The check also sees an
annotation inherited from a superclass or an interface, and one hidden inside an annotation of your
own. If the annotation sits on a bean which your handler calls, no check can see it. Then the task
fails at runtime, with a message which names the same remedies.

Before you delete the line, look at what else the class does. Small applications keep the
`@WorkflowTask` methods and the business methods which their API calls in one class. If you delete
the annotation there, the business methods lose their transaction too. Every call into
`ProcessService` needs an active transaction. So once the annotation is gone, a `startWorkflow` from
an endpoint fails, and nothing reports it. The application starts, and only the paths entered from
your API break. Split the class, or keep the version-1 annotation on it.

Neither Spring Framework 7 nor Quarkus reads a `javax.transaction.Transactional` carried over from
Spring Boot 2. It does nothing at all. The startup warns about it, so that you do not rely on a
transaction boundary which does not exist.

#### A workflow service is found because it is a bean

VanillaBP no longer scans the classpath for classes which carry `@WorkflowService`. It walks the
bean definitions of the application and keeps those whose class carries the annotation. This makes
a start noticeably faster.

So if your workflow service is not a Spring bean, you get no `ProcessService` for it. Such a class
could never serve a task anyway, because the handler object is resolved through the bean factory.
The start ends with a message. It names the BPMN process nobody serves, the file it came from and
the class which is not a bean. Make the class a bean, with `@Service` or with a `@Bean` method whose
return type is the workflow service class. A workflow service which only one profile registers is
found while that profile is active. That is the point of reading the bean definitions of this run.

`@WorkflowService` belongs on the class which holds the `@WorkflowTask` methods. Both platforms refuse
it on an interface, or on an annotation of your own which includes it. The message names the
interface and the classes which implement it. To declare something for several classes, let them
inherit the annotation from an annotated superclass. That is supported, and VanillaBP works with the
subclass.

While the application starts, it reports the handler methods it cannot see. That is a
`@WorkflowTask`, `@WorkflowStartedByBpms` or `@WorkflowEnded` method which is not public. It is also
a method which overrides an annotated one without repeating the annotation, because Java never
inherits it. Neither kind was ever wired, in version 1 just as now. The report names the method and
says what to do about it.

#### The workflow aggregate says what the BPMS sees

`@SyncWithBPMS` and `@NoSyncWithBPMS` now exist. Version 1 documented them but never shipped them.
So an aggregate without an annotation hands every attribute to the BPMS, together with everything
which hangs on those attributes. Your application comes with exactly that: no annotations, so
everything is shared.

What is shared by default has not changed. Every adapter still shares everything an aggregate holds,
unless the aggregate says otherwise. So you do not have to touch your Java code for this.

What is new: version 2 does not start such a workflow until you have said that this is what you
want. The message names the workflow, the aggregate and the attributes which would travel. It offers
two ways forward. The recommended way is to say what the models really need: `@NoSyncWithBPMS` on
the aggregate class, and `@SyncWithBPMS` on each attribute which a BPMN expression reads. An
aggregate which keeps back even a single attribute starts without anything else. The other way is
the permission, for the case where the models really may read everything:

```yaml
vanillabp:
  workflow-modules:
    my-module:
      workflows:
        MyProcess:
          allow-full-sync-with-bpms: true
```

Write it before you upgrade, and the upgrade does not begin with a failed start. It is one line of
configuration. An application which is happy to share everything keeps its code as it is and says so
once per workflow. The permission belongs to the workflow and is not inherited. The same line at a
workflow module, at the application or in an adapter section is refused, and the message says where
it belongs. A permission from above would also cover the next workflow somebody adds, and nobody has
looked at that workflow yet. An aggregate which holds nothing but its id needs neither way, because
that value reaches the BPMS in any case. The reasoning is decision 66 in
[`DECISIONS.md`](./DECISIONS.md).

Know two rules before you annotate the first attribute. First, every attribute inherits the
behaviour of its owner until it says otherwise. Second, if the class itself says nothing, its mode
is derived from the attributes. Attributes marked `@SyncWithBPMS` mean the class shares nothing
else. Attributes marked `@NoSyncWithBPMS` mean it shares everything else. If a class states no mode
and its attributes mix both, the boot ends, and the message names the class and the attributes.
Nothing is ever read back. A process variable never updates the aggregate, and VanillaBP reads only
the variables which a `@TaskParam` asks for.

#### Only a boolean and a text reach the BPMS unasked

Once your aggregate says what it shares, version 2 looks at the TYPES of those values. A value of
the aggregate travels so that a model can decide on it, and the expression language of the BPMS
reads it. Two types mean the same in every expression language: the plain `boolean` and a text.
Every other type has to be declared first. An aggregate which shares a decimal, date or number
without a declaration does not start.

Your Java code does not have to change for this. The declaration is configuration, with one entry
per value:

```yaml
vanillabp:
  workflow-modules:
    my-module:
      workflows:
        MyProcess:
          declared-aggregate-values:
            - amount
            - shipping.*
```

An entry names a path in the aggregate. A plain name is the attribute itself. `shipping.*` is every
value below `shipping`, and `shipping.express` is one value below it. A single `*` covers every value
of the aggregate. A workflow which already allows the full sync needs just this one line. If you
write the entries before you upgrade, the upgrade does not begin with a failed start.

If you have the time, a getter is the better answer. A `boolean isLargeAmount()` on the aggregate
holds the expression in Java, and the model reads nothing but its name. Then a change to your data
model cannot break a model nobody touched. For a decision table, declaring is the intended way and
not a shortcut. Its inputs are numbers and dates, because that is what a decision table is for.

The wrapper `Boolean` is not the plain `boolean`, and the message says so in its own words. A `null`
in a gateway condition means something different on every BPMS, and a value nobody computed must not
decide anything.

The other direction is less strict, because no expression language is involved. Only your handler
reads a `@TaskParam`. You have to declare such a parameter in two cases only: where an adapter says
that its BPMS hands the type back as something else, and where the declaration names no type at
all. A `@TaskParam Object` is the second case. Camunda 7 answers it with a `BigDecimal` and
Camunda 8 with a `Double`. So it has to be declared and covered by tests.

```yaml
vanillabp:
  workflow-modules:
    my-module:
      workflows:
        MyProcess:
          tasks:
            myTask:
              declared-task-params:
                - amount
```

That key may also stand at the workflow, at the workflow module or at `vanillabp` itself. The most
specific level wins.

An adapter may answer that it cannot say what its BPMS does with a type. That answer costs a warning,
never a failed start. Camunda 8 drops the scale of a decimal inside the broker, and no adapter sees
that without a running cluster. And an application whose BPMS cannot be reached while it boots still
has to boot.

A value which the aggregate declares also has to be readable. A section of a larger process usually
sits in a sub-object. While the section has not run, that object is `null`, so `shipping.express`
resolves to nothing. This stops the workflow with a message which names the path and the link which
is `null`. There are two ways out: a boolean getter on the top level, or a declared substitute,
`shipping.express=false`. Writing just `false` would be the worst answer of all, because nobody can
tell it from a `false` which somebody computed.

#### A `java.util.Calendar` is refused, a `Date` travels as an instant

An attribute of type `java.util.Calendar` cannot be shared. The text a `Calendar` prints is the
debug form of its implementation: 769 characters which name every field of it. No model reads a
point in time out of that, and nothing reads it back into a `@TaskParam`. So version 2 refuses it.
An application whose aggregate shares such an attribute does not start, and the message names the
attribute, its class and its type. Share an `Instant` instead, and a `TimeZone` or a `ZoneId` next
to it if the zone matters. If no model needs the attribute, put `@NoSyncWithBPMS` on it, and the
application starts. A `Calendar` inside a nested object, or as the element of a collection, is
refused the same way. Look for one before you upgrade:

```
grep -rn "java.util.Calendar" --include="*.java" .
```

A `java.util.Date` travels as the instant it holds, not as the text a `Date` prints.

| what the aggregate holds |      what version 1 wrote       |   what version 2 writes    |
|--------------------------|---------------------------------|----------------------------|
| a `Date` at 21:55:30.123 | `Wed Sep 16 21:55:30 CEST 2026` | `2026-09-16T19:55:30.123Z` |

The milliseconds survive, while the old text dropped them. And the text no longer depends on the
server which wrote it. A BPMN expression which compares the old text reads something else from now
on. So read such an expression as a date. Or share a `String` which your own code writes, and keep
the form under your control. A `java.util.TimeZone` travels as the id of its zone, `Europe/Berlin`,
and a parameter declared as `TimeZone` reads it back. Every other attribute whose text nothing reads
back is named while the application boots, for example a `java.util.Locale` or a `java.net.URI`.
For those this is a warning. The reasoning is in decision 58 and decision 59 in
[`DECISIONS.md`](./DECISIONS.md), and
[`migration-adapter/README.md`](./migration-adapter/README.md) lists the types which travel both
ways.

#### A value which does not fit its `@TaskParam`

Three things get their value through one and the same conversion: a `@TaskParam`, a parameter of a
`@WorkflowStartedByBpms` method, and an attribute of an aggregate which a start by the BPMS builds.
The conversion turns a number into its decimal form. It hands the result over only if it reads back
as the same number. If it does not, the call fails with a message which names the value, the type
and the method. The BPMS then raises the same incident as for any other failing task.

Version 1 refused the same pairs, so this is an old rule coming back, not a new one. Version 1 bound
the parameter by raw reflection. A pair which reflection could not satisfy threw
`argument type mismatch`, with no cause, no parameter name and no method name. What version 1
accepted is accepted here as well.

A value which travels as text now works where version 1 failed. An aggregate shares an enum as the
name of its constant. It shares a value type such as a `UUID`, a `LocalDate` or a `Duration` as the
text which that type writes itself. A parameter which declares one of those types now receives the
value. A workaround which declares a `String` and parses it in the handler keeps working, so nothing
has to change when you upgrade. `java.util.Calendar` and `java.util.Locale` are the exception. Their
text does not carry the value back, so they stay refused, and the message names the type to declare
instead. The measurement is decision 57, and the reasoning is decision 55 in
[`DECISIONS.md`](./DECISIONS.md).

#### A workflow the BPMS starts needs a method which builds its aggregate

Version 1 did not support a process which the BPMS starts on its own, so there is nothing to
migrate. If you model a timer, signal or conditional start event in version 2, the workflow service
of that process needs a `@WorkflowStartedByBpms` method which RETURNS the workflow aggregate. Without
it the application does not start, and the message shows the method to write.

#### `version` decides which method serves a task

The `version` attribute of `@WorkflowTask` has existed since version 1, but version 1 never read
it. So every method served every version. Version 2 reads it, also on `@WorkflowStartedByBpms` and
`@WorkflowEnded`. It matches it against the version of the deployed process definition, as the BPMS
counts it. Your application never invents that version. A specification may also name a version tag
of the model. `>=3` and `<=3` work as version 1's README described them.

`@BpmnProcess(version = ...)` is the fallback for the three annotations. A method which names no
version serves the range its process was declared with. A method which names one keeps exactly that.
Version 1 did not read this attribute anywhere either. So if your application wrote it in the belief
that it worked, look at every `@BpmnProcess` it declares. The methods of such a class now serve what
the class says, and they are no longer called outside that range.

Four things change for an application which already has the attribute anywhere:

1. Ranges which were meant to be disjoint really are disjoint. So a version which no method serves
   fails the delivery, and the first method no longer runs. A task of the model which the boot
   deploys is the exception. If none of its methods covers the version the BPMS gives that model,
   the boot ends. The message names the task, the version and the methods with their ranges.
2. If a BPMS reports no version at all, only methods without the attribute are reached.
3. Two methods wired to one BPMN element with overlapping ranges end the boot, and the message names
   both.
4. An application which never wrote the attribute sees no change. The default `*` lets every method
   serve every version.

### Behaviour under unchanged code

#### A workflow is started in two phases

`startWorkflow` no longer talks to a remote BPMS inside your transaction. An outbox entry is written
in your transaction, and the workflow is created after the commit. So a transaction which was rolled
back can no longer leave a workflow behind. The price is that the workflow shows up in the BPMS a
moment later. And the start happens at least once. A duplicate is unlikely, because VanillaBP checks
before it sends an entry a second time. But it is not impossible. Never build on a start which
happens exactly once.

Camunda 7 now works this way too. It delivers tasks inside the engine's transaction. Every operation
which moves a workflow forward runs after your commit, so it can be repeated if it loses a
concurrency conflict.

#### A repeated delivery does not run your handler again

A remote BPMS hands out a task again whenever it did not learn the result, for example after a
crash between your commit and the report to the BPMS. Version 1 passed that straight through, and
every handler had to protect itself. Version 2 records each processed delivery in the transaction of
the handler. It answers a repeated delivery from that record.

Keep the guards in your handlers. They still cover what a record cannot: two deliveries which run at
the same time, and everything a handler does outside its transaction. They also cover the tasks
which the upgrade left open. A task which was already open before the new version first ran has no
record. So its next delivery runs the `@WorkflowTask` method a second time, and nothing can repair
that. A record would have to claim that the handler ran and left the task open. But an activated job
which is still there may just as well come from a handler which crashed halfway. Where both the
store and the BPMS can answer, the startup counts those tasks. Once the number falls to zero, the
guards have done their job for the upgrade.

`vanillabp.adapters.<id>.deduplicate-deliveries` switches the record off, also per workflow module,
workflow and task. VanillaBP may bring no store for the persistence your aggregates live in. Then one
warning per BPMN process names the bean to provide and the property to set. The application boots
and behaves as version 1 did.

#### An operation finds its BPMS by asking

`completeTask`, `cancelTask`, the user-task operations and `correlateMessage` ask the prioritized
adapters which of them holds the workflow. They remember the answer. The task operations usually
skip the question, because the record written at the delivery of the task names the BPMS which
handed it out.

A task or a workflow which no BPMS knows raises a `TaskNotFoundException` or a
`WorkflowNotFoundException`, with a message which guides you on. If the task or workflow is already
completed, the operation does nothing and logs a warning. While a BPMS cannot be reached, the
operation fails. It does not fall back to another BPMS, because quietly using the wrong one would be
worse.

A remote BPMS is slow with an operation right after a start. A BPMS which answers from an eventually
consistent read model reports a workflow it created moments ago as unknown. So VanillaBP waits out
the window which the adapter names before it gives up. It only does this where it knows which
adapter holds the workflow. So an application on several nodes should provide a shared election
cache, see [what operations has to watch](#what-operations-has-to-watch).

The ids which the viewer API hands out have the form `<adapter id>#<BPMS specific id>`, and you
should treat them as opaque. Pass them back unchanged. Never parse one or build one yourself.

#### A message starts only the process of its own `ProcessService`

`startWorkflowByMessage` of a `ProcessService` starts the process of that service and no other.
Version 1 passed the message to the BPMS, so any process with a start event for that message
started. Version 2 checks the name before anything is saved. If the model of that process has no
message start event with this name, the call throws an `IllegalArgumentException`. It names the
process and the messages which do start it.

If your code starts a process through the `ProcessService` of another process, call the
`ProcessService` of the process the message is meant for instead. The name is the plain one, the
same name version 1 took. If an adapter cannot name the messages of its model, there is no check,
and the start says so once per process.

#### One error contract for `@WorkflowTask` methods

Every BPMS now follows the same rule. A normal return completes the task. A `TaskException` becomes
a BPMN error, and the changes of the aggregate are committed. Any other exception rolls the
transaction back and leaves the retry to the BPMS.

#### A task something else serves can say so

Version 1 ended the start for every task of your process which had no `@WorkflowTask` method,
including user tasks. Version 2 does the same. What is new is a way out for a task which somebody
else serves on purpose. That may be a user task worked on through a task list, or a job which
another worker answers:

```properties
vanillabp.workflow-modules.<module>.workflows.<process>.tasks.<element-id>.implemented-externally=true
```

The task may be named by its element id or by its task definition. Where both are written, the
element id wins. If a task has a method and also this line at the task, the start ends. A line
written for a whole workflow or module covers only the tasks without a method. A line for a task
which no deployed model has is a warning. Nothing changes for an application which served every
task in version 1.
([Workflow tasks](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-tasks#a-task-something-else-serves))

#### A process nobody claims can say so

Version 1 ended the start for every BPMN process which no `@WorkflowService` declared. This
included a process which only sits in the same file as yours. Version 2 does the same. What is new is
a way out for a process which somebody else serves:

```properties
vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true
```

VanillaBP then deploys the process with its file and leaves it alone. No listener, no worker and no
check of the adapter touches it. A process which somebody else deployed into the same BPMS is not
touched either. Nothing changes for an application which declared every process of its files in version 1.
([Workflow tasks](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-tasks#a-process-somebody-else-serves))

#### Decision tables are deployed with the workflow module

A workflow module may put `.dmn` files next to its BPMN files. The boot deploys them to the same
BPMS, in the same deployment, with the same tenant. Some applications already keep such files at
their `resources-location` and deploy them in a way of their own. They now deploy them twice. Both
Camunda engines keep what they have when an unchanged file is deployed again, so you usually do not
notice. But if your own way deploys a different version of a decision, you have two writers and must
pick one. An application without DMN files there notices nothing.

Under `use-prefix` the decision ids of those files are rewritten like the process ids, and so is
the reference of a business rule task. So under that mode, a business rule task which points at a
decision this module does not deploy breaks. Deploy the decision with the module, or scope the
module by tenant, which rewrites nothing.

#### Two writers on one workflow aggregate

A BPMN process which holds more than one token has two branches which write the same aggregate. One
writes in the transaction VanillaBP owns for its task. The other writes in the transaction your
application opens around its API call. If a persistence layer writes the whole record, it loses
what the branch which committed first wrote. VanillaBP does not solve that, but it makes it visible.
Version 1 did not.

Your application sees two messages and gets no new property. The first is a warning per BPMN
process whose model can produce concurrent tokens while its workflow aggregate has no version
attribute. It names the elements and the ways out. The second is an error. It names a commit of a
transaction VanillaBP owns which failed on a version conflict. The exception is passed on unchanged,
so the BPMS applies its own retry rules. There is no retry inside the framework, on purpose. It
would repeat whatever the handler did before the commit failed, for example a call to a remote API,
and it would hide that anything went wrong. The wiki page
[Workflow aggregates](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates#two-writers-on-one-aggregate)
describes the four ways an application can avoid the collision.

#### An expression in your model is named at the start

A version-1 model full of expressions deploys as before. What is new is that the start says what the
expressions cost. An expression which walks a path (`${order.shipping.express}`) or calls something
(`${count(order.items) > 3}`) leads to a warning per BPMN process. It ties the model to the shape of
your Java objects and to the expression language of that BPMS. An expression which only computes
(`${not bigItem}`) leads to a notice. An expression which is just the name of one variable is not
mentioned at all. That is what the wiki page
[Workflow aggregates](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates#decoupling-bpmn-from-the-data-model)
has recommended all along.

Nothing about the deployment changes, and no model has to be rewritten. If a process reads the way it
is meant to, one line ends the message:

```yaml
vanillabp:
  workflow-modules:
    ride-sharing:
      workflows:
        RideProcess:
          accept-expressions-in-the-model: true
```

The same line at the workflow module or at `vanillabp` covers everything below it. The most
specific level wins.

#### What the startup says about the versions your BPMS still holds

The first boot of the upgraded application reads the process versions which its BPMS still holds.
It asks whether this application serves their task definitions, and it counts the workflows which
run on them. Version 1 said nothing like this. So what an upgrade brings along is named for the
first time. A task of an older version which no `@WorkflowTask` method serves is a warning. If
workflows still run on that version, it is an error.

`vanillabp.adapters.<id>.outfaded-versions` names the versions you decided to stop serving, in the
grammar of the `version` attribute. `outfaded-versions-in-use` says what a workflow which still runs
on one of them is worth: a log line, or the end of the boot. Both can be set per workflow module and
workflow.

A workflow module may rename a BPMN process and keep serving the workflows which still run under the
old name. Version 1 could do this as well, by declaring the old id as a secondary process. The check
now covers that case. So the first boot after the upgrade says more about a renamed process than any
boot before. Mind the numbering while you fade those versions out. The BPMS counts the versions of
every BPMN process id from one. So version 2 of the old id and version 2 of the new id are different
models, and `outfaded-versions` for the old ones belongs at the workflow level under the old id. The
wiki page
[Renaming a BPMN process](https://github.com/vanillabp/adapter-platform-integration/wiki/Renaming-a-BPMN-process)
walks through both ways of doing it.

#### What your running workflows bring with them

Does a feature of version 2 reach a workflow which was already running? That depends on where the
BPMS keeps what the feature depends on. Camunda 7 attaches everything while its engine parses a
process definition. So every version the engine holds is reached, including the ones version 1
deployed. Camunda 8 writes its listeners into the model it deploys, and a running instance stays on
the version it was started on. So a workflow which was already running never gets them. Whenever a
feature rests on something written into the model, this decides whether it holds or not.

The stores start empty, and none of them needs to take over old data. Outbox entries are written per
operation, so a workflow started under version 1 needs none. The election cache is empty after any
restart, and its entries are only hints. So the first operation on each workflow pays for one round
of asking the adapters. The check for a renamed adapter id stays quiet, and rightly so. Version 1
stored no adapter id, because it had one adapter and its id was its type.

An application which upgrades should look at two things in its own code, both on Camunda 8. Version 1
handed the whole workflow aggregate to every command. So instances started back then carry a variable
for everything its JSON serialization could read. A `@JsonProperty` which renames the aggregate's id
attribute breaks every probe for such an instance. Version 1 wrote the JSON name, but version 2 reads
the name which the persistence layer reports. And an attribute which version 1 serialized as a field
keeps its last version-1 value for good, because version 2 reads getters only. Give such an
attribute a getter and share it.

### What the upgrade costs under load

An application on version 1 wrote no outbox entry and kept no record of a task delivery. The word
outbox appears nowhere in the sources of the last version 1 releases: `camunda7-spring-boot-adapter`
1.6.0 and `camunda8-spring-boot-adapter` 1.10.0 on `io.vanillabp:spring-boot-support` 1.4.0. So
everything below is work which version 2 does around code you do not touch.

It is counted in rows, in transactions and in threads. There are no milliseconds here, on purpose. A
time measured on a machine of today is read as a promise half a year later.

A workflow start costs one outbox entry, written in your transaction. Your transaction still commits
once. The call to the BPMS then runs after that commit, in a transaction of its own and on a thread
which is not yours.

On Camunda 8 that transaction is new. Version 1 sent the start from the thread which had just
committed yours, and it retried in memory. So nothing was written down, and a process which died in
between lost the start. On Camunda 7 the count is the same as before. Version 1 also wrote a row in
your transaction, a job of its own engine, and the job executor carried it out afterwards. What
changed there is the table the row goes into, and the thread which picks it up.

An application on Camunda 7 pays when it answers a message, and also when it completes or cancels a
task. Version 1 called the embedded engine while your transaction was open, so your commit covered
that work. Version 2 writes an entry for it. That is one row and one transaction more than before.
On Camunda 8, version 1 sent those operations from the committing thread. Now they are entries, like
the start.

Every task delivery costs one record. It is written in the transaction of your handler, so the
delivery needs no transaction of its own. A repeated delivery is answered from that record, and your
`@WorkflowTask` method does not run again.

What your handler does is not in these numbers. On version 1 it ran inside the engine's job
transaction on Camunda 7, and inside the transaction of your own `@Transactional` on Camunda 8. Now
it runs inside a transaction which VanillaBP opens around it. How long that transaction stays open
is still up to your handler.

The threads are the number to size. On Camunda 7, version 1 ran its own engine calls on the engine's
job executor. The Spring Boot starter starts it with three threads and grows it to ten. On Camunda 8,
version 1 used the thread which committed, so it had as many threads as the application has request
threads. Version 2 dispatches on four threads, `vanillabp.outbox.dispatch-threads`. On Camunda 7
this is a second pool, not a replacement. The engine's job executor is still there and still
delivers your tasks. The dispatch carries the calls which VanillaBP makes into the engine. If you
had raised the job executor because it was the bottleneck, you now have two numbers to raise. Count
both pools into the connections of the database your workflow aggregates live in.

`WhatAnUpgradeFromVersionOneCostsTest` in
`spring-boot-integration/integration-tests/upgrade-cost-integration-test` counts the version 2 side
of all this. It pins the rows, the commits and the four dispatch threads. The version 1 side is read
from the released artifacts named above. How long the two take on one machine is a separate
measurement, and this file does not state it.

### What operations has to watch

The outbox is the one new thing in the picture. Entries which cannot be dispatched are retried, with
a growing pause in between. The first retry comes after `vanillabp.outbox.attempt-frequency`, and
the pause doubles up to `vanillabp.outbox.max-attempt-frequency`. An entry is blocked after
`vanillabp.outbox.block-after-attempts` tries. Sometimes the BPMS does not report the workflow of an
entry yet, for example a Camunda 8 cluster whose exporter is behind. Such an entry uses up no tries
while it waits. It is blocked once `vanillabp.outbox.wait-for-visibility-at-most` has passed since
it was written, about four hours by default. A blocked entry waits for somebody, so operations has
to watch for blocked entries. Dispatched entries are marked as done and cleaned up after
`vanillabp.outbox.retention`, seven days by default.

The entries are dispatched on a few threads of their own, `vanillabp.outbox.dispatch-threads`, four
by default. The workflow aggregate decides which thread takes an entry. So what belongs to one
workflow keeps its order, while different workflows move at the same time. Version 1 moved a
workflow forward in the thread which was already there: a job executor thread on Camunda 7, or the
committing thread on Camunda 8. So this is a new place which uses your database connections. Count
the threads into the pool of the database your workflow aggregates live in.

The outbox removes what it no longer needs in a window at night. If nothing else is configured, that
is from four to five in the morning (`vanillabp.outbox.housekeeping.start` and `.end`). Inside the
window it removes the dispatched entries whose retention has passed, and the payloads which no entry
names any more, as much as fits. Outside the window neither costs anything. Three meters say whether
the window is wide enough (`vanillabp.outbox.housekeeping.remaining`, `.removed` and `.window.used`).
If the window was used up and something is still left over, widen it. Only one node of a cluster
cleans up a store per night. A node which is down for the whole window does not clean up that night.

The window is read in a time zone. A container runs its JVM on UTC unless somebody sets its zone. If
your JVM runs on UTC, the application warns about it once while it starts, because "four in the
morning" then means four UTC. It starts either way. To use another zone, set `TZ` on the container,
or the environment variable `VANILLABP_OUTBOX_HOUSEKEEPING_ZONE`. If UTC is what you mean, write
`UTC` there, and the line goes away.

The records of processed task deliveries have a retention of their own,
`vanillabp.delivery.retention`. If it is not set, it follows the outbox retention. The two
retentions decide different things. On the outbox side, the retention only decides how long a
dispatched entry stays readable for support. On the delivery side, it decides whether a redelivery
which arrives later runs your `@WorkflowTask` method a second time. Some installations lowered the
outbox retention to keep the table small. In doing so, they also shortened the window which
keeps a task from running twice. Now they can keep the small table and still give the records the
period they need.

An application on several nodes should provide one bean which implements
`io.vanillabp.integration.spi.WorkflowAdapterCache`. Then the nodes share which BPMS holds which
workflow. The built-in cache lives in memory, and `vanillabp.workflow-adapter-cache.max-entries` and
`.time-to-live` limit it. Entries are only hints. So losing one costs an extra round of asking the
adapters, and never correctness. VanillaBP ships no distributed implementation, on purpose.

If Micrometer is on the classpath, VanillaBP publishes what it does with your work under
`vanillabp.*`. If your platform has a health endpoint, the adapters add what they know about their
BPMS under the name `vanillabp`. Every task delivery and every dispatch runs with a set of MDC keys.
They name the adapter, the workflow module, the BPMN process, the workflow aggregate, the task and
the delivery. The wiki page
[Observability](https://github.com/vanillabp/adapter-platform-integration/wiki/Observability) names
every meter and the question it answers. It also has a log pattern you can copy.
