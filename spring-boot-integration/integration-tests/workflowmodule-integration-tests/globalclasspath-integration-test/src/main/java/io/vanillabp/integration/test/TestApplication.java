package io.vanillabp.integration.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.vanillabp.integration.test.sample.NoPersistenceForTheSampleAggregate;

@SpringBootApplication
// the sample aggregate is a plain class nothing here persists, and VanillaBP asks an
// application to say who owns it
@org.springframework.context.annotation.Import(NoPersistenceForTheSampleAggregate.class)
public class TestApplication {

  public static void main(
      String[] args) {
    SpringApplication.run(TestApplication.class, args);
  }

}
