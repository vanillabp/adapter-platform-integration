package io.vanillabp.migration.test;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A tool which translates our source stays out of the runtime classpath of the
 * applications using our artifacts.
 * <p>
 * Lombok, MapStruct's processor and the two platform processors do their work while
 * javac runs and have nothing left to do once the class file exists. An application asked
 * for a workflow engine, not for them, and every jar it did not ask for is one more thing
 * to scan, to ship and to answer a CVE report about. The scope which says that is
 * {@code provided}: it puts the jar on our own compile path and hands it to nobody.
 * <p>
 * What the check knows sits in {@link PublishedPoms} of the module 'test-utils', because
 * every repository of VanillaBP can make this mistake and they all make it in the same
 * way. This test is the caller which names the file this repository publishes and the
 * tools this build uses.
 * <p>
 * This repository writes no flattened POM, so an application reads the same
 * {@code pom.xml} a reviewer reads, and the check reads it too.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools which translate the source. Each one is read by javac and by nothing which
   * runs afterwards. {@code org.mapstruct:mapstruct} is not among them, because
   * {@code Mappers.getMapper(...)} runs with the application.
   */
  private static final Set<String> TOOLS_OF_THIS_BUILD = Set
      .of(
          "org.projectlombok:lombok",
          "org.mapstruct:mapstruct-processor",
          "org.springframework.boot:spring-boot-autoconfigure-processor",
          "io.quarkus:quarkus-extension-processor",
          "io.vanillabp:vanillabp-mapstruct-fluent-accessors");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoToolOfTheBuild(TOOLS_OF_THIS_BUILD);

  }

}
