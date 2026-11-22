package io.vanillabp.loadtest.quarkus;

import java.util.HashMap;
import java.util.Optional;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vanillabp.loadharness.LoadApplication;
import io.vanillabp.loadharness.LoadDriver;
import io.vanillabp.loadharness.RoundSettings;
import jakarta.inject.Inject;

/**
 * The Quarkus application of the load run. It runs one round when <code>load.output</code>
 * is set, writes the numbers and ends. A key may come from the configuration or from the
 * command line as <code>--load.output=...</code>.
 */
@QuarkusMain
public class LoadTestMain implements QuarkusApplication {

  @Inject
  LoadApplication application;

  @Inject
  LoadDriver driver;

  @Override
  public int run(
      final String... args) throws Exception {

    final var arguments = new HashMap<String, String>();
    for (final var argument : args) {
      if (argument.startsWith("--") && argument.contains("=")) {
        arguments.put(argument.substring(2, argument.indexOf('=')), argument.substring(argument.indexOf('=') + 1));
      }
    }
    final var config = ConfigProvider.getConfig();
    RoundSettings
        .from(key -> Optional
            .ofNullable(arguments.get(key))
            .or(() -> config.getOptionalValue(key, String.class)))
        .runAndWrite(application, driver);
    return 0;

  }

}
