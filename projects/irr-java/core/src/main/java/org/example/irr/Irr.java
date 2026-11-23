package org.example.irr;

import java.util.List;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.apache.commons.math3.analysis.differentiation.UnivariateDifferentiableFunction;
import org.apache.commons.math3.analysis.solvers.BrentSolver;
import org.apache.commons.math3.analysis.solvers.NewtonRaphsonSolver;
import org.apache.commons.math3.exception.NoBracketingException;
import org.apache.commons.math3.exception.TooManyEvaluationsException;
import org.example.irr.historical.HistoricalIrr;

/** Period IRR: the historical solver, the optimized solver, and the cumulative stages between. */
public final class Irr {
  public static final double TOLERANCE = 1e-6;
  public static final int MAX_EVALUATIONS = 200;

  /** Newton cap on the guaranteed path of the optimized solver; 200 everywhere else. */
  public static final int GUARANTEED_NEWTON_ITERATIONS = 30;

  public static final double RATE_MAX = 1000, RATE_MIN = -0.999, NEWTON_START_RATE = 0.1;

  private Irr() {}

  /** Cumulative optimization ladder; each stage changes one thing relative to the one before. */
  public enum Stage {
    /** Boxed pairs, Math.pow Brent, automatic-differentiation Newton, three-pass preparation. */
    HISTORICAL,
    /** The same cascade on the single-pass primitive-array digest. */
    PRIMITIVE_ARRAYS,
    /** Newton stage uses the analytic rate derivative. */
    ANALYTIC_DERIVATIVE,
    /** phi coordinate, one exp per flow, brackets and starts mapped by log1p. */
    PHI_DIRECT,
    /** Gap recurrence with FMA; plain square-and-multiply integer powers. */
    INCREMENTAL,
    /** Written-out integer powers 0 to 7. */
    SMALL_POWERS,
    /** Existence analysis and Brent on the analytic bracket. */
    ANALYTIC_BOUNDS,
    /** Newton first, seeded by the linear guess; Brent as fallback on the guaranteed path. */
    NEWTON_FIRST,
    /** Pade(1,1) guess waterfall: the optimized solver. */
    OPTIMIZED
  }

  /** Stage-specific prepared input: the historical problem for L0, a digest otherwise. */
  public record Prepared(Stage stage, HistoricalIrr.Problem historical, Digest digest) {}

  public static Digest digest(List<Cashflow> flows) {
    return new Digest(flows);
  }

  public static Prepared prepare(List<Cashflow> flows, Stage stage) {
    if (stage == null) throw new IllegalArgumentException("Null stage");
    return stage == Stage.HISTORICAL
        ? new Prepared(stage, HistoricalIrr.prepare(flows), null)
        : new Prepared(stage, null, new Digest(flows));
  }

  public static IrrResult solve(List<Cashflow> flows, Stage stage) {
    return solve(prepare(flows, stage));
  }

  public static IrrResult solve(List<Cashflow> flows, SolverStrategy strategy) {
    if (strategy == null) throw new IllegalArgumentException("Null strategy");
    return solve(flows, strategy == SolverStrategy.HISTORICAL ? Stage.HISTORICAL : Stage.OPTIMIZED);
  }

  public static IrrResult solve(Prepared p) {
    if (p == null) throw new IllegalArgumentException("Null prepared input");
    if (p.stage() == Stage.HISTORICAL) return historical(HistoricalIrr.solve(p.historical()));
    return new Run(p.digest()).solve(p.stage());
  }

  /** Solves an existing digest at any digest-based stage. */
  public static IrrResult solve(Digest digest, Stage stage) {
    if (digest == null || stage == null || stage == Stage.HISTORICAL)
      throw new IllegalArgumentException("A digest solves stages after HISTORICAL");
    return new Run(digest).solve(stage);
  }

