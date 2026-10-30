![Header](../readme/vanillabp-headline.png)

# The BPMS double

An adapter which pretends to be a business process management system (BPMS). It implements the
whole adapter SPI (service provider interface, the interfaces every adapter implements). It logs
what it was asked, and it gives whatever answer the test told it to give. So in a test, an
application starts and runs a workflow without any engine.

The platform integration tests itself with this same code. Nothing here is a second implementation
written for outside users. An outside user gets exactly what the platform's own test suites run
against on every build. That is the reason why it is published at all (see decision 33 in the
repository's DECISIONS.md).

- [What it is for](#what-it-is-for)
- [Adding it to a Spring Boot application](#adding-it-to-a-spring-boot-application)
- [Adding it to a Quarkus application](#adding-it-to-a-quarkus-application)
- [Configuring it](#configuring-it)
- [Steering it: the hooks](#steering-it-the-hooks)
- [Playing the BPMS: calling in](#playing-the-bpms-calling-in)
- [What this artifact promises](#what-this-artifact-promises)
- [What it does not promise](#what-it-does-not-promise)

## What it is for

Say you write something which lives next to VanillaBP, not inside it. For example an election
cache, a persistence store, an extension of the deployment pipeline, or a library which your
application registers. Your test needs an application which starts with VanillaBP set up. Without
this artifact, the cheapest way to get one is to depend on a real BPMS adapter and hope that its
engine starts without trouble.

The double gives you the same application without the engine. It deploys every BPMN file it finds,
and it accepts every workflow operation. It gives your test access to the traffic in both
directions. The test can call in as if a task had been delivered. And it can watch what VanillaBP
sent out.

It is not a BPMN engine. It runs no model, and no token moves. If the thing you test needs a process
which really runs, use a real adapter instead.

## Adding it to a Spring Boot application

One dependency, in test scope:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-spring-boot</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

The auto-configuration registers itself. The application needs nothing else to start.

## Adding it to a Quarkus application

Two dependencies, in test scope. They belong in the module which RUNS the tests. For an extension
of your own, that is the deployment module, because a Quarkus extension is tested from there:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-quarkus</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>bpms-double-quarkus-deployment</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

You have to name the deployment artifact. If an extension is on the application's own classpath,
Quarkus finds the deployment half of the extension through the descriptor in the runtime jar. An
extension in test scope is not on that classpath, so you have to tell the build.

`io.vanillabp:vanillabp-bom` has the versions of both artifacts and of the core they share. So an
application which imports that BOM writes no version here.

## Configuring it

The double is an adapter of the type `dummy`. You configure it in the same place as every other
VanillaBP adapter:

```yaml
vanillabp:
  adapters:
    test:
      type: dummy
```

Any number of ids may have that type. With two of them, a test plays a migration from one BPMS to
another. Each id gets its own pair of beans.

Two properties switch on behaviour which a double that only logs would not have:

|                  Property                   |                                                                                                                                What it does                                                                                                                                |
|---------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `dummy-adapter.at-least-once-delivery`      | The double reports the delivery behaviour of a BPMS which repeats a task whose outcome it never learned. Turn it on to exercise the delivery record and an outbox.                                                                                                         |
| `dummy-adapter.read-aggregate-in-phase-two` | The double loads the workflow aggregate while phase two runs, the way an adapter of a remote BPMS does because it builds the variables it sends out of the aggregate. Off by default, because most test doubles of `AggregatePersistenceAware` implement nothing but save. |

## Steering it: the hooks

The double has no state of its own. Every answer comes from beans which your test declares. Each
of them is an interface. It has `default` methods for everything except its one main question. So a
later VanillaBP release can add a question without breaking what you wrote.

|              Hook               |                                                                              What it answers                                                                              |
|---------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DummyTaskWiringSource`         | Which BPMN tasks a process has, and which processes a file holds. The double parses nothing, so this is the model.                                                        |
| `DummyBpmsInitiatedStartSource` | Which start events the BPMS fires on its own and which messages start a process, so `@WorkflowStartedByBpms` methods and starts by message are checked against something. |
| `DummyProcessVersionSource`     | Which versions of a process the BPMS has deployed, so a version range or a version tag resolves.                                                                          |
| `DummyHealthSource`             | What the adapter contributes to the health endpoint, including an exception.                                                                                              |
| `DummyTaskAwarenessSource`      | Whether this BPMS knows a task, a user task or a workflow. This is what drives the election across several adapters, and the visibility delay after a start.              |
| `DummyViewerSource`             | The process definitions, the BPMN XML and the workflow history the viewer API asks for.                                                                                   |
| `DummyPhaseTwoListener`         | Watches every phase-two operation, and fails one by throwing.                                                                                                             |
| `DummyDeploymentListener`       | Watches every call of the deployment pipeline, with the adapter id, the method, the workflow module and a detail.                                                         |

To declare a hook, you declare a bean. On Spring Boot, that is a `@Bean` or a `@Component` in the
test's own context. On Quarkus, it is a CDI bean. You may have several beans of one hook type. The
double asks them in the order in which the platform resolves them.

Sometimes a hook has to change its answer between two test methods. Then the test puts that state
into the hook bean and resets it there. The double itself has no reset, because it has nothing to
reset.

## Playing the BPMS: calling in

Through the deployment service, your test acts as the BPMS. It offers three methods which no adapter
SPI declares. A test calls them to make something arrive from the outside:

- `invokeTask(workflowModuleId, bpmnProcessId, context)` runs a `@WorkflowTask` method through the
  core and returns the outcome. A real adapter does the same when its BPMS delivers a task.
- `startWorkflowByBpms(workflowModuleId, bpmnProcessId, context)` reports a workflow which the BPMS
  started on its own. It returns the aggregate id and the variables to write back.
- `notifyWorkflowEnded(workflowModuleId, bpmnProcessId, context)` reports that a workflow ended.

On Spring Boot, the instance is a bean named `DummyAdapter_DeploymentService_<adapterId>`:

```java
final var bpms = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
```

On Quarkus, the deployment services come as one CDI bean of type
`List<AdapterDeploymentService<Object, Object>>`. So a test searches that list for a
`DummyDeploymentService` with the adapter id it wants.

The process service is registered in the same way, as `DummyAdapter_ProcessService_<adapterId>` on
Spring Boot. But a test rarely needs it. You watch what it does through `DummyPhaseTwoListener`.

## What this artifact promises

Because the artifact is published, three things which a test can see become a contract. They are
listed here. So a test which relies on one of them relies on something written down, not on
something somebody happened to notice.

### The log lines

Every line which the double writes starts with `Dummy-Adapter[`, then the adapter id, then `]: `.
The level is INFO. For these lines, the wording after that prefix is promised:

```
Dummy-Adapter[test]: Reading BPMN 'Sample.bpmn' for my-module
Dummy-Adapter[test]: Reading DMN 'Decision.dmn' for my-module
Dummy-Adapter[test]: Preparing BPMN 'Sample.bpmn' for my-module
Dummy-Adapter[test]: Wiring BPMN process 'Sample' for my-module
Dummy-Adapter[test]: Deploying resources for my-module
Dummy-Adapter[test]: Starting workflow processing for my-module
Dummy-Adapter[test]: Stopping workflow processing for my-module
Dummy-Adapter[test]: Invoking task 'the-task' of my-module
```

The process service logs one line for each operation and phase. For example
`Dummy-Adapter[test]: Starting workflow (phase one) of BPMN process 'Sample' of workflow module 'my-module'`
and the same line for phase two. `Completing task`, `Canceling task`, `Completing user task`,
`Canceling user task`, `Broadcasting signal`, `Pushing the changed aggregate`, `Correlating message`
and `Starting workflow by message` follow the same form. The awareness checks log
`Checking awareness of task` and `Checking awareness of workflow`.

### The bean names

`DummyAdapter_DeploymentService_<adapterId>` and `DummyAdapter_ProcessService_<adapterId>` on
Spring Boot, with the three call-in methods described above.

### What the pipeline does with a file

The double reads a BPMN file as one process. The id of the process is the path of the file below the
BPMN location of the workflow module, without `.bpmn`. So `sub/Second.bpmn` becomes the process
`sub/Second`, and two files with the same name in different directories stay apart. A
`DummyTaskWiringSource` which names processes for that file overrides this. That is how you model a
file which holds a second executable process.

The model object and the processing context are plain `Object` instances which hold nothing.
`readDmn` passes the context on. `prepareBpmn` passes an existing one on, and creates one if there is
none.

## What it does not promise

The double is written for tests, but nothing stops it from running in production. A library which
refuses to run outside a test would have to know that it is in one, and there is no honest way to
ask. It is meant for test scope, and every example here says so. An application which ships it does
so by its own decision.

Some things are not promised: the number of log lines, their order compared with lines of other
components, and the wording of anything not listed above. The class layout is not promised either.
Which class a hook lives in, which constructor the double has and how the platform modules build it
may all change.

A future release adds a fluent API. With it, a test controls the double from one place instead of
one bean for each hook. The hooks keep working underneath it.
