# Java IRR case study: specification

This is the project description. It states the two solvers being compared, the cumulative stages between them, the input classes, and how they are measured. Names are chosen for this repository.

## 1. Deliverables

- Java 21 numerical library and standalone JMH module, built with Maven Wrapper; dependency and plugin versions pinned.
- Correctness tests, deterministic synthetic generators, a stress suite, and a runnable demonstration.
- Benchmark commands, raw measurement output, and reproducible table and plot generation.
- A 10–15-page LaTeX technical report and a concise README.

## 2. Input and conventions

```java
public record Cashflow(LocalDate date, double amount) {}
```

- Input is a `List<Cashflow>` in non-decreasing date order. Null entries or dates, nonfinite amounts and out-of-order dates are rejected. Caller data is never mutated.
- Flows with amount exactly zero are skipped. Same-day flows are kept as separate flows.
- Negative amounts are contributions; positive amounts are distributions.
- `start` and `end` are the dates of the first and last non-zero flows. The period is `D = daysBetween(start, end)` in actual calendar days. `t_i = daysBetween(start, date_i) / D`, so `t` runs from 0 to 1.
- Rates cover the measurement period `D`. They are **not annualized**.
- `totalReturn = sum(C_i)`. `firstFlow` and `lastFlow` are the amounts of the first and last non-zero flows.
- Modified Dietz weighted flow: `W = sum(C_i * (end - date_i) / D)`. Dietz guess (a rate): `-totalReturn / W`.

Terminal cases, identical for both solvers and decided before any search:

| Case | Result |
|---|---|
| no non-zero flows | rate 0 |
| no negative amounts | rate +infinity |
| no positive amounts | rate -1 (−100%) |
| `D == 0` (all non-zero flows on one day) | simple return `totalPositive/totalNegativeMagnitude - 1` |

## 3. Historical solver

Package `org.example.irr.historical`. Its data layout and arithmetic are deliberately those of the original design.

### 3.1 NPV function

`HistoricalNpv` holds a boxed list of pairs `(Double amount, Double periodFraction)` and implements Commons Math `UnivariateDifferentiableFunction`.

```java
double value(double rate) {
  double total = 0;
  for (Pair<Double, Double> cf : cashFlows)
    total += cf.left() * Math.pow(1 + rate, -cf.right());   // one Math.pow per flow
  return total;
}

DerivativeStructure value(DerivativeStructure rate) {       // automatic differentiation
  DerivativeStructure base = rate.add(1);
  DerivativeStructure total = new DerivativeStructure(rate.getFreeParameters(), rate.getOrder(), 0);
  for (Pair<Double, Double> cf : cashFlows)
    total = total.add(base.pow(-cf.right()).multiply(cf.left()));
  return total;
}
```

`asXFunction()` gives the x-space form `sum(C_i * Math.pow(x, t_i))` with `x = 1/(1+r)`, over the same boxed list.

### 3.2 Preparation: three passes

1. **Pass 1:** copy non-zero flows into a new list; find `start`, `end`, and whether negative and positive amounts exist. Return the terminal cases of section 2.
2. **Pass 2:** `totalReturn`, `firstFlow`, `lastFlow`, and the Dietz weight `W`, with `LocalDate` arithmetic (`ChronoUnit.DAYS.between`) per flow.
3. **Pass 3:** build `HistoricalNpv`: per flow, `ChronoUnit.DAYS.between(start, date) / (double) D`, boxed into a pair.

Then:

- bracket: `[0, 1000]` if `firstFlow * totalReturn < 0`, otherwise `[-0.999, 0]`;
- `guess = -totalReturn / W`;
- `negativeRootExists = lastFlow * totalReturn < 0`;
- `trySolveForX = firstFlow * totalReturn < 0`.

### 3.3 Solve cascade

Constants: tolerance `1e-6`, `200` evaluations, results rounded down to 6 decimals.

1. Commons `BrentSolver(1e-6).solve(200, npv, lo, hi, guess)`; the result is rounded **down** to 6 decimals (`Math.floor(x * 1e6) / 1e6`). Any `IllegalArgumentException` (no sign change, or a guess outside the bracket) moves to the next stage; an exhausted evaluation budget is not caught. Brent evaluates `value(double)` only: no derivatives.
2. Commons `NewtonRaphsonSolver(1e-6).solve(200, npv, 0.1)`: Newton from a fixed 10%, with value and derivative from `value(DerivativeStructure)`. `TooManyEvaluationsException` or `NoBracketingException` moves on.
3. If `negativeRootExists`, return −1 (−100%).
4. If `trySolveForX`, Commons `BrentSolver(0)` on the x-space function over `[0, 1]` with start `1/(1+guess)`, 200 evaluations; if `x > 0` return `1/x - 1`. Any Commons exception moves on.
5. Otherwise there is no result.

