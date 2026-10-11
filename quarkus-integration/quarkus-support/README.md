![Header](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus support

This module adds to the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) (service
provider interface) to make the developer's work easier.

## Features

1. A few dependencies to build pure workflow modules. Such a module does not depend directly on
   Quarkus or on any VanillaBP Quarkus extension. Besides the VanillaBP SPI, this includes
   `io.vanillabp:vanillabp-integration-spi`. It has interfaces which business code may
   implement. The most important one is `io.vanillabp.integration.spi.AggregatePersistenceAware`
   for your own aggregate persistence. This interface does not depend on the platform. Business
   code implements the same interface on Spring Boot and on Quarkus. Adapter authors implement
   other interfaces, those of the adapter SPI (`io.vanillabp:vanillabp-adapter-spi`). On purpose,
   business code does not see the adapter SPI.
2. A producer for `ProcessService<A>` beans. Without it, the IDE warns
   `Unsatisfied dependency: no bean matches the injection point`.
   The producer is part of the JAR, but it is declared as a CDI `@Alternative` which is not
   selected. So at runtime, bean resolution ignores it, but the code analyzers of the IDE still
   see it. At runtime, VanillaBP uses the `ProcessService<A>` beans instead, which the VanillaBP
   Quarkus extension generates at build time.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
