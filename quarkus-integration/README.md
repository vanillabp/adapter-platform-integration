![Draft](../readme/vanillabp-headline.png)

# Quarkus integration

In Quarkus one has to use Quarkus extensions to bring new functionality into a
Quarkus project (see https://quarkus.io/extensions/). As this is the best developer
experience also Quarkus platform integration of VanillaBP is done leveraging
Quarkus' extensions mechanism.

There is one main extension `vanillabp` (implemented
by this module) which is primarily responsible for two things:

1. Bringing the [VanillaBP SPI](https://github.com/vanillabp/spi-for-java) in Quarkus to life.
2. Managing of VanillaBP adapters at build and runtime connecting to BPMSs.

Additionally, there are VanillaBP adapter extensions which need be added next to the
`vanillabp` extension to provide runtime connectivity to supported BPMSs. There is one
[dummy adapter](./dummy-adapter) as a template for new adapters also providing the
documentation what needs to be considered on providing a new VanillaBP
adapter extension. For ready-to-use adapter extensions checkout
[https://www.vanillabp.io](https://www.vanillabp.io).

## Modules

To understand subsequent documentation read the Quarkus guide
"[Writing your own extension](https://quarkus.io/guides/writing-extensions)"
to learn about concepts of Quarkus extensions.

1. **[deployment](./deployment):**<br>
   The deployment module of the extension. It is responsible for code analysis,
   loading configuration and preparing runtime CDI beans.
2. **[runtime](./runtime):**<br>
   The runtime module of the extension. It is responsible for bridging to
   [VanillaBP migration adapter](../migration-adapter) at runtime.
3. **[dummy-adapter](./dummy-adapter):**<br>
   This adapter is a template for new adapters and is used as a adapter
   by the integration tests.
4. **[quarkus-support](./quarkus-support):**<br>
   A tiny collection of useful things in the context of Quarkus to be used as
   a dependency instead of `io.vanillabp:spi-for-java`.
5. **[integration-tests](./integration-tests):**<br>
   Modules which ensure the VanillaBP Quarkus extension works as documented.

## Hints

### Logging during tests

To minimize build output three actions were taken:

1. Logs are redirected to file using `maven-surefire-plugin` configuration
   `redirectTestOutputToFile`.
2. Logs of Quarkus builds are set to log-level `ERROR` using
   `systemPropertyVariables` of `maven-surefire-plugin` with
   `<quarkus.log.level>ERROR</quarkus.log.level>`.
3. In tests the logging is captured and printed only in case of failures
   by adding `@ExtendWith(SuppressOutputExtension.class)`.

In case of errors one might disable one or all of them for finding
the root cause of the problem.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](../readme/phactum.png)

## License

Copyright 2025 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
