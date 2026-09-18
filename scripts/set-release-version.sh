#!/usr/bin/env bash
set -euo pipefail

version="${1:?Usage: bash scripts/set-release-version.sh <version>}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
  echo "Invalid release version: $version" >&2
  exit 1
fi

# Run from the repository root. The optional aggregator includes the core,
# generator and consumer, so their coordinates must change together.
mvn -B -ntp -f codegen/pom.xml \
  org.codehaus.mojo:versions-maven-plugin:2.22.0:set \
  -DprocessAllModules=true -DnewVersion="$version" -DgenerateBackupPoms=false
