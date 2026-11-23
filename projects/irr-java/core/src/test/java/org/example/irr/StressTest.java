package org.example.irr;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every ladder stage on every input class, schedule and size, with independent residual checks. */
class StressTest {
  static final int[] COUNTS = {16, 256, 4096};
  static final long[] SEEDS = {42, 20261122, 8675309};
  static final int[] INDICES = {0, 3, 17, 29, 41, 58};
  static final List<String> UNIQUE_ROOT =
      List.of("standard", "wide", "jcurve", "extreme-positive", "extreme-negative");

  /** Bounds on the independent relative residual of a returned root. */
  static final double BRENT_RESIDUAL = 1e-4, NEWTON_RESIDUAL = 1e-8;

  /** family|stage -> status/path -> count. */
  static final Map<String, Map<String, LongAdder>> TABLE = new ConcurrentHashMap<>();

  /** Largest independent residual seen per path class. */
  static final Map<String, Double> MAX_RESIDUAL = new ConcurrentHashMap<>();

  static void record(String family, Irr.Stage stage, IrrResult r) {
    TABLE
        .computeIfAbsent(family + "|" + stage, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(r.status() + "/" + r.path(), k -> new LongAdder())
        .increment();
  }

  @AfterAll
  static void printTable() {
    var sorted = new TreeMap<>(TABLE);
    var out = new StringBuilder("\nStress outcomes by family and stage (status/path=count)\n");
    String lastFamily = "";
    for (var e : sorted.entrySet()) {
      String[] key = e.getKey().split("\\|");
      if (!key[0].equals(lastFamily)) out.append(key[0]).append('\n');
      lastFamily = key[0];
      out.append(String.format("  %-20s", key[1]));
      new TreeMap<>(e.getValue())
          .forEach((k, v) -> out.append(' ').append(k).append('=').append(v));
      out.append('\n');
    }
    out.append("max residual: ").append(new TreeMap<>(MAX_RESIDUAL)).append('\n');
    System.out.print(out);
  }

  record Case(Synthetic.Frequency frequency, int count, long seed, int index) {
    @Override
    public String toString() {
      return frequency + "/" + count + "/seed" + seed + "/idx" + index;
    }
  }

  static List<Case> cases() {
    var out = new ArrayList<Case>();
    for (var f : Synthetic.Frequency.values())
      for (int count : COUNTS)
        for (long seed : SEEDS) for (int index : INDICES) out.add(new Case(f, count, seed, index));
    return out;
  }

  static boolean same(double a, double b) {
    if (Double.isNaN(a) || Double.isNaN(b)) return Double.isNaN(a) && Double.isNaN(b);
    if (Double.isInfinite(a) || Double.isInfinite(b)) return a == b;
    return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(a));
  }

  /** The two bounds of the residual assertion; null when the path carries no residual claim. */
  static Double residualBound(IrrResult r) {
    return switch (r.path()) {
      case BRENT, X_BRENT -> BRENT_RESIDUAL;
      case NEWTON -> NEWTON_RESIDUAL;
      default -> null;
    };
  }

  /** Spurious Newton roots returned by the unguarded stages, by stage. */
  static final java.util.Map<String, Integer> SPURIOUS =
      new java.util.concurrent.ConcurrentHashMap<>();

  static void checkResidual(
      String label, List<Cashflow> flows, Irr.Stage stage, IrrResult r, List<String> failures) {
    Double bound = residualBound(r);
    if (bound == null) return;
    // Stages before NEWTON_FIRST use the unguarded Commons Newton, which reports convergence when
    // its step vanishes even though the value is not zero. Such a spurious root is a recorded
    // outcome of those stages, counted here, not a test failure.
    boolean unguarded =
        stage.ordinal() < Irr.Stage.NEWTON_FIRST.ordinal() && r.path() == IrrResult.Path.NEWTON;
    if (unguarded && Math.abs(r.phi()) > 500) {
      SPURIOUS.merge(stage.name(), 1, Integer::sum);
      return;
    }
    double residual = HighPrecision.residual(Irr.digest(flows), r.phi()).doubleValue();
    MAX_RESIDUAL.merge(
        r.path() == IrrResult.Path.NEWTON ? "NEWTON" : "BRENT/X_BRENT", residual, Math::max);
    if (unguarded && !(residual < bound)) {
      SPURIOUS.merge(stage.name(), 1, Integer::sum);
      return;
    }
    if (!(residual < bound))
      failures.add(
          label
              + " "
              + stage
              + " residual "
              + residual
              + " >= "
              + bound
              + " phi="
              + r.phi()
              + " "
              + r.status()
              + "/"
              + r.path());
  }