Both Brent and Newton stop at 200. There is no residual acceptance, no analytic bracket, no compensated summation and no early proof that a root is absent.

## 4. Optimized solver

Package `org.example.irr`.

### 4.1 Digest: one pass, primitive arrays

After locating the last non-zero flow, one forward pass on integer day numbers (`LocalDate.toEpochDay()`) produces:

- `double[] amounts`, `double[] times`, `int[] gaps` (day gap from the previous non-zero flow; `gaps[0] = 0`), `dayFraction = 1.0 / D`;
- `totalReturn`, total positive and negative magnitudes, `firstFlow`, `lastFlow`;
- the Dietz weight from the identity `W = (endDay * sum(C) - sum(day_i * C_i)) / D`, so no second pass is needed;
- the nearest opposing-sign day gap from each end, for the analytic bounds.

Moments `m_k = sum(C_i * t_i^k)`, `k = 0, 1, 2`, are computed by a solve only when its plan needs a guess, and are not cached in the digest. Amounts are never normalized.

### 4.2 Evaluation in phi

`phi = log(1 + r)`, `r = exp(phi) - 1`, using `Math.log1p` and `Math.expm1`.

```
F(phi)  =  sum(C_i * exp(-t_i * phi))
F'(phi) = -sum(C_i * t_i * exp(-t_i * phi))
```

**Direct path:** one `Math.exp` per flow. Used by the `PHI_DIRECT` stage and as the true-NPV evaluator.

**Incremental path (forward, phi >= 0):**

```java
double c1 = Math.exp(-phi * dayFraction);          // exp 1: one day's discount
double k = Math.exp(-phi * times[0]);        // exp 2: the first flow's discount
double npv = amounts[0] * k, d = amounts[0] * times[0] * k;
for (int i = 1; i < n; i++) {
  k *= intPower(c1, gaps[i]);
  npv = Math.fma(amounts[i], k, npv);
  d   = Math.fma(amounts[i] * times[i], k, d);
}
return (npv, -d);
```

**`intPower(c, g)`:** straight-line multiplications for exponents 0 to 7 (at most four multiplies), square-and-multiply above. A daily schedule has `g = 1` almost everywhere: one multiply per flow.

**Reverse path (phi < 0):** walk from the last flow backwards with `c = exp(phi * dayFraction) < 1`, evaluating

```
G(phi) = sum(C_i * exp(phi * (T - t_i))) = exp(phi * T) * F(phi),   T = t_{n-1}
```

Every term is at most `|C_i|`, so nothing overflows. `G` has the same sign and roots as `F`. The reported derivative is `G' - T*G = exp(phi*T) * F'`, so the Newton step `G / (G' - T*G)` equals `F / F'`. It is deliberately not `dG/dphi`.

Accumulation uses `Math.fma`; there is no compensated summation and no allocation inside the loop.

### 4.3 Initial guess

Taylor expansion at zero: `F(phi) ≈ m0 - m1*phi + m2*phi^2/2`.

- linear guess: `m0 / m1`;
- Padé(1,1): `m0*m1 / (m1^2 - m0*m2/2)`, accepted only when the denominator exceeds `0.05 * m1^2`.

Guess waterfall for a target direction: Padé(1,1) if its sign matches the direction or `pade * m0 > 0`; else the linear guess if finite with matching sign; else `log(1 - m0/firstFlow)` if finite with matching sign; else `log(1.1)` for a positive direction or `log(0.9)` for a negative one.

### 4.4 Existence analysis

Let `S_f` be the total magnitude of flows opposing `firstFlow`, `g_f` the nearest opposing day gap from the first flow, and `S_l`, `g_l` the same from the last flow.

```
phiUpper =  (D / g_f) * log(S_f / |firstFlow|)        (0 if S_f <= |firstFlow|)
phiLower = -(D / g_l) * log(S_l / |lastFlow|)         (0 if S_l <= |lastFlow|)
```

- A bound is **degenerate** when `|phi| <= 1e-9`: no root on that side.
- The return is **significant** when `|totalReturn| > 1e-10 * max(S+, S-)`.
- **Positive root guaranteed** when `firstFlow * totalReturn < 0`, the return is significant and `phiUpper` is not degenerate. **Negative root guaranteed** likewise with `lastFlow` and `phiLower`.

