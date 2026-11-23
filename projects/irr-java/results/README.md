# Retained evidence

Measured on 22–23 November 2026 with `scripts/study.sh full`. Each `*.metadata.json` records the environment, load average, seeds, configuration, command, working-tree state, and source and jar digests. The project was uncommitted when measured, so the source digests, not the commit field, identify the implementation. The jar digest is that of the jar that was timed; a later rebuild from the same sources has a different digest.

Every full configuration uses three forks, five one-second warmups, five one-second measurements and `-prof gc`. Each invocation processes 64 inputs; times are per input. Unless stated: 256 flows, daily schedule, seed 42.

| File | Layer | Records | Content |
|---|---|---|---|
| `kernel-size.json` | kernel | 25 | One NPV evaluation, five calls × daily n = 100, 365, 1,000, 3,650, 10,000. |
| `kernel-schedule.json` | kernel | 36 | Nine calls × daily, weekly, monthly, irregular. |
| `prep.json` | preparation | 8 | Three-pass boxed preparation and single-pass digest × four schedules. |
| `ladder.json` | solve | 63 | Nine stages × seven input classes, prepared input. |
| `ladder-schedule.json` | solve | 27 | Nine stages, `standard`, weekly / monthly / irregular. |
| `overall-dated.json` | solve | 14 | Historical and optimized on dated input (preparation timed), seven classes. |
| `overall-seeds.json` | solve | 4 | Historical and optimized, `standard`, two further seeds. |
| `overall-size.json` | solve | 4 | Historical and optimized, `standard`, n = 16 and 4,096. |
| `diagnostics.csv` | all | 8,064 rows | Untimed per-input outcomes for every timed solver configuration: status, path, evaluation counts, phi, rate, independent residual. |
| `smoke-kernel.json`, `smoke-prep.json`, `smoke-ladder.json` | – | – | Shortened executability checks; no timing claims. |
| `summary.json`, `evidence-check.txt` | – | – | Derived headline numbers and the output of `verify_evidence.py`. |
| `build.log`, `demo.txt` | – | – | Repository-root Maven build with all 126 tests, and demonstration output. |
| `tex-environment.txt`, `report-build.log` | – | – | TeX toolchain and the PDF build log. |

Each `.json` has a `.log` (JMH console output) and a `.metadata.json` beside it.

## Reading the results

- A ratio between two solvers or stages is a speedup only when both returned a root for all 64 inputs. Otherwise the tables state the change of outcome.
- On `extreme-positive` the historical solver returns 48 of 64 roots; on `extreme-negative` it returns its −100% fallback for all 64; on `dominance` it fails and only stages from `ANALYTIC_BOUNDS` on report that no solution exists.
- On `signflips` roots are not unique: one of 64 inputs gets different roots from the two solvers.
- `verify_evidence.py` asserts run settings, record counts, a recorded load average below 3 for every group, finite roots with small independent residuals, target roots on unique-root classes, zero-evaluation no-solution outcomes, and agreement between `HISTORICAL` and `PRIMITIVE_ARRAYS`.
- `analyze.py` regenerates every table, macro and figure in `report/generated/`.

Benchmark commands overwrite their files; keep a copy before measuring on another machine.
