# Java IRR case study

Two period-IRR solvers in Java 21, the eight cumulative changes that connect them, three further changes prompted by the first measurements, JMH benchmarks in three layers plus thread scaling, and a [technical report](report/report.pdf) ([LaTeX source](report/report.tex)). The [specification](approved-plan.md) fixes both designs and the measurement rules.

## Build and run

Install JDK 21. The Maven wrapper downloads Maven on first use; dependencies and plugins are pinned.

```sh
./mvnw -B clean verify
java -cp benchmarks/target/benchmarks.jar org.example.irr.Demo
```

```java
import java.time.LocalDate;
import java.util.List;
import org.example.irr.*;

var flows = List.of(
    new Cashflow(LocalDate.of(2024, 1, 1), -1_000.0),
    new Cashflow(LocalDate.of(2024, 7, 1),    200.0),
    new Cashflow(LocalDate.of(2025, 1, 1),    900.0)
);
IrrResult result = Irr.solve(flows, SolverStrategy.EXTENDED);

// Reuse a prepared input, or run any stage of the ladder:
var prepared = Irr.prepare(flows, Irr.Stage.LOG_NEWTON);
IrrResult again = Irr.solve(prepared);
```

Flows must be in non-decreasing date order. Zero amounts are skipped. Rates cover the period from the first to the last non-zero flow in actual calendar days. **They are not annualized.** Four cases are decided before any search: no non-zero flows (rate 0), no contributions (+infinity), no distributions (−100%), all flows on one day (simple return).

## The two solvers

**Historical** (`org.example.irr.historical`). Three passes over `LocalDate` flows build a boxed list of `(amount, time)` pairs. NPV is `sum(C * Math.pow(1 + r, -t))`. The cascade is Commons Math Brent (tolerance `1e-6`, 200 evaluations, Dietz guess, result rounded down to six decimals) on `[0, 1000]` or `[-0.999, 0]`; then Commons Newton from 10% with automatic differentiation (200 evaluations); then a −100% fallback; then Brent in `x = 1/(1+r)`.

**Optimized** (`org.example.irr`). One pass builds primitive arrays on integer days. NPV is evaluated in `phi = log(1 + r)` by a multiplicative recurrence over day gaps (at most two `exp` calls per evaluation, FMA accumulation, written-out integer powers up to 7, a reverse walk for negative phi). An existence analysis derives analytic bounds and decides between zero, no solution, or a search direction. With a guaranteed root, Newton runs from a Padé-first guess waterfall for at most 30 iterations, then Brent on the analytic bracket; otherwise Newton runs alone for at most 200.

**Extended** (stages L9 to L11, not part of the original design). A per-evaluation power table replaces the branching integer powers; the Padé guess and Newton's root are used whatever the search direction; and a Newton step that lands beyond the analytic bounds is replaced by Newton steps on `log(P/N)`, where `P` and `N` are the discounted sums of the positive and negative amounts.

## The ladder

`Irr.solve(flows, Irr.Stage.X)` runs any of twelve cumulative stages. `SolverStrategy.HISTORICAL`, `REVISED` and `EXTENDED` select L0, L8 and L11.

| Stage | Change from the previous stage |
|---|---|
| L0 `HISTORICAL` | the historical solver |
| L1 `PRIMITIVE_ARRAYS` | same cascade on the single-pass array digest |
| L2 `ANALYTIC_DERIVATIVE` | Newton uses the analytic derivative, not automatic differentiation |
| L3 `PHI_DIRECT` | search in phi, one `exp` per flow |
| L4 `INCREMENTAL` | gap recurrence and reverse path, plain square-and-multiply powers |
| L5 `SMALL_POWERS` | written-out powers 0 to 7 |
| L6 `ANALYTIC_BOUNDS` | existence analysis, Brent on the analytic bracket |
| L7 `NEWTON_FIRST` | Newton first (30), Brent as fallback |
| L8 `OPTIMIZED` | Padé(1,1) guess |
| L9 `POWER_TABLE` | integer powers from a table built by squaring (skipped on daily schedules) |
| L10 `TRUSTED_GUESS` | Padé guess and Newton root used whatever the direction |
| L11 `LOG_NEWTON` | a Newton step beyond the analytic bounds switches to steps on `log(P/N)` |

Side effects bundled into L3, L4, L6 and L7 are listed in the specification and the report.

## Results in brief

Measured on one machine (Intel Xeon W-2140B, 8 cores, JDK 21.0.9), 256 daily flows unless stated. Full tables, confidence intervals and limitations are in the report.

