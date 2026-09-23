package io.vanillabp.integration.runtime.workflowmodule;

import java.net.URI;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * Meta-data of a workflow module.
 */
@Builder
@Getter
@Setter(AccessLevel.PACKAGE) // needed for object-serialization
@AllArgsConstructor
public class WorkflowModule {

  /**
   * The empty instance the bytecode of the boot starts from. A workflow module is found while
   * the application is built and reaches the runtime as a recorded object, which is rebuilt
   * with this constructor and the package-visible setters. The {@link URI} of such an object
   * travels as a {@link io.vanillabp.integration.runtime.util.UriSubstitute}.
   */
  WorkflowModule() {
  }

  /**
   * The location of workflow module descriptor files.
   */
  public static final String METAINF_WORKFLOWMODULE = "META-INF/workflow-module";

  /**
   * The ID of the workflow module
   */
  private String id;

  /**
   * The URI of the workflow module descriptor file.
   */
  private URI sourceUri;

  /**
   * Whether it is a global workflow module. Global means one workflow module for the entire application.
   * Non-global means one Maven/Gradle module for each workflow module.
   */
  private boolean global;

  /**
   * Equal check, based on the workflow module's ID.
   *
   * @param o The other object
   * @return True if equal
   */
  @Override
  public boolean equals(
      final Object o) {

    if (o == null || getClass() != o.getClass()) return false;
    WorkflowModule that = (WorkflowModule) o;
    return Objects.equals(id, that.id);

  }

  /**
   * @return The hash code based on the workflow module's ID
   */
  @Override
  public int hashCode() {

    return Objects.hashCode(id);

  }

}
