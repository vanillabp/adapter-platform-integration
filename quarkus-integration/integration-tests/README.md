![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus extension - integration tests.

Integration tests which make sure that things work as they should.

## Modules

1. **[native-image-tests](./native-image-tests):**<br>
   An application with a relational database and no MongoDB anywhere. It has no test class.
   The test is that it builds: in JVM mode on every build, and as a native image when the
   profile `native-image` is active (`-Dnative`). Its main method starts a workflow and reads the
   aggregate back. So running the binary also checks that the application starts. See the section
   about optional extensions in the [runtime module's README](../runtime/README.md).
2. **[sample-extension](./sample-extension):**<br>
   A small extension, built like the VanillaBP Business Cockpit. It is the Quarkus half of the
   extension which the Spring Boot integration tests also build. It depends on the two SPI
   artifacts and on the core, which does not depend on a platform. It depends on no platform
   integration. Proving that this works is why it exists. `ExtensionEnablementTest` in
   [deployment-integration-tests](./deployment-integration-tests) tests it.

These tests run against the published [BPMS double](../../bpms-double), a stand-in for a real BPMS
(business process management system). It used to be a module here, next to the others.

## Why these applications allow the full sync

Their workflow aggregates have no `@NoSyncWithBPMS`. So every attribute of them goes to the BPMS,
and VanillaBP does not start such a workflow without saying something about it (see decision 66 in
the repository's DECISIONS.md). These aggregates are test aggregates with test data, and the tests
are about other things. So the configurations say
`vanillabp.workflow-modules.<module>.workflows.<process>.allow-full-sync-with-bpms: true`. They do
not pretend to protect something. Where one aggregate serves many scenario configurations, the
aggregate instead keeps back the attribute which no model ever reads. An application would give the
same answer.

This explanation is here and not in the YAML file, because the YAML formatter drops comments inside
a mapping.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