| Condition | Directive |
|---|---|
| both bounds degenerate and return not significant | ZERO: return 0 |
| both bounds degenerate otherwise | NO_SOLUTION |
| exactly one side guaranteed | that side, GUARANTEED |
| both guaranteed | sign of `totalReturn`, GUARANTEED |
| only one side non-degenerate | that side, NOT_GUARANTEED |
| otherwise | sign of `totalReturn`, NOT_GUARANTEED |

### 4.5 Plans

- **Guaranteed:** Newton from the waterfall guess, at most **30** iterations; its result is rejected if it lands on the wrong side and disagrees with the sign of `totalReturn`. Then Commons Brent (tolerance `1e-6`, 200 evaluations) on `[0, 1.001 * phiUpper]` or `[1.001 * phiLower, 0]`, started at the guess, or at the bracket midpoint when the guess is outside it. The Brent result is rounded down to 6 decimals; the Newton result is not rounded.
- **Heuristic (not guaranteed):** Newton from Padé(1,1), else the linear guess, else 0.1; at most 200 iterations; no Brent.

**Newton:** converged when `|phi_next - phi| <= 1e-6`. It fails on a zero or non-finite derivative, a non-finite iterate, or a step that vanishes in rounding (`phi_next == phi` with `F != 0`). One combined value-and-derivative evaluation per iteration.

## 5. Optimization ladder

`Irr.Stage` exposes twelve cumulative stages. Each changes one thing relative to the stage before it. L0 to L8 reconstruct the original work; `SolverStrategy.HISTORICAL` and `SolverStrategy.REVISED` are L0 and L8. L9 to L11 are extensions made in this study (section 5.1); `SolverStrategy.EXTENDED` is L11.

| Stage | Change from the previous stage |
|---|---|
| L0 `HISTORICAL` | Section 3 exactly |
| L1 `PRIMITIVE_ARRAYS` | Same cascade and constants on the single-pass digest: `double[]` storage, integer dates, no boxing |
| L2 `ANALYTIC_DERIVATIVE` | Newton stage uses the analytic rate derivative instead of automatic differentiation |
| L3 `PHI_DIRECT` | phi coordinate with one `exp` per flow; brackets, guess and Newton start mapped by `log1p`; the same Commons Brent and Newton. Bundled: the −100% and x-space stages are dropped because phi has no wall at −100%; accumulation uses FMA; Brent rounds phi rather than the rate |
| L4 `INCREMENTAL` | Incremental path and the reverse path; integer powers by plain square-and-multiply. For phi < 0 Brent now sees the scaled function, so its evaluation count can change |
| L5 `SMALL_POWERS` | `intPower` with written-out cases 0 to 7 |
| L6 `ANALYTIC_BOUNDS` | Existence analysis; Brent on the analytic bracket, started at the mapped Dietz guess or the midpoint when that is outside; ZERO and NO_SOLUTION directives. Without a guarantee Brent is skipped and Commons Newton runs alone |
| L7 `NEWTON_FIRST` | Plans of section 4.5 with the guess waterfall minus Padé. Bundled: the guarded step-converging Newton replaces Commons Newton, with the direction gate; Brent starts from the waterfall guess and an exhausted Brent budget becomes a failure; the unguaranteed plan starts from the linear guess |
| L8 `OPTIMIZED` | Padé(1,1) guess waterfall: section 4 exactly |
| L9 `POWER_TABLE` | Extension: integer powers from a table built once per evaluation |
| L10 `TRUSTED_GUESS` | Extension: the Padé guess and the Newton root are used whatever the search direction |
| L11 `LOG_NEWTON` | Extension: a Newton step that leaves the analytic bounds is replaced by steps on log(P/N) |

### 5.1 Extensions

These respond to three weaknesses the first full measurement of L0 to L8 exposed. They are not part of the original work.

**Power table (L9).** With `c = exp(-phi/D)` (or `exp(phi/D)` on the reverse path), the evaluator fills `p[g] = c^g` for `g = 0 .. limit` once per evaluation, by `p[g] = p[g/2]^2` (times `c` for odd `g`), and each flow then costs one lookup and one multiplication: `K *= p[gap]`. `limit = min(maxGap, 256, 4n)`; a larger gap falls back to `intPower`. The digest records `maxGap`. When every gap is at most one day there is nothing to tabulate and the L8 evaluator is used. The table is scratch space allocated once per solve. Reason: on irregular gaps the data-dependent branches of `intPower` made the recurrence slower than one `exp` per flow.