  private static IrrResult historical(HistoricalIrr.Outcome o) {
    IrrResult.Path path =
        switch (o.path()) {
          case TERMINAL -> IrrResult.Path.TERMINAL;
          case BRENT -> IrrResult.Path.BRENT;
          case NEWTON -> IrrResult.Path.NEWTON;
          case MINUS_ONE -> IrrResult.Path.MINUS_ONE;
          case X_BRENT -> IrrResult.Path.X_BRENT;
          case NONE -> IrrResult.Path.NONE;
        };
    IrrResult.Status status =
        switch (o.path()) {
          case TERMINAL -> IrrResult.Status.TERMINAL;
          case MINUS_ONE -> IrrResult.Status.MINUS_ONE_FALLBACK;
          case NONE -> IrrResult.Status.FAILED;
          default -> IrrResult.Status.CONVERGED;
        };
    return new IrrResult(
        o.rate(),
        Math.log1p(o.rate()),
        status,
        path,
        o.valueEvaluations(),
        o.derivativeEvaluations(),
        o.derivativeEvaluations());
  }

  static double roundDown(double x) {
    return Math.floor(x * 1e6) / 1e6;
  }

  private static final class Run {
    final Digest d;
    int values, derivatives, iterations;

    Run(Digest d) {
      this.d = d;
    }

    IrrResult solve(Stage stage) {
      if (d.terminal() != Digest.Terminal.NONE) {
        double rate = d.terminalRate();
        return result(rate, Math.log1p(rate), IrrResult.Status.TERMINAL, IrrResult.Path.TERMINAL);
      }
      return switch (stage) {
        case PRIMITIVE_ARRAYS -> rateCascade(true);
        case ANALYTIC_DERIVATIVE -> rateCascade(false);
        case PHI_DIRECT -> phiCascade(Npv.Phi.DIRECT);
        case INCREMENTAL -> phiCascade(Npv.Phi.INCREMENTAL_GENERIC);
        case SMALL_POWERS -> phiCascade(Npv.Phi.INCREMENTAL);
        case ANALYTIC_BOUNDS -> bounded();
        case NEWTON_FIRST -> planned(false);
        case OPTIMIZED -> planned(true);
        case HISTORICAL -> throw new IllegalStateException();
      };
    }

    IrrResult result(double rate, double phi, IrrResult.Status status, IrrResult.Path path) {
      return new IrrResult(rate, phi, status, path, values, derivatives, iterations);
    }

    IrrResult rateRoot(double rate, IrrResult.Path path) {
      return result(rate, Math.log1p(rate), IrrResult.Status.CONVERGED, path);
    }

    IrrResult phiRoot(double phi, IrrResult.Path path) {
      double rate = Math.expm1(phi);
      return result(
          rate,
          phi,
          Double.isFinite(rate) && rate > -1
              ? IrrResult.Status.CONVERGED
              : IrrResult.Status.RATE_UNREPRESENTABLE,
          path);
    }

    IrrResult failed() {
      return result(Double.NaN, Double.NaN, IrrResult.Status.FAILED, IrrResult.Path.NONE);
    }

    // ------------------------------------------------ L1, L2: the historical cascade on arrays

