package io.vanillabp.migration.test;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the POMs of this repository hand an application: no tool which only translates our
 * source, and no property where a version belongs.
 * <p>
 * Lombok and the two platform processors do their work while javac runs and have nothing
 * left to do once the class file exists. An application asked
 * for a workflow engine, not for them, and every jar it did not ask for is one more thing
 * to scan, to ship and to answer a CVE report about. The scope which says that is
 * {@code provided}: it puts the jar on our own compile path and hands it to nobody.
 * <p>
 * The second assertion is about the versions in those POMs. A version written as
 * {@code ${some.version}} resolves in our own build, where the POM holding the value is
 * in the reactor, and it stops resolving for a consumer the day that POM stops defining
 * the property. On 2026-09-27 that turned every Quarkus blueprint red while this
 * repository stayed green.
 * <p>
 * What the checks know sits in {@link PublishedPoms} of the module 'test-utils', because
 * every repository of VanillaBP can make these mistakes and they all make them in the
 * same way. This test is the caller which names the file this repository publishes and
 * the tools this build uses.
 * <p>
 * This repository writes no flattened POM, so an application reads the same
 * {@code pom.xml} a reviewer reads, and the check reads it too.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools which translate the source. Each one is read by javac and by nothing which
   * runs afterwards.
   */
  private static final Set<String> TOOLS_OF_THIS_BUILD = Set
      .of(
          "org.projectlombok:lombok",
          "org.springframework.boot:spring-boot-autoconfigure-processor",
          "io.quarkus:quarkus-extension-processor");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoToolOfTheBuild(TOOLS_OF_THIS_BUILD);

  }

  @Test
  @DisplayName("no POM of this repository hands an application a property instead of a value")
  public void noPomHandsAnApplicationAPropertyInsteadOfAValue() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoPropertyInsteadOfAValue();

  }

}
