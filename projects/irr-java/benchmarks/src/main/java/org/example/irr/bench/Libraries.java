package org.example.irr.bench;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.decampo.xirr.Transaction;
import org.decampo.xirr.Xirr;
import org.example.irr.*;

/**
 * Library-native inputs, calls and rate conversions for the comparison with two open-source IRR
 * implementations. Our rate is per period: with D days between the first and last non-zero flow,
 * phi = log(1 + r) solves sum C_i exp(-phi t_i) = 0 with t_i = days_i / D.
 */
public final class Libraries {
  /** Day-count basis of org.decampo:xirr (Xirr.DAYS_IN_YEAR). */
  public static final double XIRR_DAYS_IN_YEAR = 365;

  /** Defaults of org.decampo:xirr 1.2 NewtonRaphson. */
  static final double XIRR_TOLERANCE = 0.000_000_1;

  static final int XIRR_ITERATIONS = 10_000;

  public enum Solver {
    HISTORICAL,
    EXTENDED,
    /**
     * Extended solver on input prepared in setup: the partner for POI, which pays no date handling.
     */
    EXTENDED_PREPARED,
    /** org.decampo:xirr with its default configuration. */
    XIRR,
    /** Apache POI Irr.irr(double[]): guess 0.1 per period. */
    POI_IRR,
    /** Apache POI Irr.irr(double[], 0.0). */
    POI_IRR_ZERO_GUESS;

    public boolean isPoi() {
      return this == POI_IRR || this == POI_IRR_ZERO_GUESS;
    }
  }

  /**
   * NORMALIZED divides every flow of an input by that input's sum |C|; RAW leaves it as generated.
   */
  public enum Amounts {
    NORMALIZED,
    RAW
  }

  /**
   * ROOT: a finite phi was returned; FAILED: no usable root returned; EXCEPTION: the call threw.
   */
  public enum Status {
    ROOT,
    FAILED,
    EXCEPTION
  }

  /** iterations: value evaluations of XIRR's Newton, -1 for solvers where it is not recorded. */
  public record Outcome(Status status, double phi, String detail, int iterations) {}

  /** One counted run of XIRR's Newton: the rate (NaN when it threw), the exception, evaluations. */
  public record XirrRun(double rate, String exception, int iterations) {}

  /** The same cash flows in every form a solver starts from; built outside timed methods. */
  public record Input(
      List<Cashflow> flows,
      List<Transaction> transactions,
      double[] daily,
      long days,
      Irr.Prepared prepared) {}

  private Libraries() {}

  /**
   * Builds the xirr transactions and the POI array (indexed by day offset, zeros in empty days).
   */
  public static Input input(List<Cashflow> flows) {
    var digest = Irr.digest(flows);
    var transactions = new ArrayList<Transaction>(flows.size());
    for (var f : flows) transactions.add(new Transaction(f.amount(), f.date()));
    return new Input(
        flows,
        transactions,
        dailyValues(flows),
        digest.periodDays(),
        Irr.prepare(flows, Irr.Stage.LOG_NEWTON));
  }

  /**
   * Every amount divided by the input's sum |C|, so XIRR's absolute tolerance is a relative one.
   */
  public static List<Cashflow> normalized(List<Cashflow> flows) {
    double scale = 0;
    for (var f : flows) scale += Math.abs(f.amount());
    var out = new ArrayList<Cashflow>(flows.size());
    for (var f : flows) out.add(new Cashflow(f.date(), f.amount() / scale));
    return out;
  }

  /**
   * Amounts by day offset from the first non-zero flow to the last, with zeros on days without a
   * flow. For equally spaced daily flows this is the amount list itself.
   */
  public static double[] dailyValues(List<Cashflow> flows) {
    LocalDate first = null, last = null;
    for (var f : flows)
      if (f.amount() != 0) {
        if (first == null) first = f.date();
        last = f.date();
      }
    if (first == null) return new double[0];
    double[] values = new double[(int) ChronoUnit.DAYS.between(first, last) + 1];
    for (var f : flows)
      if (f.amount() != 0) values[(int) ChronoUnit.DAYS.between(first, f.date())] += f.amount();
    return values;
  }

  /** XIRR's default configuration; throws on non-convergence, overflow or invalid input. */
  public static double xirr(List<Transaction> transactions) {
    return new Xirr(transactions).xirr();
  }