| Layer | Historical | Optimized (L8) | Ratio |
|---|---|---|---|
| One NPV value, 365 daily flows | 6.46 µs | 0.379 µs | 17.1× |
| One NPV value and derivative, 365 daily flows | 55.7 µs (automatic differentiation) | 0.461 µs | 121× |
| Preparation | 9.97 µs | 3.71 µs | 2.7× |
| Solve, prepared, `standard` | 26.4 µs | 1.12 µs | 23.5× |
| Solve, prepared, `wide` | 70.2 µs | 1.45 µs | 48.3× |
| Solve, prepared, `signflips` | 49.1 µs | 1.42 µs | 34.7× |
| Solve, dated (preparation included), `standard` | 34.4 µs | 5.04 µs | 6.8× |

Where outcomes change, no ratio is quoted:

- `extreme-positive` (root above the historical bracket): historical returns 48 of 64 roots at 2.5 ms each; optimized returns 64 at 3.2 µs.
- `extreme-negative` (root below the bracket): historical returns its −100% fallback for all 64 after 2.6 ms; optimized returns 64 roots at 16.0 µs.
- `dominance` (no root): historical fails after 2.7 ms; optimized proves there is no solution in 17 ns.

Two weaknesses of the optimized design were measured, explained and then addressed by the extensions:

| | Optimized (L8) | Extended (L11) |
|---|---|---|
| Solve, `standard`, monthly gaps | 2.50 µs (10.0×) | 1.18 µs (21.3×) |
| Solve, `standard`, irregular gaps | 9.86 µs (2.7×) | 1.23 µs (21.2×) |
| Solve, `extreme-negative` | 16.0 µs, 43 evaluations | 2.69 µs, 5 evaluations |
| `signflips` roots agreeing with historical | 63 of 64 | 64 of 64 |

- **Irregular gaps.** The same gaps cost 1.4 µs per evaluation in ascending order and 4.3 µs in random order, so about two thirds of the cost is unpredictable branching in the integer power. The power table takes 0.31 µs in either order. Hardware counters were not read.
- **Far-negative roots.** Newton's first step overshoots by orders of magnitude and uses all 30 iterations before Brent runs. The log-form step detects the overshoot from the analytic bound and converges in about five evaluations.
- **Threads.** Independent solves on 16 hardware threads reach 11.7 million per second for the extended solver (13.1× one thread) and 0.36 million for the historical one (8.8×).

## Input classes

Generator v3, `Synthetic.corpusCase`; schedules are daily, weekly, calendar-monthly and irregular (gaps of 1–40 days), plus a fixed 37-day gap and the irregular gaps sorted ascending for the gap-order experiment.

| Class | Construction |
|---|---|
| `standard` | one contribution then distributions, phi in [-0.3, 0.5) |
| `wide` | as standard, phi in [-2, 2) |
| `jcurve` | contributions over the first eighth of the flows, then distributions |
| `signflips` | later amounts flip sign with probability 25–50%; uniqueness not assumed |
| `extreme-positive` | phi in [7.5, 30): rate above 1000 |
| `extreme-negative` | phi in [-30, -7.5): rate below −0.999 |
| `dominance`, `excluded` | no root; `excluded` is a terminal case and is not timed |

## Tests

158 tests from 81 test methods (one disabled, recording a known limitation): the historical cascade step by step, every stage on every class, schedule and size (`StressTest`), old-versus-new parity (`ParityTest`), and an independent 60-digit residual check of returned roots. An early stage failing on a hard class is a recorded outcome.

## Reproduce the study

```sh
./mvnw -B clean verify
scripts/study.sh smoke    # every benchmark, stage and class; no speed claims
scripts/study.sh full     # about two hours; run with nothing else using the CPU
```

The full study has 262 configurations: `KernelBench` (one NPV evaluation, including the gap-order experiment), `PrepBench` (preparation), `LadderBench` (twelve stages on seven classes, prepared; L0, L8 and L11 dated; schedules, seeds and sizes) and `ScalingBench` (throughput on 1 to 16 threads). Settings: 3 forks, 5 × 1 s warmup, 5 × 1 s measurement, `-prof gc`, 64 inputs per invocation. The script waits for a load average below 3 before each group and runs from a private copy of the jar. `LadderDiagnostics` then writes untimed per-input outcomes to `results/diagnostics.csv`.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements.txt
.venv/bin/python scripts/verify_evidence.py
.venv/bin/python scripts/analyze.py
cd report && latexmk -pdf -interaction=nonstopmode -halt-on-error report.tex
```

`verify_evidence.py` checks run settings, record counts, load averages and every diagnostic outcome. `analyze.py` regenerates all tables, macros and figures in `report/generated/` from the raw JSON. See [results/README.md](results/README.md) for the evidence map and [report/BUILD.md](report/BUILD.md) for the report build.

Java formatting is checked during Maven validation; `./mvnw spotless:apply` repairs it.