    IrrResult rateCascade(boolean autodiff) {
      boolean positiveBracket = d.firstFlow() * d.totalReturn() < 0;
      double lo = positiveBracket ? 0 : RATE_MIN, hi = positiveBracket ? RATE_MAX : 0;
      double guess = d.dietzGuess();
      try {
        double x =
            new BrentSolver(TOLERANCE)
                .solve(
                    MAX_EVALUATIONS,
                    r -> {
                      values++;
                      return Npv.rateValue(d, r);
                    },
                    lo,
                    hi,
                    guess);
        return rateRoot(roundDown(x), IrrResult.Path.BRENT);
      } catch (IllegalArgumentException e) {
        // No sign change, or a guess outside the bracket: next stage.
      }
      try {
        double x =
            new NewtonRaphsonSolver(TOLERANCE)
                .solve(MAX_EVALUATIONS, new RateFunction(autodiff), NEWTON_START_RATE);
        if (Double.isFinite(x)) return rateRoot(x, IrrResult.Path.NEWTON);
      } catch (TooManyEvaluationsException | NoBracketingException e) {
        // Next stage.
      }
      if (d.lastFlow() * d.totalReturn() < 0)
        return result(
            -1,
            Double.NEGATIVE_INFINITY,
            IrrResult.Status.MINUS_ONE_FALLBACK,
            IrrResult.Path.MINUS_ONE);
      if (positiveBracket) {
        try {
          double x =
              new BrentSolver(0)
                  .solve(
                      MAX_EVALUATIONS,
                      (UnivariateFunction)
                          v -> {
                            values++;
                            return Npv.xValue(d, v);
                          },
                      0,
                      1,
                      1 / (1 + guess));
          if (x > 0) return rateRoot(1 / x - 1, IrrResult.Path.X_BRENT);
        } catch (IllegalArgumentException | TooManyEvaluationsException e) {
          // No result.
        }
      }
      return failed();
    }

    /** Rate-space function for the Commons Newton solver: autodiff or analytic derivative. */
    final class RateFunction implements UnivariateDifferentiableFunction {
      final boolean autodiff;

      RateFunction(boolean autodiff) {
        this.autodiff = autodiff;
      }

      @Override
      public double value(double rate) {
        values++;
        return Npv.rateValue(d, rate);
      }

      @Override
      public DerivativeStructure value(DerivativeStructure rate) {
        derivatives++;
        iterations++;
        if (autodiff) return Npv.rateAutomatic(d, rate);
        Npv.Evaluation e = Npv.rateAnalytic(d, rate.getValue());
        return new DerivativeStructure(1, 1, e.value(), e.derivative());
      }
    }

    // ------------------------------------- L3 to L5: the same cascade in phi, fixed brackets

    IrrResult phiCascade(Npv.Phi evaluator) {
      boolean positiveBracket = d.firstFlow() * d.totalReturn() < 0;
      double lo = positiveBracket ? 0 : Math.log1p(RATE_MIN),
          hi = positiveBracket ? Math.log1p(RATE_MAX) : 0;
      IrrResult r = brent(evaluator, lo, hi, mappedGuess(), false);
      if (r != null) return r;
      r = commonsNewton(evaluator);
      return r != null ? r : failed();
    }

    /** The Dietz rate guess in phi. A rate at or below -100% has no image: Brent rejects it. */
    double mappedGuess() {
      double g = d.dietzGuess();
      return g <= -1 ? Double.NEGATIVE_INFINITY : Math.log1p(g);
    }

    /** The cascade's Commons Newton from 10%, in phi, with the analytic derivative. */
    IrrResult commonsNewton(Npv.Phi evaluator) {
      try {
        double phi =
            new NewtonRaphsonSolver(TOLERANCE)
                .solve(
                    MAX_EVALUATIONS,
                    new UnivariateDifferentiableFunction() {
                      @Override
                      public double value(double x) {
                        values++;
                        return evaluator.value(d, x);
                      }

                      @Override
                      public DerivativeStructure value(DerivativeStructure x) {
                        derivatives++;
                        iterations++;
                        Npv.Evaluation e = evaluator.both(d, x.getValue());
                        return new DerivativeStructure(1, 1, e.value(), e.derivative());
                      }
                    },
                    Math.log1p(NEWTON_START_RATE));
        if (Double.isFinite(phi)) return phiRoot(phi, IrrResult.Path.NEWTON);
      } catch (TooManyEvaluationsException | NoBracketingException e) {
        // No result.
      }
      return null;
    }

    // ---------------------------------------------------- L6: analysis and analytic brackets

    IrrResult bounded() {
      Npv.Phi evaluator = Npv.Phi.INCREMENTAL;
      Analysis a = Analysis.of(d);
      IrrResult direct = directive(a);
      if (direct != null) return direct;
      if (a.guaranteed() && Double.isFinite(a.bracketLow()) && Double.isFinite(a.bracketHigh())) {
        IrrResult r =
            brent(evaluator, a.bracketLow(), a.bracketHigh(), inBracket(a, mappedGuess()), false);
        if (r != null) return r;
      }
      IrrResult r = commonsNewton(evaluator);
      return r != null ? r : failed();
    }

