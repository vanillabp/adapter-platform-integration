/**
 * Drives a fixed amount of work through VanillaBP and the BPMS double, and measures what it
 * costs. The platform runs it once a week against the current commit and a baseline, and an
 * adapter repository can run the same work against its own BPMS. {@code load-test/README.md}
 * of the platform repository says how to run it and how to read the numbers.
 */
package io.vanillabp.loadharness;
