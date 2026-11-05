#!/usr/bin/env bash
# Refreshes the GraalVM reachability metadata from the tracing agent over the tests, then drops test-only entries.
# Needs a GraalVM JDK 21 in GRAALVM21_HOME (the agent runs inside the test JVM, which is Java 21) and the native
# proof library (DIW_ZK_NATIVE=require DIW_ZK_FEATURES=tlsn) so the JNA paths are recorded too.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${GRAALVM21_HOME:?set GRAALVM21_HOME to a GraalVM JDK 21}"
export JAVA_HOME="$GRAALVM21_HOME" GRAALVM_HOME="$GRAALVM21_HOME"
export DIW_ZK_NATIVE="${DIW_ZK_NATIVE:-require}" DIW_ZK_FEATURES="${DIW_ZK_FEATURES:-tlsn}"
gradle_args=(--no-daemon -Porg.gradle.java.installations.auto-detect=false "-Porg.gradle.java.installations.paths=$GRAALVM21_HOME")
./gradlew "${gradle_args[@]}" -Pagent test --rerun
./gradlew "${gradle_args[@]}" metadataCopy
python3 tools/filter-native-metadata.py