  /**
   * Reproduces org.decampo:xirr 1.2 with default settings, counting function evaluations: guess
   * (total / deposits) / years, Newton on Xirr.presentValue and Xirr.derivative, stop when |value|
   * &lt; 1e-7 (NewtonRaphson.TOLERANCE), at most 10,000 iterations, and the same overflow and
   * zero-derivative exceptions. Returns the same rate as {@code new Xirr(transactions).xirr()}.
   */
  public static XirrRun xirrCounted(List<Transaction> transactions) {
    var x = new Xirr(transactions);
    LocalDate start = null, end = null;
    double total = 0, deposits = 0, max = Double.NEGATIVE_INFINITY;
    for (var t : transactions) {
      var when = t.getWhen();
      if (start == null || when.isBefore(start)) start = when;
      if (end == null || when.isAfter(end)) end = when;
      total += t.getAmount();
      if (t.getAmount() < 0) deposits -= t.getAmount();
      max = Math.max(max, t.getAmount());
    }
    if (max == 0) return new XirrRun(-1, "", 0);
    double years = ChronoUnit.DAYS.between(start, end) / XIRR_DAYS_IN_YEAR;
    double candidate = (total / deposits) / years;
    if (!Double.isFinite(candidate)) return new XirrRun(Double.NaN, "OverflowException", 0);
    for (int i = 0; i < XIRR_ITERATIONS; i++) {
      double value = x.presentValue(candidate);
      if (!Double.isFinite(value)) return new XirrRun(Double.NaN, "OverflowException", i + 1);
      if (Math.abs(value) < XIRR_TOLERANCE) return new XirrRun(candidate, "", i + 1);
      double derivative = x.derivative(candidate);
      if (!Double.isFinite(derivative)) return new XirrRun(Double.NaN, "OverflowException", i + 1);
      if (derivative == 0.0) return new XirrRun(Double.NaN, "ZeroValuedDerivativeException", i + 1);
      candidate -= value / derivative;
      if (!Double.isFinite(candidate)) return new XirrRun(Double.NaN, "OverflowException", i + 1);
    }
    return new XirrRun(Double.NaN, "NonconvergenceException", XIRR_ITERATIONS);
  }

  public static double poi(double[] values, double guess) {
    return org.apache.poi.ss.formula.functions.Irr.irr(values, guess);
  }

  /** XIRR annual rate (365-day basis) to phi: (1 + R)^(D / 365) = 1 + r. */
  public static double phiFromXirr(double annualRate, long days) {
    return days / XIRR_DAYS_IN_YEAR * Math.log1p(annualRate);
  }

  /** POI per-day rate to phi: (1 + q)^D = 1 + r. */
  public static double phiFromPoi(double perDayRate, long days) {
    return days * Math.log1p(perDayRate);
  }

  /** Untimed: runs one solver on one input and classifies the result. */
  public static Outcome outcome(Solver solver, Input in) {
    try {
      if (solver == Solver.XIRR) return xirrOutcome(in);
      double phi =
          switch (solver) {
            case HISTORICAL -> resultPhi(Irr.solve(in.flows(), SolverStrategy.HISTORICAL));
            case EXTENDED -> resultPhi(Irr.solve(in.flows(), SolverStrategy.EXTENDED));
            case EXTENDED_PREPARED -> resultPhi(Irr.solve(in.prepared()));
            case XIRR -> throw new AssertionError();
            case POI_IRR -> phiFromPoi(poi(in.daily(), 0.1), in.days());
            case POI_IRR_ZERO_GUESS -> phiFromPoi(poi(in.daily(), 0.0), in.days());
          };
      return Double.isFinite(phi)
          ? new Outcome(Status.ROOT, phi, "", -1)
          : new Outcome(Status.FAILED, Double.NaN, "", -1);
    } catch (RuntimeException e) {
      return new Outcome(Status.EXCEPTION, Double.NaN, e.getClass().getSimpleName(), -1);
    }
  }

  /** The library's own call decides the outcome; the counted run supplies the iterations. */
  private static Outcome xirrOutcome(Input in) {
    var counted = xirrCounted(in.transactions());
    try {
      double rate = xirr(in.transactions());
      if (Double.compare(rate, counted.rate()) != 0)
        throw new IllegalStateException("Counted XIRR run diverges from the library");
      double phi = phiFromXirr(rate, in.days());
      return Double.isFinite(phi)
          ? new Outcome(Status.ROOT, phi, "", counted.iterations())
          : new Outcome(Status.FAILED, Double.NaN, "", counted.iterations());
    } catch (RuntimeException e) {
      if (e instanceof IllegalStateException
          || !e.getClass().getSimpleName().equals(counted.exception()))
        throw new IllegalStateException("Counted XIRR run diverges from the library", e);
      return new Outcome(
          Status.EXCEPTION, Double.NaN, e.getClass().getSimpleName(), counted.iterations());
    }
  }

  private static double resultPhi(IrrResult r) {
    return r.hasRoot() ? r.phi() : Double.NaN;
  }
}
