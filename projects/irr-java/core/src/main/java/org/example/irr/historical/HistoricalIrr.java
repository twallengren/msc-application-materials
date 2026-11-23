package org.example.irr.historical;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.math3.analysis.solvers.BrentSolver;
import org.apache.commons.math3.analysis.solvers.NewtonRaphsonSolver;
import org.apache.commons.math3.exception.NoBracketingException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;
import org.example.irr.Cashflow;

/**
 * Baseline internal-rate-of-return solver: boxed pairs, three separate preparation passes, and a
 * four-stage cascade (Brent, Newton, minus one, x-space Brent).
 */
public final class HistoricalIrr {
  private static final double TOLERANCE = 1e-6;
  private static final int MAX_EVALUATIONS = 200;
  private static final double SCALE = 1e6;

  private HistoricalIrr() {}

  public enum Path {
    TERMINAL,
    BRENT,
    NEWTON,
    MINUS_ONE,
    X_BRENT,
    NONE
  }

  /** The rate is NaN when the path is NONE. */
  public record Outcome(double rate, Path path, int valueEvaluations, int derivativeEvaluations) {
    public boolean hasRate() {
      return path != Path.NONE;
    }
  }

  /** Everything the preparation produces; a non-null terminal means pass 1 decided the result. */
  public record Problem(
      Outcome terminal,
      HistoricalNpv npv,
      double lowerBound,
      double upperBound,
      double guess,
      boolean negativeRootExists,
      boolean trySolveForX) {}

  private static Outcome terminal(double rate) {
    return new Outcome(rate, Path.TERMINAL, 0, 0);
  }

  public static Problem prepare(List<Cashflow> flows) {
    if (flows == null) {
      throw new IllegalArgumentException("flows must not be null");
    }

    // Pass 1: validate, copy the non-zero flows, find start and end, note the signs present.
    List<Cashflow> nonZero = new ArrayList<>();
    LocalDate previous = null;
    boolean hasNegative = false;
    boolean hasPositive = false;
    double totalPositive = 0;
    double totalNegativeMagnitude = 0;
    for (Cashflow cf : flows) {
      if (cf == null) {
        throw new IllegalArgumentException("null cash flow");
      }
      if (cf.date() == null) {
        throw new IllegalArgumentException("null date");
      }
      if (!Double.isFinite(cf.amount())) {
        throw new IllegalArgumentException("non-finite amount");
      }
      if (previous != null && cf.date().isBefore(previous)) {
        throw new IllegalArgumentException("dates out of order");
      }
      previous = cf.date();
      if (cf.amount() == 0) {
        continue;
      }
      nonZero.add(cf);
      if (cf.amount() < 0) {
        hasNegative = true;
        totalNegativeMagnitude -= cf.amount();
      } else {
        hasPositive = true;
        totalPositive += cf.amount();
      }
    }
    if (nonZero.isEmpty()) {
      return terminalProblem(0);
    }
    LocalDate start = nonZero.get(0).date();
    LocalDate end = nonZero.get(nonZero.size() - 1).date();
    if (!hasNegative) {
      return terminalProblem(Double.POSITIVE_INFINITY);
    }
    if (!hasPositive) {
      return terminalProblem(-1);
    }
    long days = ChronoUnit.DAYS.between(start, end);
    if (days == 0) {
      return terminalProblem(totalPositive / totalNegativeMagnitude - 1);
    }

    // Pass 2: total return, first and last amounts, and the weighted flow.
    double totalReturn = 0;
    double weighted = 0;
    double firstFlow = nonZero.get(0).amount();
    double lastFlow = nonZero.get(nonZero.size() - 1).amount();
    for (Cashflow cf : nonZero) {
      totalReturn += cf.amount();
      weighted += cf.amount() * ChronoUnit.DAYS.between(cf.date(), end) / (double) days;
    }

    // Pass 3: build the NPV function from boxed (amount, period fraction) pairs.
    List<Pair<Double, Double>> pairs = new ArrayList<>();
    for (Cashflow cf : nonZero) {
      pairs.add(new Pair<>(cf.amount(), ChronoUnit.DAYS.between(start, cf.date()) / (double) days));
    }
    HistoricalNpv npv = new HistoricalNpv(pairs);

    double lower;
    double upper;
    if (firstFlow * totalReturn < 0) {
      lower = 0;
      upper = 1000;
    } else {
      lower = -0.999;
      upper = 0;
    }
    double guess = -totalReturn / weighted;
    boolean negativeRootExists = lastFlow * totalReturn < 0;
    boolean trySolveForX = firstFlow * totalReturn < 0;
    return new Problem(null, npv, lower, upper, guess, negativeRootExists, trySolveForX);
  }

  private static Problem terminalProblem(double rate) {
    return new Problem(terminal(rate), null, Double.NaN, Double.NaN, Double.NaN, false, false);
  }

  public static Outcome solve(Problem problem) {
    if (problem.terminal() != null) {
      return problem.terminal();
    }
    HistoricalNpv npv = problem.npv();
    npv.resetCounters();

    // Stage 1: Brent over the bracket, result rounded down to 6 decimals.
    try {
      double root =
          new BrentSolver(TOLERANCE)
              .solve(
                  MAX_EVALUATIONS,
                  npv,
                  problem.lowerBound(),
                  problem.upperBound(),
                  problem.guess());
      return finish(Math.floor(root * SCALE) / SCALE, Path.BRENT, npv);
    } catch (IllegalArgumentException e) {
      // fall through to the next stage
    }

    // Stage 2: Newton from a fixed 10%.
    try {
      double root = new NewtonRaphsonSolver(TOLERANCE).solve(MAX_EVALUATIONS, npv, 0.1);
      // Reconstruction default: a non-finite Newton result counts as a failure of this stage.
      if (Double.isFinite(root)) {
        return finish(root, Path.NEWTON, npv);
      }
    } catch (TooManyEvaluationsException | NoBracketingException e) {
      // fall through to the next stage
    }

    // Stage 3: a root at minus one is assumed when the last flow opposes the total.
    if (problem.negativeRootExists()) {
      return finish(-1, Path.MINUS_ONE, npv);
    }

    // Stage 4: Brent in x-space over [0, 1].
    if (problem.trySolveForX()) {
      try {
        double x =
            new BrentSolver(0)
                .solve(MAX_EVALUATIONS, npv.asXFunction(), 0, 1, 1 / (1 + problem.guess()));
        if (x > 0) {
          return finish(1 / x - 1, Path.X_BRENT, npv);
        }
      } catch (IllegalArgumentException | TooManyEvaluationsException e) {
        // fall through to no result
      }
    }

    return finish(Double.NaN, Path.NONE, npv);
  }

  private static Outcome finish(double rate, Path path, HistoricalNpv npv) {
    return new Outcome(rate, path, npv.valueCalls(), npv.derivativeCalls());
  }

  public static Outcome solve(List<Cashflow> flows) {
    return solve(prepare(flows));
  }
}
