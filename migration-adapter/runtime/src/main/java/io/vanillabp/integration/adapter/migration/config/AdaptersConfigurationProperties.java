package io.vanillabp.integration.adapter.migration.config;

import java.util.List;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * The one key every level of the configuration carries: which adapters serve, and in which
 * order. The global section extends this class, and so do the section of a workflow module
 * ({@link WorkflowModuleAdapterProperties}) and the section of a workflow
 * ({@link WorkflowAdapterProperties}).
 */
@Getter
@Setter
@SuperBuilder
public class AdaptersConfigurationProperties {

  /**
   * Which adapters serve the workflows of this level, and in which order they are asked
   * where several of them could serve - the first one which says it holds the workflow
   * runs the {@link io.vanillabp.spi.process.ProcessService} method, and the first one of
   * the list starts a new workflow.
   * <p>
   * The key is <code>vanillabp.prioritized-adapters</code>,
   * <code>vanillabp.workflow-modules.&lt;module&gt;.prioritized-adapters</code> or
   * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.prioritized-adapters</code>,
   * and the most specific level which names one adapter or more wins as a whole - see
   * {@link MigrationAdapterProperties#getPrioritizedAdaptersFor(String, String)}.
   * <p>
   * Empty by default, which means "this level says nothing". An application whose
   * classpath holds exactly one adapter has the list derived for it and configures
   * nothing (decision 8 in the repository's DECISIONS.md); where no level names an
   * adapter for a workflow, the startup ends with a message naming the three keys.
   */
  @Builder.Default
  private List<String> prioritizedAdapters = List.of();

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: Lombok moves the
   * initializer of a field with a default into the builder, so a constructor which sets
   * nothing itself would leave such a field <code>null</code>.
   */
  public AdaptersConfigurationProperties() {

    this(builder());

  }

}
