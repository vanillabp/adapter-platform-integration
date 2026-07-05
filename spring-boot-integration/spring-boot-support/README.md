![Header](../../readme/vanillabp-headline.png)

# VanillaBP Spring Boot support

This module extends the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java)
for a great developer experience.

## Features

1. A couple of dependencies to build pure workflow modules, not directly depending on
   a specific VanillaBP adapter. Besides the VanillaBP SPI this includes
   `io.vanillabp:vanillabp-integration-spi` containing interfaces business code may
   implement — most notably `io.vanillabp.integration.spi.AggregatePersistenceAware`
   for custom aggregate persistence. This interface is platform-independent: business
   code implements the very same interface regardless of running on Spring Boot or
   Quarkus. Adapter authors, in contrast, implement the interfaces of the adapter SPI
   (`io.vanillabp.adapter:migration-adapter-spi`) which is intentionally not exposed
   to business code.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
