![Header](../../readme/vanillabp-headline.png)

# VanillaBP Spring Boot support

This module adds to the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) (service
provider interface) to make the developer's work easier.

## Features

1. A few dependencies to build pure workflow modules. Such a module does not depend directly on
   a specific VanillaBP adapter. Besides the VanillaBP SPI, this includes
   `io.vanillabp:vanillabp-integration-spi`. It has interfaces which business code may
   implement. The most important one is `io.vanillabp.integration.spi.AggregatePersistenceAware`
   for your own aggregate persistence. This interface does not depend on the platform. Business
   code implements the same interface on Spring Boot and on Quarkus. Adapter authors implement
   other interfaces, those of the adapter SPI (`io.vanillabp:vanillabp-adapter-spi`). On purpose,
   business code does not see the adapter SPI.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
