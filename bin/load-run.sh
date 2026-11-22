#!/usr/bin/env bash
#
# Runs the weekly load run: builds the current commit and a baseline, runs the same fixed
# work against both on both platforms, and appends one row per platform to the record.
#
#   bin/load-run.sh --baseline <git ref> --record-dir <dir> [options]
#
# Options:
#   --baseline <ref>      the commit or tag to compare with (required)
#   --record-dir <dir>    where <platform>.csv lives, usually a clone of the wiki (required)
#   --work-dir <dir>      where builds, Maven repositories and rounds go (default: target/load-run)
#   --rounds <n>          rounds per side and platform, at least five (default: 5)
#   --platforms "<list>"  "spring-boot quarkus" by default
#   --workflows <n>       the first size of the live set; the timed work is ten times as much
#                         (default: 100)
#   --maven-args "<args>" passed to every Maven call, e.g. "-s .github/maven/settings.xml"
#   --seed-repository <dir>  a Maven repository both private repositories start as a copy
#                         of, so third-party artifacts are not downloaded twice. Leave the
#                         snapshots of VanillaBP out of it, or the two sides may mix
#
# Needs a JDK 21 or newer, Docker for the PostgreSQL the rounds run on, and git.
#
# The baseline is built from its own checkout into a Maven repository of its own. Then the
# applications of load-test, which come from THIS commit, are built twice: once against the
# baseline's artifacts and once against this commit's. So both sides run the identical
# harness and differ only in the platform they run on. A round is one fresh JVM. The rounds
# alternate between baseline and candidate, so a slow minute of the runner hits both.
#
# Exit codes: 0 every platform is green or has a note, 2 a platform is red (its row is
# written all the same), 1 the run broke and wrote nothing for at least one platform.
# load-test/README.md says what to look at when it turns red.

set -euo pipefail

baseline=""
record_dir=""
work_dir="target/load-run"
rounds=5
platforms="spring-boot quarkus"
workflows=100
maven_args=""
seed_repository=""

while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) baseline="$2"; shift 2 ;;
    --record-dir) record_dir="$2"; shift 2 ;;
    --work-dir) work_dir="$2"; shift 2 ;;
    --rounds) rounds="$2"; shift 2 ;;
    --platforms) platforms="$2"; shift 2 ;;
    --workflows) workflows="$2"; shift 2 ;;
    --maven-args) maven_args="$2"; shift 2 ;;
    --seed-repository) seed_repository="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done
if [ -z "$baseline" ] || [ -z "$record_dir" ]; then
  echo "usage: bin/load-run.sh --baseline <git ref> --record-dir <dir> [options]" >&2
  exit 1
fi
if [ "$rounds" -lt 5 ]; then
  echo "five rounds per side are the least the medians need, got $rounds" >&2
  exit 1
fi

root="$(git rev-parse --show-toplevel)"
cd "$root"
mkdir -p "$work_dir" "$record_dir"
work_dir="$(cd "$work_dir" && pwd)"
record_dir="$(cd "$record_dir" && pwd)"
service_tasks=3
candidate_commit="$(git rev-parse --short=12 HEAD)"
baseline_commit="$(git rev-parse --short=12 "$baseline^{commit}")"
runner="$(lscpu 2>/dev/null | sed -n 's/^Model name: *//p' | head -1)"
runner="${runner:-unknown}"
cores="$(nproc)"
date_of_run="$(date -u +%F)"

# shellcheck disable=SC2086 # the Maven arguments are meant to split
mvn_quiet() {
  ./mvnw --batch-mode --no-transfer-progress $maven_args "$@"
}

# Builds the load applications of THIS commit against the artifacts in the given Maven
# repository and copies them to the given directory.
build_apps() {
  local repository="$1" target="$2"
  mvn_quiet -Dmaven.repo.local="$repository" -Pload-test \
    -pl load-harness,load-test,load-test/spring-boot,load-test/quarkus \
    -DskipTests -DskipITs -Dmaven.javadoc.skip=true install
  rm -rf "$target"
  mkdir -p "$target"
  cp load-test/spring-boot/target/load-test-spring-boot.jar "$target/spring-boot.jar"
  cp -r load-test/quarkus/target/quarkus-app "$target/quarkus-app"
}

echo "=== candidate $candidate_commit, baseline $baseline ($baseline_commit)"

if [ -n "$seed_repository" ] && [ -d "$seed_repository" ]; then
  for side in candidate baseline; do
    if [ ! -d "$work_dir/m2-$side" ]; then
      cp -r "$seed_repository" "$work_dir/m2-$side"
    fi
  done
