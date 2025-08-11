![Draft](../../readme/vanillabp-headline.png)

# VanillaBP Quarkus support

This module extends the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java)
for a great developer experience.

## Features

1. A couple of dependencies to build pur workflow modules, not directly depending on Quarkus
   or any VanillaBP Quarkus extension.
2. A producer for `ProcessService<A>` beans to avoid
   IDE warning `Unsatisfied dependency: no bean matches the injection point`.
   It is only in use during development and excluded in production.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
