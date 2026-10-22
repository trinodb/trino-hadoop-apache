# Updating Trino's Hadoop client library

## Purpose and design

This project repackages Hadoop for Trino's HDFS client and tests. It provides a
controlled Hadoop distribution that can coexist with Trino's other libraries.

Hadoop has historically depended on old library versions that conflict with
those used by Trino. Shading relocates a library's packages into a private
namespace so Hadoop can use its own copy without interfering with Trino.
However, those private copies cannot be upgraded through Trino's dependency
management, and they complicate vulnerability updates and security scanning.
The goal is to keep the isolation Hadoop needs while allowing compatible
libraries to be ordinary dependencies managed by Trino.

## Build and test

Use JDK 21 or newer and run:

```sh
mvn -B clean install
mvn -B dependency:tree
```

Inspect the dependency-reduced `target/pom.xml` as well as the input POM. Removed
libraries must not be inadvertently promoted into published dependencies.

Test changes with a Trino PR that checks out the Hadoop PR head and installs its
snapshot before Trino builds and tests. Every Maven repository used by the CI
jobs, including separate repositories used for Maven Daemon tests, needs that
snapshot. Confirm that the snapshot is rebuilt rather than taken from a cache.

## Required before/after review

Build both the PR version and an identified baseline (the previous release or
current upstream master) in separate directories. Record the baseline commit
and preserve any existing local changes. A temporary `git archive` checkout is
sufficient; do not reset a user's checkout to build the baseline.

For each build, save the full listings and dependency tree:

```sh
jar tf target/hadoop-apache-VERSION.jar | LC_ALL=C sort > jar-entries.txt
jar tf target/hadoop-apache-VERSION-sources.jar | LC_ALL=C sort > source-jar-entries.txt
mvn -B dependency:tree -DoutputFile=dependency-tree.txt
```

Create and inspect all three unified diffs:

```sh
diff -u baseline/jar-entries.txt current/jar-entries.txt > jar-entries.diff
diff -u baseline/source-jar-entries.txt current/source-jar-entries.txt > source-jar-entries.diff
diff -u baseline/dependency-tree.txt current/dependency-tree.txt > dependency-tree.diff
```

Review the full listings and all three diffs to catch unexpected dependency
or packaging changes. Confirm that removed entries belong to the intended
libraries, required Hadoop classes and native libraries remain, and service
registrations do not reference removed implementations. Check both the binary
and source JARs, and provide the listings and diffs for review. Keep generated
comparison artifacts outside the committed sources.

Verify that every class in the binary JAR has a matching source file in the
source JAR. Use the class's package and `SourceFile` metadata to locate its
source; nested and generated classes can share a source file. Compare any
missing sources with the baseline to distinguish existing gaps from regressions.

## Shading filter changes

A filter is unused only when removing it leaves the output JAR byte-for-byte
identical. Do not infer this merely from whether its pattern appears to match.

First build the same sources twice with a fixed archive timestamp, for example
`-Dproject.build.outputTimestamp=2026-01-01T00:00:00Z`, and confirm that the binary
JARs are byte-for-byte identical. Then remove each candidate filter in an
isolated build and compare its output with the baseline using `cmp` or a
cryptographic hash. Check individual exclusion patterns as well as whole
filters, and also check whether source JAR entries or contents change.

Finally build with all proposed filter removals together and confirm that its
binary JAR is still byte-for-byte identical to the original baseline. Filters
can overlap, so independent comparisons alone are insufficient. Preserve the
comparison results for review, then run the normal build and relevant tests.
