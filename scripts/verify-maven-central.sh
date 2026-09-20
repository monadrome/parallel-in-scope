#!/usr/bin/env bash
set -euo pipefail

version="${1:-0.3.0-SNAPSHOT}"
# The local repository is configurable, so ask Maven for it: a hardcoded ~/.m2 path makes the
# removal below a silent no-op and lets the build pass against a locally installed copy.
local_repo="$(mvn -q -DforceStdout help:evaluate -Dexpression=settings.localRepository)"
artifact_dir="$local_repo/io/github/monadrome/parallel-in-scope/$version"

# Remove the local copy so this check proves Maven Central can serve the artifact.
rm -rf "$artifact_dir"

for attempt in $(seq 1 12); do
  if mvn -B -ntp -U \
      -f verification/maven-central-consumer/pom.xml \
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
