# Retained evidence

Measured on 23 November 2026 with `scripts/study.sh full`. Each `*.metadata.json` records the environment, load average, seeds, configuration, command, working-tree state, and source and jar digests. The project was uncommitted when measured, so the source digests, not the commit field, identify the implementation. The jar digest is that of the jar that was timed; a later rebuild from the same sources has a different digest.

Every full configuration uses three forks, five one-second warmups, five one-second measurements and `-prof gc`. Each invocation processes 64 inputs; times are per input. Unless stated: 256 flows, daily schedule, seed 42.

| File | Layer | Records | Content |
|---|---|---|---|
| `kernel-size.json` | kernel | 25 | One NPV evaluation, five calls × daily n = 100, 365, 1,000, 3,650, 10,000. |
| `kernel-schedule.json` | kernel | 48 | Twelve calls × daily, weekly, monthly, irregular. |
| `kernel-gaps.json` | kernel | 8 | Gap-order experiment: four value calls × fixed 37-day gap and irregular gaps sorted ascending. |
| `prep.json` | preparation | 8 | Three-pass boxed preparation and single-pass digest × four schedules. |
| `ladder.json` | solve | 84 | Twelve stages × seven input classes, prepared input. |
| `ladder-schedule.json` | solve | 36 | Twelve stages, `standard`, weekly / monthly / irregular. |
| `overall-dated.json` | solve | 21 | Historical, optimized (L8) and extended (L11) on dated input (preparation timed), seven classes. |
| `overall-seeds.json` | solve | 6 | The same three stages, `standard`, two further seeds. |
| `overall-size.json` | solve | 6 | The same three stages, `standard`, n = 16 and 4,096. |
| `scaling-t1.json` … `scaling-t16.json` | throughput | 4 each | Historical and extended, `standard` and `wide`, on 1, 2, 4, 8 and 16 threads; solves per second. |
| `library-daily.json`, `library-irregular.json` | solve | 24, 12 | L0, L11 (dated; prepared on daily) against `org.decampo:xirr` and Apache POI `Irr`, `standard` and `wide`, normalized and raw amounts. Measured in a separate run (`scripts/study.sh library`) with its own jar; see its metadata. |
| `library-diagnostics.csv` | solve | 2,304 rows | Untimed outcomes of the library group: status, phi, residual, distance from the L11 root, XIRR iteration count. |
| `diagnostics.csv` | all | 10,752 rows | Untimed per-input outcomes for every timed solver configuration: status, path, evaluation counts, phi, rate, independent residual. |
| `smoke-kernel.json`, `smoke-prep.json`, `smoke-ladder.json`, `smoke-scaling.json`, `smoke-library-*.json` | – | – | Shortened executability checks; no timing claims. |
| `summary.json`, `evidence-check.txt` | – | – | Derived headline numbers and the output of `verify_evidence.py`. |
| `build.log`, `demo.txt` | – | – | Repository-root Maven build with all 168 tests, and demonstration output. |
| `tex-environment.txt`, `report-build.log` | – | – | TeX toolchain and the PDF build log. |

Each `.json` has a `.log` (JMH console output) and a `.metadata.json` beside it. In the retained files the home directory is written as `~` and the repository root as `<repo>`; nothing else in the raw output was edited.

## Reading the results

- A ratio between two solvers or stages is a speedup only when both returned a root for all 64 inputs. Otherwise the tables state the change of outcome.
- On `extreme-positive` the historical solver returns 48 of 64 roots; on `extreme-negative` it returns its −100% fallback for all 64; on `dominance` it fails and only stages from `ANALYTIC_BOUNDS` on report that no solution exists.
- On `signflips` roots are not unique: one of 64 inputs gets different roots from the historical and optimized (L8) solvers; from L10 on all 64 agree.
- The scaling files are in throughput mode; every other file reports average time.
- `verify_evidence.py` asserts run settings, record counts, a recorded load average below 3 for every group, finite roots with small independent residuals, target roots on unique-root classes, zero-evaluation no-solution outcomes, and agreement between `HISTORICAL` and `PRIMITIVE_ARRAYS`.
- `analyze.py` regenerates every table, macro and figure in `report/generated/`.

Benchmark commands overwrite their files; keep a copy before measuring on another machine.
