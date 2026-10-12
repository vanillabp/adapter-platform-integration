![Header](../../readme/vanillabp-headline.png)

# VanillaBP Spring Boot support - integration tests.

Integration tests which make sure that things work as they should.

## Modules

1. **[dummy-extension](./dummy-extension):**<br>
   An extension which does almost nothing. It is a template for new extensions, and the
   integration tests use it as an extension. For extensions and adapters which are ready to use,
   see [https://www.vanillabp.io](https://www.vanillabp.io).
2. **[sample-extension](./sample-extension):**<br>
   A small extension, built like the VanillaBP Business Cockpit. It has its own
   annotation, its own service for each aggregate, the election and its own section of the
   configuration. It depends on the two SPI artifacts and on the core, which does not depend on a
   platform. It depends on no platform integration. Proving that this works is why it exists.
3. **[extension-integration-test](./extension-integration-test):**<br>
   Tests what an extension gets from VanillaBP. They run against the sample extension.
4. **[main-integration-test](./main-integration-test):**<br>
   This module contains the main integration test.
5. **[test-applications](./test-applications):**<br>
   This module contains the test applications used by integration tests.
6. **[workflowmodule-integration-tests](./workflowmodule-integration-tests):**<br>
   This module contains integration tests for the features of workflow modules.

These tests run against the published [BPMS double](../../bpms-double), a stand-in for a real BPMS
(business process management system). It used to be a module here, next to the others. For adapter
modules which are ready to use, see [https://www.vanillabp.io](https://www.vanillabp.io).

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