    // ------------------------------------------------------- L7, L8: Newton-first plans

    IrrResult planned(boolean usePade) {
      Npv.Phi evaluator = Npv.Phi.INCREMENTAL;
      Analysis a = Analysis.of(d);
      IrrResult direct = directive(a);
      if (direct != null) return direct;
      int direction = a.directive() == Analysis.Directive.POSITIVE ? 1 : -1;
      boolean bracketed =
          a.guaranteed() && Double.isFinite(a.bracketLow()) && Double.isFinite(a.bracketHigh());
      if (!bracketed) {
        // Heuristic plan: one Newton attempt, no Brent.
        IrrResult r = newton(evaluator, Guess.heuristic(d.moments(), usePade), MAX_EVALUATIONS);
        return r != null ? r : failed();
      }
      double guess = Guess.waterfall(d, d.moments(), direction, usePade);
      IrrResult r = newton(evaluator, guess, GUARANTEED_NEWTON_ITERATIONS);
      // Direction gate: reject a root on the wrong side that also disagrees with the return.
      if (r != null && !(r.phi() * direction < 0 && r.phi() * d.totalReturn() < 0)) return r;
      r = brent(evaluator, a.bracketLow(), a.bracketHigh(), inBracket(a, guess), true);
      return r != null ? r : failed();
    }

    IrrResult directive(Analysis a) {
      if (a.directive() == Analysis.Directive.ZERO)
        return result(0, 0, IrrResult.Status.TERMINAL, IrrResult.Path.ZERO);
      if (a.directive() == Analysis.Directive.NO_SOLUTION)
        return result(Double.NaN, Double.NaN, IrrResult.Status.NO_SOLUTION, IrrResult.Path.NONE);
      return null;
    }

    /** A Brent start strictly inside the bracket: the guess, or the midpoint if it is outside. */
    static double inBracket(Analysis a, double guess) {
      double lo = a.bracketLow(), hi = a.bracketHigh();
      return guess > lo && guess < hi ? guess : 0.5 * (lo + hi);
    }

    // ------------------------------------------------------------------------ solvers in phi

    /** catchBudget: the optimized plans treat an exhausted Brent budget as a failed stage. */
    IrrResult brent(Npv.Phi evaluator, double lo, double hi, double start, boolean catchBudget) {
      try {
        double phi =
            new BrentSolver(TOLERANCE)
                .solve(
                    MAX_EVALUATIONS,
                    x -> {
                      values++;
                      return evaluator.value(d, x);
                    },
                    lo,
                    hi,
                    start);
        return phiRoot(roundDown(phi), IrrResult.Path.BRENT);
      } catch (IllegalArgumentException e) {
        return null;
      } catch (TooManyEvaluationsException e) {
        if (!catchBudget) throw e;
        return null;
      }
    }

    /** Converges on step size. One combined value-and-derivative evaluation per iteration. */
    IrrResult newton(Npv.Phi evaluator, double phi, int maxIterations) {
      if (!Double.isFinite(phi)) return null;
      for (int k = 0; k < maxIterations; k++) {
        derivatives++;
        iterations++;
        Npv.Evaluation e = evaluator.both(d, phi);
        double slope = e.derivative();
        if (slope == 0 || !Double.isFinite(slope) || !Double.isFinite(e.value())) return null;
        double next = phi - e.value() / slope;
        if (!Double.isFinite(next)) return null;
        // The step vanished in rounding while the value is not zero.
        if (next == phi && e.value() != 0) return null;
        if (Math.abs(next - phi) <= TOLERANCE) return phiRoot(next, IrrResult.Path.NEWTON);
        phi = next;
      }
      return null;
    }
  }
}
