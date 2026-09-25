package io.vanillabp.integration.adapter.migration.config;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The answer a setting gets which was written at a level nobody reads it at.
 * <p>
 * One class carries what an adapter may be told and every level binds that class, so a key
 * can be written at four levels whatever the code does with it. A few keys are read higher
 * up only, and until somebody says so, a line one level too deep looks like it works: the
 * application boots, and the setting does nothing. Every one of those keys therefore ends
 * the startup with the same three parts - where the setting IS read, where each written
 * line has to move to, and why the other levels cannot be read.
 * <p>
 * The keys are printed sorted, because the maps behind them come from a binder and keep no
 * order, so the same configuration would otherwise read differently from one boot to the
 * next.
 * <p>
 * Why every one of these keys is refused rather than dropped is decision 89 in the
 * repository's DECISIONS.md.
 */
final class MisplacedSettings {

  private MisplacedSettings() {

  }

  /**
   * Builds the refusal of a setting written below the level it is read at.
   *
   * @param whereTheSettingIsRead Which levels read it, as a clause the message continues
   *          with "but it is configured at" - for example "The location of an adapter's
   *          BPMN files is read at the workflow module and at the adapter"
   * @param writtenAt The full property keys the application wrote, in any order
   * @param whereEachLineBelongs What the reader moves each line to, as a noun phrase -
   *          for example "the workflow it is meant for"
   * @param keysToWriteInstead The keys which may carry the setting, spelled out so a
   *          developer can copy one
   * @param whyTheOtherLevelsCannotBeRead The sentence closing the message: what VanillaBP
   *          does which leaves the written level unread
   * @return The exception to throw
   */
  static IllegalStateException refuse(
      final String whereTheSettingIsRead,
      final List<String> writtenAt,
      final String whereEachLineBelongs,
      final List<String> keysToWriteInstead,
      final String whyTheOtherLevelsCannotBeRead) {

    return new IllegalStateException(
        """
            %s, but it is configured at:
              %s
            Move each of them to %s:
              %s
            %s"""
            .formatted(
                whereTheSettingIsRead,
                writtenAt
                    .stream()
                    .sorted()
                    .collect(Collectors.joining("\n  ")),
                whereEachLineBelongs,
                String.join("\n  ", keysToWriteInstead),
                whyTheOtherLevelsCannotBeRead));

  }

}
