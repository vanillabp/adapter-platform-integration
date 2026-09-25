package io.vanillabp.integration.it.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The BPMN and DMN files of a Quarkus application are indexed while it is built,
 * because a fast-jar cannot be searched for <code>**&#47;*.bpmn</code> once it runs.
 * A developer who adds a process to a workflow module therefore adds it to a list which
 * was written before, and this test says what happens then: the file is read and
 * deployed like the ones which were there from the start.
 * <p>
 * Dev mode is where a developer meets that case first, so it is the one pinned here.
 * The file arrives while the application runs, the next request restarts it, and the
 * restarted application reports the new file among the ones it read. The second test
 * keeps the other half: a BPMN file which changes still restarts the application, which
 * is what carries the changed model to the BPMS.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmnFileAddedInDevModeTest {

  private static final String BPMN = "not parsed by the dummy adapter";

  private static final String FIRST_BPMN = "test-module/processes/dummy/first.bpmn";

  private static final String SECOND_BPMN = "test-module/processes/dummy/second.bpmn";

  // Dev mode reads 'quarkus.http.port', not the 'quarkus.http.test-port' which Surefire
  // sets to zero, so this application needs a free port of its own. Without one it takes
  // the default 8080 and a second build on the machine answers the requests below.
  private static final int PORT = OneFreePortPerJvm.getPort();

  @RegisterExtension
  static final QuarkusDevModeTest test = new QuarkusDevModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .add(new StringAsset("quarkus.http.port="
              + PORT
              + "\n"), "application.properties")
          .addAsResource("META-INF/workflow-module")
          .add(new StringAsset(BPMN), FIRST_BPMN)
          .addClass(BpmnFileAddedInDevModeSupportingResource.class));

  private static String get(
      final String path) {

    return RestAssured
        .given()
        .port(PORT)
        .when()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .asString();

  }

  @Test
  @DisplayName("A BPMN file added after the first build is deployed")
  public void aBpmnFileAddedAfterTheFirstBuildIsDeployed() {

    assertEquals("first.bpmn", get("/deployed-bpmn"));

    test.addResourceFile(SECOND_BPMN, BPMN);

    assertEquals(
        "first.bpmn,second.bpmn",
        get("/deployed-bpmn"),
        "the BPMN file added while the application ran was not deployed");

  }

  @Test
  @DisplayName("A changed BPMN file restarts the application")
  public void aChangedBpmnFileRestartsTheApplication() {

    final var bootIdBefore = get("/boot-id");

    test.modifyResourceFile(FIRST_BPMN, content -> content
        + " (changed)");

    assertNotEquals(
        bootIdBefore,
        get("/boot-id"),
        "changing a BPMN file did not restart the application");

  }

}
