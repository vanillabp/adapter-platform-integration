# Load measurement

Once a week, VanillaBP runs the same fixed work against the current commit and against a baseline,
on Spring Boot and on Quarkus. The graphs below show the ratio between the two: the median of the
current commit's rounds divided by the median of the baseline's rounds. A line at 1.0 means the
current commit costs what the baseline cost, on the same machine in the same hour. The band shows
the fastest and the slowest round of the current commit. The dashed line is the threshold: a time
which stays above it in two weeks in a row opens an issue. A changed count of database statements
opens one at once.

The graphs show factors and nothing else. They do not say how fast your application will be. The
handlers of the run do nothing, and its BPMS answers at once.

## Spring Boot

![Spring Boot](load/spring-boot.svg)

## Quarkus

![Quarkus](load/quarkus.svg)

## The numbers

One file per platform holds every week, one row each: [Spring Boot](load/spring-boot.csv) and
[Quarkus](load/quarkus.csv). A row names the commit, the baseline, the runner and its cores, and for
every number the median of both sides, the ratio, and the smallest and largest round of the current
commit. The weekly run writes these files. Do not edit a row by hand. A new row is compared with the
row before it.

[`load-test/README.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/load-test/README.md)
says what each number means, how to run the same work on your own machine, and what to look at when
the run turns red.
