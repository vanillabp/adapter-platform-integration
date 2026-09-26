# An adapter reports through a bean, not through the adapter SPI

The block at the end of a start belongs to the core. An adapter has to reach it, because
152 of the 260 startup messages of VanillaBP come from the four adapters, and a start which
writes a block for the platform and a line per adapter is worse than one which writes lines
for everything.

The way there is a bean. `io.vanillabp.integration.spi.startup.StartupReport` lives in the
integration SPI, `StartupFindings` implements it, and both platform integrations publish one
instance per application. An adapter asks for it the way it asks for a data source: a
constructor parameter on Spring Boot, an injection point or a producer parameter on Quarkus.

Not the adapter SPI, and not `AdapterCollaborators`. The collaborators are handed to the
deployment service and the process service of an adapter, and most of what an adapter finds
at a start is found before either of them exists: the Camunda 8 adapter alone has 75
messages and nearly all of them are about its configuration, which is read while its beans
are built. A collaborator would reach half the places which need it, and the other half
would need the bean anyway. Two ways to one object is one way too many.

The interface carries the reporting half and nothing else: `notice`, `warn`, `error`,
`refuse`. When a start is over, whether a reason not to start is collected or thrown, and
what the block looks like stay with the core, because those are answers a start needs once
and not once per adapter.

`StartupTopic` moved from the core into the integration SPI with it. An adapter names the
topic of its finding, so the list of topics is part of the contract rather than part of the
core's rendering.

## What an adapter reports

A finding, which is something the developer of the application has to change or know about.
What the adapter itself set up, how many workers it started, which BPMS it reached: those
are reports about the adapter doing its work and they stay where they are. The three framed
reports of the adapters (`Camunda8Connectors`, `Camunda8Listeners`, `Camunda7Listeners`) are
of that kind and do not move.

## Nothing breaks while the adapters follow

The platform publishes the bean and nothing asks for it yet. An adapter which does not know
about it behaves exactly as it did, and each of the four picks the bean up in its own story
once this snapshot is published. That is why the way is a bean and not a new mandatory
collaborator: a mandatory one would turn every adapter red the moment the snapshot lands,
for a feature none of them uses yet.

## Referred to by

- `migration-adapter/integration-spi/.../spi/startup/StartupReport.java`
- `migration-adapter/integration-spi/.../spi/startup/StartupTopic.java`
- `migration-adapter/runtime/.../startup/StartupFindings.java`
- `spring-boot-integration/.../SpringBootMigrationAdapterAutoConfiguration.java`
  (`vanillaBpStartupReport`)
- `quarkus-integration/.../WorkflowTaskRegistryProducer.java` (`startupReport`)
- `migration-adapter/runtime/src/test/.../startup/AnAdapterReportsIntoTheSameBoxTest.java`
- `TheStartupReportOfAnAdapterTest` of both platform integrations
- `migration-adapter/ADAPTER-AUTHORS.md`, section "Saying what a start found"

