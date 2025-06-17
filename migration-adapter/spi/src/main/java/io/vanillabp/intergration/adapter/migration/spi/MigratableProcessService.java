package io.vanillabp.intergration.adapter.migration.spi;


public interface MigratableProcessService<A> {

  /**
   * Determine whether the given task is active in the target BPMS.
   *
   * @param taskId The task's ID
   * @return true = active, false = inactive, null = unknown to BPMS
   */
  Boolean isTaskActive(
      String taskId);

}
