/**
 * Keeps the numbers of the load run: one file per round, and one row per week and platform
 * in a CSV file which compares the candidate with the baseline. Reading the CSV back is as
 * important as writing it, because a time turns red only when it missed the threshold in two
 * weeks in a row.
 */
package io.vanillabp.loadharness.record;