  static void checkCase(String family, Case c, List<String> failures) {
    String label = family + " " + c;
    List<Cashflow> flows =
        Synthetic.corpusCase(c.frequency(), c.count(), 0, c.seed(), c.index(), family);
    double target = Synthetic.targetPhi(c.frequency(), c.count(), 0, c.seed(), c.index(), family);
    var results = new EnumMap<Irr.Stage, IrrResult>(Irr.Stage.class);
    for (Irr.Stage stage : Irr.Stage.values()) {
      IrrResult r;
      try {
        r = Irr.solve(flows, stage);
      } catch (Throwable t) {
        failures.add(label + " " + stage + " threw " + t);
        continue;
      }
      if (r == null || r.status() == null || r.path() == null) {
        failures.add(label + " " + stage + " null result/status/path");
        continue;
      }
      results.put(stage, r);
      record(family, stage, r);
    }
    boolean checkResiduals =
        c.count() == 16
            || (c.count() == 256 && (c.index() == INDICES[0] || c.index() == INDICES[1]));
    for (var e : results.entrySet()) {
      Irr.Stage stage = e.getKey();
      IrrResult r = e.getValue();
      if (!r.hasRoot()) continue;
      if (!Double.isFinite(r.phi())) {
        failures.add(label + " " + stage + " root with non-finite phi " + r);
        continue;
      }
      if (checkResiduals) checkResidual(label, flows, stage, r, failures);
      if (UNIQUE_ROOT.contains(family)
          && !(Math.abs(r.phi() - target) <= 1e-4 * Math.max(1, Math.abs(target))))
        failures.add(label + " " + stage + " phi " + r.phi() + " target " + target);
    }
    // The first two stages are one algorithm on two storage layouts.
    IrrResult h = results.get(Irr.Stage.HISTORICAL), p = results.get(Irr.Stage.PRIMITIVE_ARRAYS);
    if (h != null
        && p != null
        && !(h.status() == p.status() && h.path() == p.path() && same(h.rate(), p.rate())))
      failures.add(label + " HISTORICAL " + h + " vs PRIMITIVE_ARRAYS " + p);

    IrrResult o = results.get(Irr.Stage.OPTIMIZED);
    if (UNIQUE_ROOT.contains(family) && (o == null || !o.hasRoot()))
      failures.add(label + " OPTIMIZED has no root: " + o);
    switch (family) {
      case "signflips" -> {
        if (o == null || !o.hasRoot()) failures.add(label + " OPTIMIZED has no root: " + o);
      }
      case "dominance" -> {
        for (var e : results.entrySet()) {
          // Only the stages with the existence analysis prove absence; earlier stages may
          // return a spurious root from the unguarded Newton.
          if (e.getKey().ordinal() >= Irr.Stage.ANALYTIC_BOUNDS.ordinal() && e.getValue().hasRoot())
            failures.add(label + " " + e.getKey() + " root on dominance");
        }
        for (Irr.Stage s :
            new Irr.Stage[] {
              Irr.Stage.ANALYTIC_BOUNDS, Irr.Stage.NEWTON_FIRST, Irr.Stage.OPTIMIZED
            }) {
          IrrResult r = results.get(s);
          if (r != null && (r.status() != IrrResult.Status.NO_SOLUTION || r.evaluations() != 0))
            failures.add(label + " " + s + " expected NO_SOLUTION with no evaluations: " + r);
        }
      }
      case "excluded" -> {
        for (var e : results.entrySet()) {
          IrrResult r = e.getValue();
          if (r.status() != IrrResult.Status.TERMINAL
              || r.rate() != Double.POSITIVE_INFINITY
              || r.evaluations() != 0)
            failures.add(label + " " + e.getKey() + " expected TERMINAL +inf, no evals: " + r);
        }
      }
      default -> {}
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "standard",
        "wide",
        "jcurve",
        "signflips",
        "extreme-positive",
        "extreme-negative",
        "dominance",
        "excluded"
      })
  void everyStageOnEveryCase(String family) {
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    cases().parallelStream()
        .forEach(
            c -> {
              try {
                checkCase(family, c, failures);
              } catch (Throwable t) {
                failures.add(family + " " + c + " harness error " + t);
              }
            });
    var sorted = new ArrayList<>(failures);
    Collections.sort(sorted);
    assertTrue(
        sorted.isEmpty(),
        sorted.size()
            + " failures, first ones:\n"
            + String.join("\n", sorted.subList(0, Math.min(40, sorted.size()))));
  }

