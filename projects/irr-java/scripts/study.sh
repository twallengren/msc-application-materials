#!/bin/sh
# Single entry point for every retained measurement, in three layers:
#   kernel   one NPV evaluation, no solving
#   prep     preparation only
#   solve    complete solves at every ladder stage, per input class
# Each group waits for an idle machine and records the load average. Named outputs are replaced.
set -eu
cd "$(dirname "$0")/.."
mode=${1:-full}
mkdir -p results
# Run from a private copy so a rebuild cannot change the code under a running study.
jar=$(mktemp -d)/benchmarks.jar
cp benchmarks/target/benchmarks.jar "$jar"
wait_idle() {
 python3 - <<'PY'
import os,sys,time
deadline=time.time()+900
while os.getloadavg()[0]>=3:
    if time.time()>deadline: sys.exit('Machine did not become idle (1-minute load average >= 3)')
    time.sleep(15)
PY
}
run() {
 name=$1; shift
 [ "$mode" = smoke ] || wait_idle
 printf '%s\n' "Running $name"
 python3 scripts/metadata.py "$name" "$@" -prof gc
 java -jar "$jar" "$@" -prof gc -rf json -rff "results/$name.json" > "results/$name.log" 2>&1
 if grep -q '<failure>' "results/$name.log"; then cat "results/$name.log"; exit 1; fi
 python3 - "$name" <<'PY'
import json,sys
from pathlib import Path
assert json.loads(Path(f'results/{sys.argv[1]}.json').read_text()),sys.argv[1]
PY
}
base='-p configuration=n256 -p frequency=DAILY -p seed=42'
ends='-p stage=HISTORICAL,OPTIMIZED'
case "$mode" in
 smoke)
  quick='-p configuration=n16 -p frequency=DAILY -p seed=42 -f 1 -wi 1 -i 1 -w 100ms -r 100ms'
  run smoke-kernel '.*KernelBench.evaluate' $quick
  run smoke-prep '.*PrepBench.prepare' $quick
  run smoke-ladder '.*LadderBench.*' $quick
  java -Xmx512m -cp "$jar" org.example.irr.bench.LadderDiagnostics > results/diagnostics.csv
  ;;
 full)
  # Layer 1: kernel by size (daily) and by schedule (256 flows).
  run kernel-size '.*KernelBench.evaluate' -p configuration=n100,n365,n1000,n3650,n10000 -p frequency=DAILY -p seed=42 -p call=OLD_POW_VALUE,OLD_AUTODIFF,PHI_DIRECT_VALUE,PHI_INCREMENTAL_VALUE,PHI_INCREMENTAL_BOTH
  run kernel-schedule '.*KernelBench.evaluate' -p configuration=n256 -p seed=42
  # Layer 2: preparation.
  run prep '.*PrepBench.prepare' -p configuration=n256 -p seed=42
  # Layer 3: every stage on every class; then schedules where integer powers matter.
  run ladder '.*LadderBench.prepared' $base
  run ladder-schedule '.*LadderBench.prepared' -p configuration=n256 -p frequency=WEEKLY,MONTHLY,IRREGULAR -p seed=42 -p family=standard
  # Historical versus optimized on dated input, further seeds and sizes.
  run overall-dated '.*LadderBench.dated' $base $ends
  run overall-seeds '.*LadderBench.prepared' -p configuration=n256 -p frequency=DAILY -p seed=20261122,8675309 -p family=standard $ends
  run overall-size '.*LadderBench.prepared' -p configuration=n16,n4096 -p frequency=DAILY -p seed=42 -p family=standard $ends
  # Untimed outcomes last, so they never compete with a timed run.
  java -Xmx512m -cp "$jar" org.example.irr.bench.LadderDiagnostics > results/diagnostics.csv
  ;;
 *) printf '%s\n' 'Usage: study.sh smoke|full' >&2; exit 2;;
esac
