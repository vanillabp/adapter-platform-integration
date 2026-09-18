package io.vanillabp.integration.test.utils;

/**
 * The container images the tests pin, in one place.
 * <p>
 * A test which starts a container names the version it runs, so a run says what it ran
 * against and a new release of the image cannot change a result overnight. The version is
 * read from a system property with the pinned one as its default, so a machine which needs
 * another version passes it on the command line instead of editing test sources. The
 * blueprints repository reads its images the same way, which is what lets the two say the
 * same thing to a reader.
 */
public final class ContainerImages {

  /**
   * The MongoDB the tests run against, overridable with {@code -Dmongodb.image=mongo:7.0}.
   * <p>
   * It is 8.2 and not 8.0 because MongoDB 8.0 refuses to start on a Linux kernel 6.19 or
   * newer, see <a href="https://jira.mongodb.org/browse/SERVER-121912">SERVER-121912</a>. The
   * container ends right after it started and the failing test says nothing about the reason.
   */
  public static final String MONGODB = System.getProperty("mongodb.image", "mongo:8.2");

  private ContainerImages() {
  }

}
