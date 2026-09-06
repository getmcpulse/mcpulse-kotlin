#!/usr/bin/env bash
# Fetches the jars the no-build-tool test path needs.
#
# `lib/` is gitignored, so a fresh clone has nothing in it. Maven or Gradle will
# resolve these on their own; this exists so the suite can also be run with
# nothing but a JDK, which is how it is run in this repo's README.
set -euo pipefail

MAVEN=https://repo1.maven.org/maven2
JACKSON=2.18.2

mkdir -p lib
for artifact in databind core annotations; do
  file="jackson-${artifact}-${JACKSON}.jar"
  [ -f "lib/$file" ] && continue
  echo "fetching $file"
  curl -sfL "${MAVEN}/com/fasterxml/jackson/core/jackson-${artifact}/${JACKSON}/${file}" -o "lib/$file"
done
