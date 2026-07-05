![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus support

This module extends the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java)
for a great developer experience.

## Features

1. A couple of dependencies to build pure workflow modules, not directly depending on Quarkus
   or any VanillaBP Quarkus extension. Besides the VanillaBP SPI this includes
   `io.vanillabp:vanillabp-integration-spi` containing interfaces business code may
   implement — most notably `io.vanillabp.integration.spi.AggregatePersistenceAware`
   for custom aggregate persistence. This interface is platform-independent: business
   code implements the very same interface regardless of running on Spring Boot or
   Quarkus. Adapter authors, in contrast, implement the interfaces of the adapter SPI
   (`io.vanillabp.adapter:migration-adapter-spi`) which is intentionally not exposed
   to business code.
2. A producer for `ProcessService<A>` beans to avoid
   IDE warning `Unsatisfied dependency: no bean matches the injection point`.
   It is part of the JAR but declared as an unselected CDI `@Alternative`, so it is
   ignored for bean resolution at runtime while still being recognized by the IDE's
   code analyzers. At runtime, `ProcessService<A>` beans generated at build time by
   the VanillaBP Quarkus extension are used instead.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
