package io.vanillabp.integration.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.vanillabp.integration.test.sample.NoPersistenceForTheSampleAggregate;

@SpringBootApplication
// nothing here persists the sample aggregate, it is a plain class. VanillaBP asks an
// application who owns it
@org.springframework.context.annotation.Import(NoPersistenceForTheSampleAggregate.class)
public class TestApplication {

  public static void main(
      String[] args) {
    SpringApplication.run(TestApplication.class, args);
  }

}
