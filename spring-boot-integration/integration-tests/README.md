![Header](../../readme/vanillabp-headline.png)

# VanillaBP Spring Boot support - integration tests.

A collection of integration tests to guarantee desired functionality.

## Modules

1. **[dummy-extension](./dummy-extension):**<br>
   This adapter is a template for new extensions and is used as an extension
   by the integration tests. For ready-to-use adapter extensions checkout
   [https://www.vanillabp.io](https://www.vanillabp.io).
2. **[sample-extension](./sample-extension):**<br>
   An extension built like the VanillaBP Business Cockpit, in miniature: its own
   annotation, its own per-aggregate service, the election and its own section of the
   configuration. It depends on the two SPI artifacts and the platform-neutral core and
   on no platform integration - which is what it exists to prove.
3. **[extension-integration-test](./extension-integration-test):**<br>
   What an extension gets from VanillaBP, exercised against the sample extension.
4. **[main-integration-test](./main-integration-test):**<br>
   This module contains the main integration test.
5. **[test-applications](./test-applications):**<br>
   This module contains the test applications used by integration tests.
6. **[workflowmodule-integration-tests](./workflowmodule-integration-tests):**<br>
   This module contains integration tests, testing workflow module functionality.

The BPMS these tests run against is the published [BPMS double](../../bpms-double), which used to
live here as a sibling module. For ready-to-use adapter modules checkout
[https://www.vanillabp.io](https://www.vanillabp.io).

## Why these applications allow the full sync

Their workflow aggregates carry no `@NoSyncWithBPMS`, so every attribute of them travels to the
BPMS, and VanillaBP does not start such a workflow without a word (see decision 66 in the
repository's DECISIONS.md). These are test aggregates holding test data, and the tests are about
other things, so the configurations say
`vanillabp.workflow-modules.<module>.workflows.<process>.allow-full-sync-with-bpms: true` rather
than pretending to protect something. Where one aggregate serves many scenario configurations,
the aggregate keeps back the attribute no model ever reads instead, which is the same answer an
application would give.

The comment belongs here because the YAML formatter drops comments inside a mapping.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
