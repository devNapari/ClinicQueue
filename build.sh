#!/usr/bin/env bash
# Builds ClinicQueue with the JDK only (Java 17+). No Maven or internet needed.
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out/classes out/test-classes
javac -d out/classes src/main/java/com/clinicqueue/*.java
cp -r src/main/resources/static out/classes/static

# Runnable jar: java -jar out/clinicqueue.jar
printf 'Main-Class: com.clinicqueue.Main\n' > out/manifest.txt
jar --create --file out/clinicqueue.jar --manifest out/manifest.txt -C out/classes .

echo "Built out/clinicqueue.jar"