**Ungated guess (L10).** On the guaranteed plan the Padé guess is used whenever its denominator guard accepts it, without the direction condition, and the root Newton finds is returned without the direction gate. Reason: with several roots the gate rejected a nearby root and Brent then returned a remote one from the analytic bracket.

**Log-form step (L11).** Let `P(phi)` and `N(phi)` be the discounted sums of the positive and of the negative amounts, so `NPV = P - N`, and let `h = log(P/N)`. `h` has the roots of the NPV and is exactly linear for two flows. Every root lies inside the widened analytic bounds `[1.001 phiLower, 1.001 phiUpper]`. If a Newton step on the NPV lands beyond a non-zero bound, that step is discarded and the iteration continues from the same point with `phi <- phi - h/h'`, `h' = N'/N - P'/P`, within the same iteration cap. One loop accumulates `P`, `N` and their time-weighted sums with the amounts split without branches. Reason: far from the root the NPV is nearly exponential, so a Newton step overshoots by orders of magnitude and then returns about one unit per iteration; on far-negative roots L8 spent its 30 iterations and fell back to Brent.

## 6. Input classes

Every solver comparison is reported per class, because the optimizations act on different classes. Generator: `java.util.Random`, fixed anchor date, recorded seeds; nothing is generated inside a timed method.

| Class | Construction |
|---|---|
| `standard` | one contribution, then positive distributions, known root `phi` in [-0.3, 0.5) |
| `wide` | as standard, `phi` in [-2, 2) |
| `jcurve` | several early contributions, later distributions, one sign change, known unique root |
| `signflips` | each later amount flips sign with a per-case probability of 25–50% around a constructed root in [-1, 1); uniqueness not assumed |
| `extreme-positive` | conventional, `phi` in [7.5, 30): rate above 1000 |
| `extreme-negative` | conventional, `phi` in [-30, -7.5): rate below −0.999 |
| `dominance`, `excluded` | no root |

Schedules: daily, weekly, calendar-monthly (`plusMonths`), irregular (gaps of 1–40 days).

Tests:

- Unit tests for each stage of the historical cascade and each terminal case.
- A stress suite running every ladder stage on every class, schedule and size.
- An independent 60-digit residual check of returned roots; residuals are computed by tests and diagnostics, never inside a solve.
- Old-versus-new parity: roots agreeing within `1e-5`, with counts of cases where only one solver returns a root and of −100% fallbacks.

Failure of an early stage on a hard class is a recorded outcome, not a test failure.

## 7. Measurement

Three layers and a scaling measurement, each on identical inputs within a comparison.

1. **Kernel: one NPV evaluation.** Historical `Math.pow` value; historical automatic differentiation; phi direct; phi incremental; power table; log form; value-only and value-plus-derivative. Daily schedules with n = 100, 365, 1,000, 3,650, 10,000, plus weekly, monthly and irregular schedules. A gap-order experiment times the same calls on a fixed 37-day gap, on irregular gaps in ascending order, and on the same gaps in random order, to separate the arithmetic of an integer power from the predictability of its branches.
2. **Preparation.** Three-pass boxed preparation against the single-pass digest.
3. **Solve.** All twelve stages per input class on prepared input, with evaluation counts per solve; the historical, optimized (L8) and extended (L11) ends also on dated input (preparation included).
4. **Thread scaling.** Throughput of independent solves on 1, 2, 4, 8 and 16 threads, each thread with its own prepared inputs, for the historical and extended ends.

Accuracy tables accompany the timings: agreement between historical roots and those of L8 and of L11, and outcomes where they differ.

Rules:

- Full runs: three forks, five one-second warmups, five one-second measurements, allocation profiling. Each invocation processes 64 inputs; times are per input.
- A group starts only when the machine's load average is below 3; the load is recorded with each run.
- A ratio is a speedup only where both sides return a root for every input. Otherwise the change in outcome is reported. Where roots are not unique the two sides may return different roots; the accuracy table says how often.
- Prepared solves reuse the prepared object across invocations, so nothing a solve needs may be cached in it: moments are computed by each solve.
- Untimed diagnostics record status, path, evaluation counts and an independent residual for every input and stage.

## 8. Report

A4, 11-point `article`, 10–15 pages, with numbered equations, tables, figures and BibTeX references, built by `latexmk -pdf`. Tables and plots are generated from raw benchmark JSON by a Python script with pinned dependencies. Remembered figures from earlier informal timing are kept separate from these measurements. No minimum speedup is required; conclusions follow the evidence.
