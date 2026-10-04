#!/usr/bin/env bash
set -euo pipefail

version="${1:-0.3.0-SNAPSHOT}"
# The repository root .mvn/jvm.config carries the --add-exports/--add-opens flags Error Prone
# needs on the build JDK; a JDK 8 JVM refuses to start with them, and this script exists
# precisely to exercise the artifact on a Java 8 runtime. Point Maven's jvm.config lookup at
# the consumer project, which has no .mvn directory.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
consumer_dir="$(cd "$script_dir/../verification/maven-central-consumer" && pwd)"
export MAVEN_BASEDIR="$consumer_dir"
# The local repository is configurable, so ask Maven for it: a hardcoded ~/.m2 path makes the
# removal below a silent no-op and lets the build pass against a locally installed copy.
local_repo="$(mvn -q -DforceStdout help:evaluate -Dexpression=settings.localRepository)"
artifact_dir="$local_repo/io/github/monadrome/parallel-in-scope/$version"

# Remove the local copy so this check proves Maven Central can serve the artifact.
rm -rf "$artifact_dir"

for attempt in $(seq 1 12); do
  if mvn -B -ntp -U \
      -f "$consumer_dir/pom.xml" \
      -Dparallel-in-scope.version="$version" \
      -Dtest=MavenCentralConsumerTest#publishedArtifactCanBeResolvedAndUsed \
      clean verify; then
    exit 0
  fi

  if [ "$attempt" -lt 12 ]; then
    echo "Maven Central has not served $version yet; retrying in 10 seconds (attempt $((attempt + 1))/12)." >&2
    rm -rf "$artifact_dir"
    sleep 10
  fi
done

exit 1
