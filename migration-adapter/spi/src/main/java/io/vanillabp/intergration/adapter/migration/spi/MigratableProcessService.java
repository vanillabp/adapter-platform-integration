package io.vanillabp.intergration.adapter.migration.spi;


import io.vanillabp.spi.process.ProcessService;

public interface MigratableProcessService<A> extends ProcessService<A> {

  /**
   * Determine whether the given task is active in the target BPMS.
   *
   * @param taskId The task's ID
   * @return true = active, false = inactive, null = unknown to BPMS
   */
  Boolean isTaskActive(
      String taskId);

}
