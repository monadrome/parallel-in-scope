#!/usr/bin/env bash
set -euo pipefail

version="${1:-0.3.0-SNAPSHOT}"
# The version selects the artifact directory the cleanup below removes and is passed
# through to the consumer build; accept only a plain Maven version so it can never be
# a path that escapes that directory.
if [[ ! "$version" =~ ^[0-9A-Za-z]+([.-][0-9A-Za-z]+)*$ ]]; then
  echo "error: refusing version '$version': expected a plain Maven version such as 0.3.0-SNAPSHOT" >&2
  exit 2
fi

# The repository root .mvn/jvm.config carries the --add-exports/--add-opens flags Error Prone
# needs on the build JDK; a JDK 8 JVM refuses to start with them, and this script exists
# precisely to exercise the artifact on a Java 8 runtime. Point Maven's jvm.config lookup at
# the consumer project, which has no .mvn directory.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
consumer_dir="$(cd "$script_dir/../verification/maven-central-consumer" && pwd)"
export MAVEN_BASEDIR="$consumer_dir"

# Resolve into a throwaway repository instead of the developer's shared ~/.m2. A locally
# installed copy must not satisfy the dependency — this check exists to prove Maven Central
# serves the artifact — and the cleanup below must never reach outside the artifact
# directory; an empty per-run repository gives both guarantees by construction.
tmp_repo="$(mktemp -d "${TMPDIR:-/tmp}/verify-maven-central.XXXXXX")"
# Clean up on every exit path. The signal traps exit with the conventional 128+signal
# code, which runs the EXIT trap; this makes cleanup and the exit code explicit rather
# than relying on the shell running the EXIT trap by itself when it dies from a signal.
trap 'rm -rf "$tmp_repo"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
artifact_dir="$tmp_repo/io/github/monadrome/parallel-in-scope/$version"

# Remove the local copy so this check proves Maven Central can serve the artifact.
rm -rf "$artifact_dir"

for attempt in $(seq 1 12); do
  if mvn -B -ntp -U \
      -f "$consumer_dir/pom.xml" \
      -Dmaven.repo.local="$tmp_repo" \
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