fi

# the candidate: this commit, built into a repository of its own
mvn_quiet -Dmaven.repo.local="$work_dir/m2-candidate" -DskipTests -DskipITs -Dmaven.javadoc.skip=true install
build_apps "$work_dir/m2-candidate" "$work_dir/apps/candidate"

# the baseline: its own checkout, its own repository. Its platform artifacts carry the same
# version as the candidate's, so the two repositories must never mix
rm -rf "$work_dir/baseline-src"
git worktree add --detach "$work_dir/baseline-src" "$baseline_commit" >/dev/null
trap 'git worktree remove --force "$work_dir/baseline-src" >/dev/null 2>&1 || true' EXIT
(cd "$work_dir/baseline-src" \
  && ./mvnw --batch-mode --no-transfer-progress $maven_args -Dmaven.repo.local="$work_dir/m2-baseline" \
    -DskipTests -DskipITs -Dmaven.javadoc.skip=true install)
build_apps "$work_dir/m2-baseline" "$work_dir/apps/baseline"

# one PostgreSQL for the whole run, a fresh database for every round. The image is the one
# ContainerImages.POSTGRESQL of test-utils pins, so tests and load run see the same server
image="${POSTGRESQL_IMAGE:-postgres:16-alpine}"
container="vanillabp-load-run-$$"
docker run -d --rm --name "$container" -e POSTGRES_PASSWORD=load -p 127.0.0.1::5432 "$image" >/dev/null
trap 'docker rm -f "$container" >/dev/null 2>&1 || true; git worktree remove --force "$work_dir/baseline-src" >/dev/null 2>&1 || true' EXIT
timeout 60 bash -c "until docker exec $container pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done"
port="$(docker port "$container" 5432/tcp | head -1 | sed 's/.*://')"

run_round() {
  local platform="$1" side="$2" round="$3"
  local database="${platform//-/_}_${side}_${round}"
  local output="$work_dir/rounds/$platform/$side/round-$round.properties"
  mkdir -p "$(dirname "$output")"
  docker exec "$container" createdb -U postgres "$database"
  local app
  if [ "$platform" = "spring-boot" ]; then
    app="$work_dir/apps/$side/spring-boot.jar"
  else
    app="$work_dir/apps/$side/quarkus-app/quarkus-run.jar"
  fi
  echo "--- $platform, $side, round $round"
  # the same heap for every round and both sides, so a difference is not the JVM's choice
  LOAD_JDBC_URL="jdbc:counting:postgresql://127.0.0.1:$port/$database" \
    LOAD_JDBC_USER=postgres LOAD_JDBC_PASSWORD=load \
    java -Xms1g -Xmx1g -jar "$app" \
    --load.output="$output" --load.workflows="$workflows" --load.service-tasks="$service_tasks" \
    > "$work_dir/rounds/$platform/$side/round-$round.log" 2>&1 \
    || { echo "round failed, see $work_dir/rounds/$platform/$side/round-$round.log" >&2; return 1; }
  docker exec "$container" dropdb -U postgres "$database"
}

rm -rf "$work_dir/rounds"
for round in $(seq 1 "$rounds"); do
  for platform in $platforms; do
    # odd rounds start with the baseline, even rounds with the candidate
    if [ $((round % 2)) -eq 1 ]; then sides="baseline candidate"; else sides="candidate baseline"; fi
    for side in $sides; do
      run_round "$platform" "$side" "$round"
    done
  done
done

harness="$(ls load-harness/target/load-harness-*.jar | grep -v -e sources -e javadoc | head -1)"
result=0
for platform in $platforms; do
  status=0
  java -cp "$harness" io.vanillabp.loadharness.record.CompareRuns \
    --record "$record_dir/$platform.csv" --platform "$platform" \
    --baseline-rounds "$work_dir/rounds/$platform/baseline" \
    --candidate-rounds "$work_dir/rounds/$platform/candidate" \
    --date "$date_of_run" --candidate-commit "$candidate_commit" --baseline-ref "$baseline" \
    --runner "$runner" --cores "$cores" --workflows "$workflows" --service-tasks "$service_tasks" \
    --verdict "$work_dir/verdict-$platform.md" || status=$?
  case "$status" in
    0) ;;
    2) [ "$result" -eq 1 ] || result=2 ;;
    *) result=1 ;;
  esac
  if [ -f "$record_dir/$platform.csv" ]; then
    bin/render-load-graph "$record_dir/$platform.csv" "$record_dir/$platform.svg" || result=1
  fi
done
exit "$result"