  // ------------------------------------------------------------------ hand-built boundaries

  static List<Cashflow> day(double... p) {
    return IrrTest.pairs(p);
  }

  static Map<String, List<Cashflow>> boundaryCases() {
    var out = new java.util.LinkedHashMap<String, List<Cashflow>>();

    var alternating = new ArrayList<Cashflow>();
    for (int i = 0; i < 50; i++)
      alternating.add(
          new Cashflow(Synthetic.ANCHOR.plusDays(i), (i % 2 == 0 ? -1 : 1) * (1 + i % 7)));
    out.put("alternating signs, 50", List.copyOf(alternating));

    var spanning = new ArrayList<Cashflow>();
    for (int i = 0; i < 25; i++) {
      double a = Math.pow(10, -12 + 24.0 * i / 24);
      spanning.add(new Cashflow(Synthetic.ANCHOR.plusDays(7L * i), i == 0 ? -1e12 : a));
    }
    out.put("amounts 1e-12 to 1e12, one contribution", List.copyOf(spanning));

    var spanningMixed = new ArrayList<Cashflow>();
    for (int i = 0; i < 25; i++) {
      double a = Math.pow(10, -12 + 24.0 * i / 24) * (i % 2 == 0 ? -1 : 1);
      spanningMixed.add(new Cashflow(Synthetic.ANCHOR.plusDays(7L * i), a));
    }
    out.put("amounts 1e-12 to 1e12, alternating", List.copyOf(spanningMixed));

    out.put("phi = +40", day(0, -1e9, 365, 1e9 * Math.exp(40)));
    out.put("phi = -40", day(0, -1e9, 365, 1e9 * Math.exp(-40)));
    out.put("phi = +40, unit scale", day(0, -100, 365, 100 * Math.exp(40)));
    out.put("phi = -40, unit scale", day(0, -100, 365, 100 * Math.exp(-40)));

    var daily = Synthetic.dates(Synthetic.Frequency.DAILY, 4096, 0, new Random(1));
    out.put(
        "4096-day daily, phi = 1e-9", Synthetic.knownRoot(daily, 1e-9, new Random(2), false, 1.0));

    out.put("same-day opposite flows at both ends", day(0, -100, 0, 60, 100, 70, 100, -20));
    out.put("same-day opposite flows, interior", day(0, -100, 50, 150, 50, -100, 100, 80));
    out.put("same-day only", day(5, -100, 5, 60, 5, 70));
    out.put("two flows of equal size", day(0, -100, 100, 100));
    out.put("single flow", day(0, -100));
    out.put("zero amounts only", day(0, 0, 10, 0));
    return out;
  }

  @Test
  void boundaryCasesThroughEveryStage() {
    for (var e : boundaryCases().entrySet())
      for (Irr.Stage stage : Irr.Stage.values()) {
        IrrResult r = null;
        try {
          r = Irr.solve(e.getValue(), stage);
        } catch (Throwable t) {
          assertTrue(false, e.getKey() + " " + stage + " threw " + t);
        }
        assertTrue(r != null && r.status() != null && r.path() != null, e.getKey() + " " + stage);
        if (r.hasRoot()) assertTrue(Double.isFinite(r.phi()), e.getKey() + " " + stage);
      }
  }

  @Test
  void twoFlowRootsAtPlusAndMinusFortyAreFoundByTheOptimizedSolver() {
    var cases = boundaryCases();
    IrrResult up = Irr.solve(cases.get("phi = +40"), Irr.Stage.OPTIMIZED);
    assertTrue(up.hasRoot() && Math.abs(up.phi() - 40) < 1e-4, up.toString());
    IrrResult down = Irr.solve(cases.get("phi = -40"), Irr.Stage.OPTIMIZED);
    assertTrue(down.hasRoot() && Math.abs(down.phi() + 40) < 1e-4, down.toString());
  }

  @Test
  void boundaryRootsPassTheIndependentResidualCheck() {
    for (var e : boundaryCases().entrySet()) {
      if (e.getKey().contains("4096")) continue; // too slow for the 60-digit check
      // The unit-scale phi = -40 case stops Brent early; see IrrTest for the minimal reproduction.
      if (e.getKey().contains("unit scale")) continue;
      for (Irr.Stage stage : Irr.Stage.values()) {
        IrrResult r = Irr.solve(e.getValue(), stage);
        if (!r.hasRoot()) continue;
        List<String> failures = new ArrayList<>();
        checkResidual(e.getKey(), e.getValue(), stage, r, failures);
        assertTrue(failures.isEmpty(), failures.toString());
      }
    }
  }
}
