#!/usr/bin/env bash
# Compiles and runs the test suite (JDK only).
set -euo pipefail
cd "$(dirname "$0")"

./build.sh >/dev/null
javac -cp out/classes -d out/test-classes src/test/java/com/clinicqueue/*.java
java -cp out/classes:out/test-classes com.clinicqueue.AllTests
