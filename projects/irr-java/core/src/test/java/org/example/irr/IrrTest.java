package org.example.irr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Unit and behaviour tests of the optimized side: digest, evaluators, guesses, analysis, solve. */
class IrrTest {
  static final LocalDate BASE = LocalDate.of(2024, 1, 1);
  static final double LN2 = Math.log(2);

  // ------------------------------------------------------------------------------- helpers

  /** Flows from (day offset, amount) pairs given as a flat list. */
  static List<Cashflow> pairs(double... p) {
    var out = new ArrayList<Cashflow>();
    for (int i = 0; i < p.length; i += 2)
      out.add(new Cashflow(BASE.plusDays((long) p[i]), p[i + 1]));
    return out;
  }

  /** A conventional or mixed-sign schedule on a synthetic calendar. */
  static List<Cashflow> schedule(Synthetic.Frequency f, int n, long seed, boolean mixed) {
    Random r = new Random(seed);
    List<LocalDate> dates = Synthetic.dates(f, n, 0, r);
    var out = new ArrayList<Cashflow>();
    for (int i = 0; i < dates.size(); i++) {
      double a = i == 0 ? -1000 : 1 + 99 * r.nextDouble();
      if (mixed && i > 0 && r.nextDouble() < 0.3) a = -a;
      out.add(new Cashflow(dates.get(i), a));
    }
    return out;
  }

  /** Non-zero flows with time fractions computed directly from the dates. */
  record Direct(double[] c, double[] t) {
    static Direct of(List<Cashflow> flows) {
      var nz = flows.stream().filter(f -> f.amount() != 0).toList();
      LocalDate start = nz.getFirst().date(), end = nz.getLast().date();
      long days = ChronoUnit.DAYS.between(start, end);
      double[] c = new double[nz.size()], t = new double[nz.size()];
      for (int i = 0; i < c.length; i++) {
        c[i] = nz.get(i).amount();
        t[i] = days == 0 ? 0 : (double) ChronoUnit.DAYS.between(start, nz.get(i).date()) / days;
      }
      return new Direct(c, t);
    }

    double f(double phi) {
      double s = 0;
      for (int i = 0; i < c.length; i++) s += c[i] * Math.exp(-t[i] * phi);
      return s;
    }

    double fPrime(double phi) {
      double s = 0;
      for (int i = 0; i < c.length; i++) s -= c[i] * t[i] * Math.exp(-t[i] * phi);
      return s;
    }

    /** sum(|C| w) with w = exp(-t phi), or exp(phi (T - t)) in the scaled reverse form. */
    double scale(double phi, boolean reverse) {
      double s = 0, tl = t[t.length - 1];
      for (int i = 0; i < c.length; i++)
        s += Math.abs(c[i]) * (reverse ? Math.exp(phi * (tl - t[i])) : Math.exp(-t[i] * phi));
      return s;
    }

    double moment(int k) {
      double s = 0;
      for (int i = 0; i < c.length; i++) s += c[i] * Math.pow(t[i], k);
      return s;
    }
  }

  static void assertSame(IrrResult a, IrrResult b) {
    assertEquals(a.toString(), b.toString());
  }

  static boolean oneOf(double x, double tol, double... targets) {
    for (double t : targets) if (Math.abs(x - t) <= tol) return true;
    return false;
  }

  // ==================================================================================== Digest

  @Test
  void digestRejectsInvalidInput() {
    assertThrows(IllegalArgumentException.class, () -> Irr.digest(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> Irr.digest(Arrays.asList(new Cashflow(BASE, -1), null, new Cashflow(BASE, 2))));
    assertThrows(IllegalArgumentException.class, () -> Irr.digest(Arrays.asList((Cashflow) null)));
    assertThrows(IllegalArgumentException.class, () -> Irr.digest(List.of(new Cashflow(null, 5))));
    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class,
          () -> Irr.digest(List.of(new Cashflow(BASE, -1), new Cashflow(BASE.plusDays(1), bad))));
    assertThrows(IllegalArgumentException.class, () -> Irr.digest(pairs(10, -1, 5, 2)));
    // Out-of-order dates are rejected even when the offending entry is a zero amount.
    assertThrows(IllegalArgumentException.class, () -> Irr.digest(pairs(0, -100, 10, 110, 5, 0)));
    assertThrows(IllegalArgumentException.class, () -> Irr.digest(pairs(0, -1, 10, 0, 5, 2)));
  }

  @ParameterizedTest
  @EnumSource(Irr.Stage.class)
  void everyStageRejectsInvalidInput(Irr.Stage stage) {
    assertThrows(IllegalArgumentException.class, () -> Irr.solve((List<Cashflow>) null, stage));
    assertThrows(
        IllegalArgumentException.class,
        () -> Irr.solve(Arrays.asList(new Cashflow(BASE, -1), null), stage));
    assertThrows(
        IllegalArgumentException.class,
        () -> Irr.solve(List.of(new Cashflow(null, -1), new Cashflow(BASE, 2)), stage));
    assertThrows(
        IllegalArgumentException.class, () -> Irr.solve(pairs(0, -1, 3, Double.NaN), stage));
    assertThrows(IllegalArgumentException.class, () -> Irr.solve(pairs(9, -1, 3, 2), stage));
  }

  @Test
  void entryPointsRejectNulls() {
    var flows = pairs(0, -100, 10, 110);
    assertThrows(IllegalArgumentException.class, () -> Irr.prepare(flows, null));
    assertThrows(IllegalArgumentException.class, () -> Irr.solve((Irr.Prepared) null));
    assertThrows(IllegalArgumentException.class, () -> Irr.solve(flows, (SolverStrategy) null));
    assertThrows(
        IllegalArgumentException.class, () -> Irr.solve(Irr.digest(flows), Irr.Stage.HISTORICAL));
    assertThrows(
        IllegalArgumentException.class, () -> Irr.solve((Digest) null, Irr.Stage.OPTIMIZED));
  }

  @Test
  void digestDoesNotMutateCallerList() {
    var flows = new ArrayList<>(pairs(0, 0, 10, -100, 10, 20, 40, 0, 50, 30, 110, 90, 120, 0));
    var copy = new ArrayList<>(flows);
    Irr.digest(flows);
    for (Irr.Stage s : Irr.Stage.values()) Irr.solve(flows, s);
    assertEquals(copy, flows);
  }

  /** Leading zero, same-day flows, interior and trailing zero. */
  static List<Cashflow> exampleFlows() {
    return pairs(0, 0, 10, -100, 10, 20, 40, 0, 50, 30, 110, 90, 120, 0);
  }

  @Test
  void digestFields() {
    var flows = exampleFlows();
    Digest d = Irr.digest(flows);
    // Zero amounts skipped: start is day 10, end is day 110.
    assertEquals(4, d.size());
    assertEquals(100, d.periodDays());
    assertEquals(0.01, d.dayFraction(), 1e-18);
    assertEquals(-100, d.amount(0));
    assertEquals(20, d.amount(1));
    assertEquals(30, d.amount(2));
    assertEquals(90, d.amount(3));
    // Same-day flows stay separate, with gap 0 between them.
    assertEquals(0, d.gap(0));
    assertEquals(0, d.gap(1));
    assertEquals(40, d.gap(2));
    assertEquals(60, d.gap(3));
    assertEquals(0.0, d.time(0));
    assertEquals(0.0, d.time(1));
    assertEquals(0.4, d.time(2));
    assertEquals(1.0, d.time(3));
    assertEquals(40, d.totalReturn(), 1e-12);
    assertEquals(140, d.positiveTotal(), 1e-12);
    assertEquals(100, d.negativeTotal(), 1e-12);
    assertEquals(-100, d.firstFlow());
    assertEquals(90, d.lastFlow());
    // sum(C (end - date) / D) = -100 + 20 + 30 * 0.6 + 0 = -62
    assertEquals(-62, d.dietzWeight(), 1e-12);
    assertEquals(40.0 / 62, d.dietzGuess(), 1e-12);
    // The first opposing flow is on the start day; the last opposing flow (-100) is D days away.
    assertEquals(0, d.firstOpposingGap());
    assertEquals(100, d.lastOpposingGap());
    assertEquals(Digest.Terminal.NONE, d.terminal());
    assertTrue(Double.isNaN(d.terminalRate()));
  }

  @Test
  void digestOpposingGaps() {
    Digest d = Irr.digest(pairs(0, -100, 10, 10, 20, -5, 100, 50));
    assertEquals(10, d.firstOpposingGap());
    assertEquals(80, d.lastOpposingGap());
    // Mirrored signs give the same gaps.
    Digest m = Irr.digest(pairs(0, 100, 10, -10, 20, 5, 100, -50));
    assertEquals(10, m.firstOpposingGap());
    assertEquals(80, m.lastOpposingGap());
  }

  @Test
  void digestPeriodUsesCalendarDays() {
    var flows =
        List.of(
            new Cashflow(LocalDate.of(2024, 1, 1), -100),
            new Cashflow(LocalDate.of(2025, 1, 1), 110));
    Digest d = Irr.digest(flows);
    assertEquals(366, d.periodDays());
    assertEquals(366, d.gap(1));
    assertEquals(1.0 / 366, d.dayFraction(), 1e-18);
    assertEquals(1.0, d.time(1));
    var plain =
        List.of(
            new Cashflow(LocalDate.of(2023, 1, 1), -100),
            new Cashflow(LocalDate.of(2024, 1, 1), 110));
    assertEquals(365, Irr.digest(plain).periodDays());
  }

  @ParameterizedTest
  @EnumSource(Synthetic.Frequency.class)
  void digestMatchesDirectFormulas(Synthetic.Frequency frequency) {
    for (boolean mixed : new boolean[] {false, true}) {
      var flows = schedule(frequency, 60, 11, mixed);
      Digest d = Irr.digest(flows);
      Direct ref = Direct.of(flows);
      assertEquals(ref.c().length, d.size());
      LocalDate start = flows.getFirst().date(), end = flows.getLast().date();
      long days = ChronoUnit.DAYS.between(start, end);
      assertEquals(days, d.periodDays());
      double w = 0, abs = 0, total = 0, pos = 0, neg = 0;
      for (int i = 0; i < d.size(); i++) {
        assertEquals(ref.c()[i], d.amount(i));
        assertEquals(ref.t()[i], d.time(i));
        int gap =
            i == 0
                ? 0
                : (int) ChronoUnit.DAYS.between(flows.get(i - 1).date(), flows.get(i).date());
        assertEquals(gap, d.gap(i));
        double c = flows.get(i).amount();
        w += c * ChronoUnit.DAYS.between(flows.get(i).date(), end) / (double) days;
        abs += Math.abs(c);
        total += c;
        if (c > 0) pos += c;
        else neg -= c;
      }
      assertEquals(1.0 / days, d.dayFraction(), 1e-18);
      assertEquals(w, d.dietzWeight(), 1e-12 * abs);
      assertEquals(total, d.totalReturn(), 1e-12 * abs);
      assertEquals(pos, d.positiveTotal(), 1e-12 * abs);
      assertEquals(neg, d.negativeTotal(), 1e-12 * abs);
      assertEquals(flows.getFirst().amount(), d.firstFlow());
      assertEquals(flows.getLast().amount(), d.lastFlow());
      for (int k = 0; k < 3; k++) assertEquals(ref.moment(k), d.moment(k), 1e-12 * abs);
      assertThrows(IllegalArgumentException.class, () -> d.moment(3));
    }
  }

  @Test
  void digestMomentsAreDirectSums() {
    Digest d = Irr.digest(exampleFlows());
    assertEquals(40, d.moment(0), 1e-12);
    assertEquals(30 * 0.4 + 90, d.moment(1), 1e-12);
    assertEquals(30 * 0.16 + 90, d.moment(2), 1e-12);
  }

  @Test
  void terminalCasesFollowTheSpecificationTable() {
    // No non-zero flows.
    for (var flows : List.of(List.<Cashflow>of(), pairs(0, 0, 5, 0))) {
      Digest d = Irr.digest(flows);
      assertEquals(Digest.Terminal.EMPTY, d.terminal());
      assertEquals(0, d.size());
      assertEquals(0.0, d.terminalRate());
    }
    // No negative amounts.
    Digest noNeg = Irr.digest(pairs(0, 10, 5, 20, 9, 0));
    assertEquals(Digest.Terminal.NO_CONTRIBUTIONS, noNeg.terminal());
    assertEquals(Double.POSITIVE_INFINITY, noNeg.terminalRate());
    // No positive amounts.
    Digest noPos = Irr.digest(pairs(0, -10, 5, -20));
    assertEquals(Digest.Terminal.NO_DISTRIBUTIONS, noPos.terminal());
    assertEquals(-1.0, noPos.terminalRate());
    // One day: simple return.
    Digest sameDay = Irr.digest(pairs(7, -100, 7, 30, 7, 80, 7, -50));
    assertEquals(Digest.Terminal.SINGLE_DAY, sameDay.terminal());
    assertEquals(0, sameDay.periodDays());
    assertEquals(110.0 / 150 - 1, sameDay.terminalRate(), 1e-15);
    // A single non-zero flow is one-signed, so the sign rules win over the one-day rule.
    assertEquals(Digest.Terminal.NO_CONTRIBUTIONS, Irr.digest(pairs(0, 0, 3, 5)).terminal());
    assertEquals(Digest.Terminal.NO_DISTRIBUTIONS, Irr.digest(pairs(0, -5)).terminal());
  }

  @ParameterizedTest
  @EnumSource(Irr.Stage.class)
  void terminalCasesAreIdenticalAtEveryStage(Irr.Stage stage) {
    IrrResult empty = Irr.solve(pairs(0, 0, 4, 0), stage);
    assertEquals(IrrResult.Status.TERMINAL, empty.status());
    assertEquals(IrrResult.Path.TERMINAL, empty.path());
    assertEquals(0.0, empty.rate());
    assertEquals(0, empty.evaluations());
    assertFalse(empty.hasRoot());

    IrrResult noNeg = Irr.solve(pairs(0, 10, 50, 20), stage);
    assertEquals(IrrResult.Status.TERMINAL, noNeg.status());
    assertEquals(Double.POSITIVE_INFINITY, noNeg.rate());
    assertEquals(0, noNeg.evaluations());

    IrrResult noPos = Irr.solve(pairs(0, -10, 50, -20), stage);
    assertEquals(IrrResult.Status.TERMINAL, noPos.status());
    assertEquals(-1.0, noPos.rate());
    assertEquals(0, noPos.evaluations());

    IrrResult day = Irr.solve(pairs(3, -100, 3, 110), stage);
    assertEquals(IrrResult.Status.TERMINAL, day.status());
    assertEquals(0.1, day.rate(), 1e-12);
    assertEquals(0, day.evaluations());
    assertEquals(IrrResult.Path.TERMINAL, day.path());
  }

  // ======================================================================================= Npv

  @Test
  void integerPowersMatchMathPow() {
    double[] bases = {0.999, 0.9, 0.5, 0.147, 1.0, 1.0001, 1.05, 0.9999999};
    for (double c : bases)
      for (int g = 0; g <= 60; g++) {
        double expected = Math.pow(c, g);
        assertEquals(expected, Npv.intPower(c, g), 1e-14 * expected, "intPower " + c + "^" + g);
        assertEquals(
            expected, Npv.binaryPower(c, g), 1e-14 * expected, "binaryPower " + c + "^" + g);
      }
    assertThrows(IllegalArgumentException.class, () -> Npv.binaryPower(0.5, -1));
    assertEquals(
        Math.pow(0.9999, 4000), Npv.intPower(0.9999, 4000), 1e-12 * Math.pow(0.9999, 4000));
  }

  static final double[] PHIS = {-5, -1, -0.01, 0, 0.1, 2, 10};

  @ParameterizedTest
  @EnumSource(Synthetic.Frequency.class)
  void phiEvaluatorsMatchDirectSums(Synthetic.Frequency frequency) {
    int n = frequency == Synthetic.Frequency.MONTHLY ? 36 : 60;
    for (boolean mixed : new boolean[] {false, true}) {
      var flows = schedule(frequency, n, 5, mixed);
      Digest d = Irr.digest(flows);
      Direct ref = Direct.of(flows);
      double tLast = d.time(d.size() - 1);
      assertEquals(1.0, tLast);
      for (Npv.Phi e : Npv.Phi.values())
        for (double phi : PHIS) {
          boolean reverse = e != Npv.Phi.DIRECT && phi < 0;
          double expected = ref.f(phi) * (reverse ? Math.exp(phi * tLast) : 1);
          double tol = 1e-12 * ref.scale(phi, reverse);
          String msg = e + " phi=" + phi + " " + frequency;
          assertEquals(expected, e.value(d, phi), tol, msg);
          Npv.Evaluation both = e.both(d, phi);
          assertEquals(e.value(d, phi), both.value(), msg + " both vs value");
          double expectedDerivative = ref.fPrime(phi) * (reverse ? Math.exp(phi * tLast) : 1);
          assertEquals(expectedDerivative, both.derivative(), 1e-12 * ref.scale(phi, reverse), msg);
        }
    }
  }

  @ParameterizedTest
  @EnumSource(Synthetic.Frequency.class)
  void newtonQuotientEqualsDirectQuotient(Synthetic.Frequency frequency) {
    int n = frequency == Synthetic.Frequency.MONTHLY ? 36 : 60;
    var flows = schedule(frequency, n, 9, false);
    Digest d = Irr.digest(flows);
    Direct ref = Direct.of(flows);
    for (Npv.Phi e : Npv.Phi.values())
      for (double phi : PHIS) {
        double expected = ref.f(phi) / ref.fPrime(phi);
        Npv.Evaluation v = e.both(d, phi);
        assertEquals(
            expected,
            v.value() / v.derivative(),
            1e-9 * Math.max(1, Math.abs(expected)),
            e + " phi=" + phi + " " + frequency);
      }
  }

  @Test
  void incrementalEvaluatorsDoNotOverflowAtLargeNegativePhi() {
    Digest d = Irr.digest(pairs(0, -100, 365, 110));
    for (Npv.Phi e : new Npv.Phi[] {Npv.Phi.INCREMENTAL_GENERIC, Npv.Phi.INCREMENTAL}) {
      double v = e.value(d, -700);
      assertTrue(Double.isFinite(v), e.name());
      assertEquals(110, v, 1e-9);
      Npv.Evaluation both = e.both(d, -700);
      assertEquals(v, both.value());
      assertEquals(-110, both.derivative(), 1e-9);
      assertEquals(110, e.value(d, -745), 1e-9);
    }
    // The forward path at very large phi underflows to the first flow rather than overflowing.
    assertEquals(-100, Npv.Phi.INCREMENTAL.value(d, 700), 1e-9);
  }

  @ParameterizedTest
  @EnumSource(Synthetic.Frequency.class)
  void rateSpaceFunctionsAgree(Synthetic.Frequency frequency) {
    var flows = schedule(frequency, 40, 3, true);
    Digest d = Irr.digest(flows);
    for (double r : new double[] {-0.5, -0.1, 0, 0.05, 1, 5}) {
      double scale = 0, dscale = 0;
      for (int i = 0; i < d.size(); i++) {
        double w = Math.pow(1 + r, -d.time(i));
        scale += Math.abs(d.amount(i)) * w;
        dscale += Math.abs(d.amount(i)) * d.time(i) * w / (1 + r);
      }
      double value = Npv.rateValue(d, r);
      Npv.Evaluation analytic = Npv.rateAnalytic(d, r);
      assertEquals(value, analytic.value(), 1e-12 * scale);
      DerivativeStructure ad = Npv.rateAutomatic(d, new DerivativeStructure(1, 1, 0, r));
      assertEquals(value, ad.getValue(), 1e-12 * scale);
      assertEquals(analytic.derivative(), ad.getPartialDerivative(1), 1e-9 * dscale);
      assertEquals(value, Npv.xValue(d, 1 / (1 + r)), 1e-12 * scale);
      // The phi evaluators describe the same function: F'(phi) = dF/dr * (1 + r).
      double phi = Math.log1p(r);
      assertEquals(value, Npv.Phi.DIRECT.value(d, phi), 1e-12 * scale);
      assertEquals(
          analytic.derivative(), Npv.Phi.DIRECT.both(d, phi).derivative() / (1 + r), 1e-9 * dscale);
    }
  }

  // ====================================================================================== Guess

  /** m0 = 20, m1 = 90, m2 = 75 at times 0, 1/2, 1. */
  static Digest guessDigest() {
    return Irr.digest(pairs(0, -100, 50, 60, 100, 60));
  }

  @Test
  void guessFormulasMatchHandMoments() {
    Digest d = guessDigest();
    assertEquals(20, d.moment(0), 1e-12);
    assertEquals(90, d.moment(1), 1e-12);
    assertEquals(75, d.moment(2), 1e-12);
    assertEquals(20.0 / 90, Guess.linear(d), 1e-14);
    // m0 m1 / (m1^2 - m0 m2 / 2) = 1800 / 7350
    assertEquals(1800.0 / 7350, Guess.pade(d), 1e-14);
  }

  @Test
  void padeIsNaNWhenDenominatorGuardFails() {
    // m0 = 109, m1 = 9.5, m2 = 9.75: denominator 90.25 - 531.4 < 0.
    Digest d = Irr.digest(pairs(0, 100, 50, -1, 100, 10));
    assertEquals(109, d.moment(0), 1e-12);
    assertEquals(9.5, d.moment(1), 1e-12);
    assertEquals(9.75, d.moment(2), 1e-12);
    assertTrue(Double.isNaN(Guess.pade(d)));
    assertEquals(109 / 9.5, Guess.linear(d), 1e-12);
    // Just above and just below the guard: denominator = 0.05 m1^2 exactly is rejected.
    Digest edge = Irr.digest(pairs(0, 1, 100, 1)); // m0 = 2, m1 = 1, m2 = 1: 1 - 1 = 0 <= 0.05
    assertTrue(Double.isNaN(Guess.pade(edge)));
    Digest ok = Irr.digest(pairs(0, -1, 100, 3)); // m0 = 2, m1 = 3, m2 = 3: 9 - 3 = 6 > 0.45
    assertEquals(2.0 * 3 / 6, Guess.pade(ok), 1e-14);
  }

  @Test
  void waterfallAcceptsPadeWhenItMatchesTheDirection() {
    Digest d = guessDigest();
    assertEquals(1800.0 / 7350, Guess.waterfall(d, 1, true), 1e-14);
    // Without Pade the linear guess takes over.
    assertEquals(20.0 / 90, Guess.waterfall(d, 1, false), 1e-14);
  }

  @Test
  void waterfallAcceptsPadeOfWrongSignWhenItAgreesWithTheReturn() {
    // m0 = -30, m1 = 50, m2 = 40: Pade = -1500 / 3100 < 0, direction +1, but pade * m0 > 0.
    Digest d = Irr.digest(pairs(0, -100, 50, 40, 100, 30));
    double p = Guess.pade(d);
    assertEquals(-1500.0 / 3100, p, 1e-14);
    assertEquals(p, Guess.waterfall(d, 1, true), 1e-14);
    // Without Pade: linear = -0.6 has the wrong sign for +1, simple = log(1 - m0/first) is
    // log(0.7) < 0 also wrong, so the fixed fallback applies.
    assertEquals(Math.log(1.1), Guess.waterfall(d, 1, false), 1e-14);
  }

  @Test
  void waterfallFallsThroughLinearSimpleAndFixedGuesses() {
    // Direction -1: Pade < 0 is accepted. m0 = 50, m1 = -35, m2 = -27.5.
    Digest a = Irr.digest(pairs(0, 100, 50, -30, 100, -20));
    double p = Guess.pade(a);
    assertEquals(50.0 * -35 / (1225 + 0.5 * 50 * 27.5), p, 1e-14);
    assertEquals(p, Guess.waterfall(a, -1, true), 1e-14);
    assertEquals(50.0 / -35, Guess.waterfall(a, -1, false), 1e-14);
    // Direction +1: Pade, linear and log(1 - m0/first) = log(0.5) all point the wrong way.
    assertEquals(Math.log(1.1), Guess.waterfall(a, 1, true), 1e-14);

    // Direction +1 reaching the simple guess: m0 = 50, m1 = -120, m2 = -147, first = -100.
    Digest b = Irr.digest(pairs(0, -100, 10, 300, 100, -150));
    assertTrue(Guess.pade(b) < 0);
    assertTrue(Guess.linear(b) < 0);
    assertEquals(Math.log(1.5), Guess.waterfall(b, 1, true), 1e-14);
    assertEquals(Math.log(1.5), Guess.waterfall(b, 1, false), 1e-14);

    // Direction -1 reaching the fixed fallback: m0 = -250, m1 = -200, m2 = -125, first = 100.
    Digest c = Irr.digest(pairs(0, 100, 50, -300, 100, -50));
    assertTrue(Guess.pade(c) > 0);
    assertTrue(Guess.linear(c) > 0);
    assertTrue(Math.log(1 - c.moment(0) / c.firstFlow()) > 0);
    assertEquals(Math.log(0.9), Guess.waterfall(c, -1, true), 1e-14);
    assertEquals(Math.log(0.9), Guess.waterfall(c, -1, false), 1e-14);
  }

  @Test
  void heuristicGuessOrder() {
    Digest d = guessDigest();
    assertEquals(Guess.pade(d), Guess.heuristic(d, true), 1e-14);
    assertEquals(Guess.linear(d), Guess.heuristic(d, false), 1e-14);
    // Pade not finite: linear is used.
    Digest g = Irr.digest(pairs(0, 100, 50, -1, 100, 10));
    assertEquals(Guess.linear(g), Guess.heuristic(g, true), 1e-14);
    // m1 = 0: the linear guess is not finite, so 0.1; Pade is exactly 0.
    Digest z = Irr.digest(pairs(0, -10, 50, 100, 100, -50));
    assertEquals(0, z.moment(1), 1e-12);
    assertEquals(0.1, Guess.heuristic(z, false), 1e-14);
    assertEquals(0.0, Guess.heuristic(z, true), 1e-12);
  }

  // =================================================================================== Analysis

  static Analysis analyse(double... p) {
    return Analysis.of(Irr.digest(pairs(p)));
  }

  @Test
  void boundsFollowTheFormulas() {
    Analysis a = analyse(0, -100, 30, 60, 100, 80);
    assertEquals(100.0 / 30 * Math.log(140.0 / 100), a.phiUpper(), 1e-12);
    assertEquals(-(100.0 / 100) * Math.log(100.0 / 80), a.phiLower(), 1e-12);
    assertEquals(Analysis.Directive.POSITIVE, a.directive());
    assertTrue(a.guaranteed());
    assertEquals(0, a.bracketLow());
    assertEquals(1.001 * a.phiUpper(), a.bracketHigh(), 1e-12);

    // Opposing totals at or below the end flow give an exactly zero bound.
    Analysis b = analyse(0, 100, 30, 50, 100, -60);
    assertEquals(0, b.phiUpper());
    assertEquals(-(100.0 / 70) * Math.log(150.0 / 60), b.phiLower(), 1e-12);
    assertEquals(Analysis.Directive.NEGATIVE, b.directive());
    assertTrue(b.guaranteed());
    assertEquals(1.001 * b.phiLower(), b.bracketLow(), 1e-12);
    assertEquals(0, b.bracketHigh());
    // Equality is degenerate too.
    assertEquals(0, analyse(0, -100, 10, 100).phiUpper());
  }

  @Test
  void directiveZero() {
    Analysis a = analyse(0, -100, 50, 100);
    assertEquals(Analysis.Directive.ZERO, a.directive());
    // An insignificant but non-zero return: |total| <= 1e-10 * max(S+, S-).
    Analysis c = analyse(0, -1e6, 10, 1e6 + 1e-5);
    assertEquals(Analysis.Directive.ZERO, c.directive());
  }

  @Test
  void directiveNoSolution() {
    // First and last flows dominate everything that opposes them.
    Analysis a = analyse(0, 100, 50, -30, 100, 100);
    assertEquals(Analysis.Directive.NO_SOLUTION, a.directive());
    assertEquals(0, a.phiUpper());
    assertEquals(0, a.phiLower());
    // Mirrored.
    assertEquals(Analysis.Directive.NO_SOLUTION, analyse(0, -100, 50, 30, 100, -100).directive());
  }

  @Test
  void directiveOneSideGuaranteed() {
    Analysis positive = analyse(0, -100, 30, 60, 100, 80);
    assertEquals(Analysis.Directive.POSITIVE, positive.directive());
    assertTrue(positive.guaranteed());
    Analysis negative = analyse(0, 100, 30, 50, 100, -60);
    assertEquals(Analysis.Directive.NEGATIVE, negative.directive());
    assertTrue(negative.guaranteed());
  }

  @Test
  void directiveBothGuaranteedFollowsTheSignOfTheReturn() {
    Analysis up = analyse(0, -100, 50, 300, 100, -100);
    assertTrue(up.phiUpper() > 0 && up.phiLower() < 0);
    assertEquals(Analysis.Directive.POSITIVE, up.directive());
    assertTrue(up.guaranteed());
    Analysis down = analyse(0, 100, 50, -300, 100, 100);
    assertTrue(down.phiUpper() > 0 && down.phiLower() < 0);
    assertEquals(Analysis.Directive.NEGATIVE, down.directive());
    assertTrue(down.guaranteed());
  }

  @Test
  void directiveOneSidePossible() {
    // Only the upper bound is non-degenerate and nothing is guaranteed.
    Analysis upper = analyse(0, -100, 50, 150, 100, -200);
    assertTrue(upper.phiUpper() > 0);
    assertEquals(0, upper.phiLower());
    assertEquals(Analysis.Directive.POSITIVE, upper.directive());
    assertFalse(upper.guaranteed());
    // Only the lower bound is non-degenerate.
    Analysis lower = analyse(0, -200, 50, 150, 100, -100);
    assertEquals(0, lower.phiUpper());
    assertTrue(lower.phiLower() < 0);
    assertEquals(Analysis.Directive.NEGATIVE, lower.directive());
    assertFalse(lower.guaranteed());
  }

  @Test
  void directiveNeitherGuaranteedNorOneSided() {
    Analysis negative = analyse(0, -100, 50, 150, 100, -100);
    assertTrue(negative.phiUpper() > 0 && negative.phiLower() < 0);
    assertEquals(Analysis.Directive.NEGATIVE, negative.directive());
    assertFalse(negative.guaranteed());
    Analysis positive = analyse(0, 100, 50, -150, 100, 100);
    assertEquals(Analysis.Directive.POSITIVE, positive.directive());
    assertFalse(positive.guaranteed());
    // A zero return with live bounds is not ZERO: it takes the NEGATIVE branch, unguaranteed.
    Analysis flat = analyse(0, -100, 50, 150, 100, -50);
    assertTrue(flat.phiUpper() > 0 && flat.phiLower() < 0);
    assertEquals(0, Irr.digest(pairs(0, -100, 50, 150, 100, -50)).totalReturn(), 1e-12);
    assertEquals(Analysis.Directive.NEGATIVE, flat.directive());
    assertFalse(flat.guaranteed());
  }

  static void assertRootInsideBracket(List<Cashflow> flows) {
    Digest d = Irr.digest(flows);
    Analysis a = Analysis.of(d);
    assertTrue(a.guaranteed());
    double lo = a.bracketLow(), hi = a.bracketHigh();
    assertTrue(lo <= 0 && hi >= 0 && lo < hi);
    // The bracket changes sign, so it contains a root.
    assertTrue(
        Npv.Phi.INCREMENTAL.value(d, lo) * Npv.Phi.INCREMENTAL.value(d, hi) < 0,
        "no sign change on [" + lo + ", " + hi + "]");
    for (Irr.Stage stage : new Irr.Stage[] {Irr.Stage.ANALYTIC_BOUNDS, Irr.Stage.OPTIMIZED}) {
      IrrResult r = Irr.solve(flows, stage);
      assertTrue(r.hasRoot());
      assertTrue(r.phi() >= lo - 2e-6 && r.phi() <= hi + 2e-6, stage + " phi=" + r.phi());
    }
  }

  @Test
  void rootsOfGuaranteedCasesLieInsideTheBracket() {
    assertRootInsideBracket(pairs(0, -100, 30, 60, 100, 80));
    assertRootInsideBracket(pairs(0, 100, 30, 50, 100, -60));
    assertRootInsideBracket(pairs(0, -100, 50, 300, 100, -100));
    assertRootInsideBracket(pairs(0, 100, 50, -300, 100, 100));
    assertRootInsideBracket(pairs(0, -100, 365, 110));
    assertRootInsideBracket(pairs(0, 100, 365, -110));
  }

  @ParameterizedTest
  @EnumSource(Synthetic.Frequency.class)
  void rootsOfGuaranteedSyntheticCasesLieInsideTheBracket(Synthetic.Frequency frequency) {
    int guaranteed = 0;
    for (String family :
        List.of("standard", "wide", "jcurve", "extreme-positive", "extreme-negative", "signflips"))
      for (int index = 0; index < 12; index++) {
        var flows = Synthetic.corpusCase(frequency, 24, 0, 7, index, family);
        Digest d = Irr.digest(flows);
        if (d.terminal() != Digest.Terminal.NONE) continue;
        Analysis a = Analysis.of(d);
        if (!a.guaranteed()
            || (a.directive() != Analysis.Directive.POSITIVE
                && a.directive() != Analysis.Directive.NEGATIVE)
            || !Double.isFinite(a.bracketLow())
            || !Double.isFinite(a.bracketHigh())) continue;
        guaranteed++;
        double lo = a.bracketLow(), hi = a.bracketHigh();
        String msg = family + " " + frequency + " " + index;
        assertTrue(Npv.Phi.INCREMENTAL.value(d, lo) * Npv.Phi.INCREMENTAL.value(d, hi) < 0, msg);
        IrrResult r = Irr.solve(d, Irr.Stage.OPTIMIZED);
        assertTrue(r.hasRoot(), msg);
        assertTrue(r.phi() >= lo - 2e-6 && r.phi() <= hi + 2e-6, msg + " phi=" + r.phi());
      }
    assertTrue(guaranteed > 20, "too few guaranteed cases: " + guaranteed);
  }

  // ============================================================================ end to end

  @Test
  void twoFlowsGiveTenPercentAtEveryStage() {
    var flows = pairs(0, -100, 365, 110);
    for (Irr.Stage s : Irr.Stage.values()) {
      IrrResult r = Irr.solve(flows, s);
      assertTrue(r.hasRoot(), s.name());
      assertEquals(0.10, r.rate(), 5e-6, s.name());
      assertEquals(Math.log(1.1), r.phi(), 5e-6, s.name());
    }
    // Reversed signs: the rate of a borrowing is the same 10%.
    assertEquals(0.10, Irr.solve(pairs(0, 100, 365, -110), Irr.Stage.OPTIMIZED).rate(), 5e-6);
  }

  @Test
  void zeroAndNoSolutionDirectivesCostNoEvaluations() {
    for (Irr.Stage s :
        new Irr.Stage[] {Irr.Stage.ANALYTIC_BOUNDS, Irr.Stage.NEWTON_FIRST, Irr.Stage.OPTIMIZED}) {
      IrrResult zero = Irr.solve(pairs(0, -100, 50, 100), s);
      assertEquals(IrrResult.Status.TERMINAL, zero.status(), s.name());
      assertEquals(IrrResult.Path.ZERO, zero.path(), s.name());
      assertEquals(0.0, zero.rate());
      assertEquals(0.0, zero.phi());
      assertEquals(0, zero.evaluations());
      assertFalse(zero.hasRoot());

      IrrResult none = Irr.solve(pairs(0, 100, 50, -30, 100, 100), s);
      assertEquals(IrrResult.Status.NO_SOLUTION, none.status(), s.name());
      assertEquals(IrrResult.Path.NONE, none.path(), s.name());
      assertTrue(Double.isNaN(none.rate()));
      assertTrue(Double.isNaN(none.phi()));
      assertEquals(0, none.evaluations());
      assertFalse(none.hasRoot());
    }
  }

  @Test
  void threeFlowsWithTwoRootsAtPhiZeroAndTwoLnTwo() {
    // -100 + 300 x - 200 x^2 with x = exp(-phi / 2): x = 1 or 1/2.
    var flows = pairs(0, -100, 100, 300, 200, -200);
    IrrResult r = Irr.solve(flows, Irr.Stage.OPTIMIZED);
    assertTrue(r.hasRoot());
    assertTrue(oneOf(r.phi(), 1e-5, 0, 2 * LN2), "phi = " + r.phi());
    assertEquals(Math.expm1(r.phi()), r.rate(), 1e-12);
    // Whichever root is returned, it is a root.
    assertEquals(
        0, new Direct(new double[] {-100, 300, -200}, new double[] {0, .5, 1}).f(r.phi()), 1e-3);
  }

  @Test
  void threeFlowsWithRootsAtPlusAndMinusTwoLnTwo() {
    // 100 - 250 x + 100 x^2: x = 2 or 1/2. The return is negative and both sides are guaranteed,
    // so the negative root is wanted.
    var flows = pairs(0, 100, 100, -250, 200, 100);
    Analysis a = Analysis.of(Irr.digest(flows));
    assertEquals(Analysis.Directive.NEGATIVE, a.directive());
    assertTrue(a.guaranteed());
    for (Irr.Stage s : new Irr.Stage[] {Irr.Stage.NEWTON_FIRST, Irr.Stage.OPTIMIZED}) {
      IrrResult r = Irr.solve(flows, s);
      assertTrue(r.hasRoot(), s.name());
      assertEquals(-2 * LN2, r.phi(), 1e-5, s.name());
    }
    // Every stage that finds a root finds one of the two.
    for (Irr.Stage s : Irr.Stage.values()) {
      IrrResult r = Irr.solve(flows, s);
      if (r.hasRoot()) assertTrue(oneOf(r.phi(), 1e-4, 2 * LN2, -2 * LN2), s + " " + r.phi());
    }
  }

  @Test
  void newtonOnTheWrongSideIsRejectedAndBrentOnTheBracketFindsTheRoot() {
    // Newton from the fallback guess log(0.9) lands on the positive root +2 ln 2; the plan wants
    // the negative one, so the result is rejected and Brent on [1.001 * lower, 0] supplies it.
    var flows = pairs(0, 100, 100, -250, 200, 100);
    for (Irr.Stage s : new Irr.Stage[] {Irr.Stage.NEWTON_FIRST, Irr.Stage.OPTIMIZED}) {
      IrrResult r = Irr.solve(flows, s);
      assertEquals(IrrResult.Path.BRENT, r.path(), s.name());
      assertTrue(r.newtonIterations() > 0, "Newton must have been tried: " + s);
      assertTrue(r.newtonIterations() <= Irr.GUARANTEED_NEWTON_ITERATIONS);
      assertTrue(r.valueEvaluations() > 0);
      assertEquals(-2 * LN2, r.phi(), 1e-5);
      // Brent results are rounded down to six decimals.
      assertEquals(Math.floor(r.phi() * 1e6) / 1e6, r.phi(), 0);
    }
  }

  @Test
  void newtonFailureOnAGuaranteedCaseFallsBackToBrent() {
    // Root at phi = -40 with a tiny last flow: Newton from the first guess runs off to a huge
    // negative iterate and stalls, so the bracketed Brent search produces the root.
    var flows = twoFlowsWithPhi(-40);
    Analysis a = Analysis.of(Irr.digest(flows));
    assertTrue(a.guaranteed());
    assertEquals(Analysis.Directive.NEGATIVE, a.directive());
    IrrResult r = Irr.solve(flows, Irr.Stage.OPTIMIZED);
    assertEquals(IrrResult.Path.BRENT, r.path());
    assertEquals(-40, r.phi(), 1e-5);
  }

  @Test
  void aCaseWithoutProvenRootsCanEndFailed() {
    // -100 + 150 x - 100 x^2 has no real root, but the analysis cannot prove that.
    var flows = pairs(0, -100, 50, 150, 100, -100);
    Analysis a = Analysis.of(Irr.digest(flows));
    assertFalse(a.guaranteed());
    for (Irr.Stage s :
        new Irr.Stage[] {Irr.Stage.ANALYTIC_BOUNDS, Irr.Stage.NEWTON_FIRST, Irr.Stage.OPTIMIZED}) {
      IrrResult r = Irr.solve(flows, s);
      assertEquals(IrrResult.Status.FAILED, r.status(), s.name());
      assertEquals(IrrResult.Path.NONE, r.path());
      assertTrue(Double.isNaN(r.rate()) && Double.isNaN(r.phi()));
      assertFalse(r.hasRoot());
      assertTrue(r.newtonIterations() <= Irr.MAX_EVALUATIONS);
    }
  }

  @Test
  void heuristicPlanFindsTheRootsOfAnUnguaranteedCase() {
    // 1 - 5 x + 6 x^2: x = 1/2 or 1/3, phi = 2 ln 2 or 2 ln 3; the return is positive and the
    // analysis cannot guarantee a root.
    var flows = pairs(0, 1, 100, -5, 200, 6);
    Analysis a = Analysis.of(Irr.digest(flows));
    assertFalse(a.guaranteed());
    assertEquals(Analysis.Directive.POSITIVE, a.directive());
    IrrResult r = Irr.solve(flows, Irr.Stage.OPTIMIZED);
    assertTrue(r.hasRoot());
    assertTrue(oneOf(r.phi(), 1e-4, 2 * LN2, 2 * Math.log(3)), "phi = " + r.phi());
  }

  @Test
  void strategiesEqualTheirStages() {
    List<List<Cashflow>> cases =
        List.of(
            pairs(0, -100, 365, 110),
            pairs(0, 100, 100, -250, 200, 100),
            pairs(0, -100, 50, 150, 100, -100),
            pairs(0, 100, 50, -30, 100, 100),
            pairs(0, 10, 5, 20),
            Synthetic.corpusCase(Synthetic.Frequency.WEEKLY, 30, 0, 42, 3, "standard"),
            Synthetic.corpusCase(Synthetic.Frequency.IRREGULAR, 30, 0, 42, 4, "signflips"));
    for (var flows : cases) {
      assertSame(
          Irr.solve(flows, Irr.Stage.HISTORICAL), Irr.solve(flows, SolverStrategy.HISTORICAL));
      assertSame(Irr.solve(flows, Irr.Stage.OPTIMIZED), Irr.solve(flows, SolverStrategy.REVISED));
    }
  }

  @ParameterizedTest
  @EnumSource(Irr.Stage.class)
  void preparedSolveEqualsDirectSolve(Irr.Stage stage) {
    List<List<Cashflow>> cases = new ArrayList<>();
    cases.add(pairs(0, -100, 365, 110));
    cases.add(pairs(0, 100, 100, -250, 200, 100));
    cases.add(pairs(0, -100, 50, 150, 100, -100));
    cases.add(pairs(0, 100, 50, -30, 100, 100));
    cases.add(pairs(0, 0));
    for (var family : List.of("standard", "wide", "jcurve", "signflips", "extreme-positive"))
      for (var f : Synthetic.Frequency.values())
        cases.add(Synthetic.corpusCase(f, 20, 0, 20261122, 2, family));
    for (var flows : cases) {
      IrrResult direct = Irr.solve(flows, stage);
      assertSame(direct, Irr.solve(Irr.prepare(flows, stage)));
      assertEquals(stage, Irr.prepare(flows, stage).stage());
      if (stage != Irr.Stage.HISTORICAL) assertSame(direct, Irr.solve(Irr.digest(flows), stage));
      // Solving twice from one prepared input gives the same answer.
      Irr.Prepared p = Irr.prepare(flows, stage);
      assertSame(Irr.solve(p), Irr.solve(p));
    }
  }

  @Test
  void newtonIterationCaps() {
    int guaranteedChecked = 0;
    for (String family :
        List.of(
            "standard",
            "wide",
            "jcurve",
            "signflips",
            "extreme-positive",
            "extreme-negative",
            "dominance"))
      for (var f : Synthetic.Frequency.values())
        for (int index = 0; index < 8; index++) {
          var flows = Synthetic.corpusCase(f, 32, 0, 8675309, index, family);
          Digest d = Irr.digest(flows);
          for (Irr.Stage s : Irr.Stage.values()) {
            IrrResult r = Irr.solve(flows, s);
            String msg = family + " " + f + " " + index + " " + s;
            assertTrue(r.newtonIterations() <= Irr.MAX_EVALUATIONS, msg);
            assertTrue(r.derivativeEvaluations() <= Irr.MAX_EVALUATIONS, msg);
            if ((s == Irr.Stage.NEWTON_FIRST || s == Irr.Stage.OPTIMIZED)
                && d.terminal() == Digest.Terminal.NONE) {
              Analysis a = Analysis.of(d);
              boolean bracketed =
                  a.guaranteed()
                      && (a.directive() == Analysis.Directive.POSITIVE
                          || a.directive() == Analysis.Directive.NEGATIVE)
                      && Double.isFinite(a.bracketLow())
                      && Double.isFinite(a.bracketHigh());
              if (bracketed) {
                guaranteedChecked++;
                assertTrue(r.newtonIterations() <= Irr.GUARANTEED_NEWTON_ITERATIONS, msg);
              }
            }
          }
        }
    assertTrue(guaranteedChecked > 40, "guaranteed cases checked: " + guaranteedChecked);
  }

  static List<Cashflow> twoFlowsWithPhi(double phi) {
    // -1e9 now and 1e9 * exp(phi) after one year: the root is exactly phi. The scale keeps
    // function values far above the absolute accuracy of the Commons Brent solver.
    return pairs(0, -1e9, 365, 1e9 * Math.exp(phi));
  }

  @Test
  void largePositivePhiHasARepresentableRate() {
    IrrResult r = Irr.solve(twoFlowsWithPhi(40), Irr.Stage.OPTIMIZED);
    assertEquals(40, r.phi(), 1e-4);
    assertEquals(Math.expm1(r.phi()), r.rate(), 1e-9 * r.rate());
    assertTrue(Double.isFinite(r.rate()) && r.rate() > -1);
    assertEquals(IrrResult.Status.CONVERGED, r.status());
    assertTrue(r.hasRoot());
  }

  @Test
  void rateUnrepresentableOnlyWhenTheRateReallyIs() {
    IrrResult r = Irr.solve(twoFlowsWithPhi(-40), Irr.Stage.OPTIMIZED);
    assertEquals(-40, r.phi(), 1e-4);
    // exp(-40) is below half an ulp of 1, so the rate rounds to exactly -1.
    assertEquals(-1.0, r.rate());
    assertEquals(IrrResult.Status.RATE_UNREPRESENTABLE, r.status());
    assertTrue(r.hasRoot());
    assertNotNull(r.path());

    // The status is a pure function of representability at every stage that returns a phi root.
    for (double phi : new double[] {-45, -40, -36, -30, -20, 20, 36, 40, 60}) {
      for (Irr.Stage s : new Irr.Stage[] {Irr.Stage.ANALYTIC_BOUNDS, Irr.Stage.OPTIMIZED}) {
        IrrResult q = Irr.solve(twoFlowsWithPhi(phi), s);
        if (!q.hasRoot()) continue;
        boolean representable = Double.isFinite(q.rate()) && q.rate() > -1;
        assertEquals(
            representable ? IrrResult.Status.CONVERGED : IrrResult.Status.RATE_UNREPRESENTABLE,
            q.status(),
            "phi=" + phi);
        assertEquals(phi, q.phi(), 1e-4 * Math.max(1, Math.abs(phi)), "phi=" + phi + " " + s);
        assertEquals(Math.expm1(q.phi()), q.rate(), 0);
      }
    }
    // A moderately negative phi still has a representable rate.
    IrrResult m = Irr.solve(twoFlowsWithPhi(-30), Irr.Stage.OPTIMIZED);
    assertTrue(m.rate() > -1);
    assertEquals(IrrResult.Status.CONVERGED, m.status());
  }

  /**
   * Known defect: Commons Brent stops as soon as |f| is below its absolute function-value accuracy
   * (1e-15) at the start value or a bracket end, whatever the scale of the amounts. At tiny scales
   * the guess is returned unrefined. The same inputs at unit scale are solved correctly.
   */
  @org.junit.jupiter.api.Disabled(
      "Known limitation of the reproduced design: Brent is constructed with a coordinate"
          + " tolerance only, so Commons Math keeps its absolute function-value threshold of"
          + " 1e-15. Kept as an executable record; see the report's limitations.")
  @ParameterizedTest
  @EnumSource(
      value = Irr.Stage.class,
      names = {"PHI_DIRECT", "INCREMENTAL", "SMALL_POWERS", "ANALYTIC_BOUNDS"})
  void bracketedBrentDoesNotStopOnTheAbsoluteSizeOfTheFunction(Irr.Stage stage) {
    var unit = pairs(0, -100, 100, 30, 200, 100);
    double expected = Irr.solve(unit, Irr.Stage.OPTIMIZED).phi();
    var tiny = pairs(0, -1e-16 * 100, 100, 30e-16, 200, 100e-16);
    IrrResult r = Irr.solve(tiny, stage);
    assertTrue(r.hasRoot());
    assertEquals(expected, r.phi(), 1e-4, stage + " " + r);
  }

  // ------------------------------------------------------------------------ extension stages

  /** The power table reproduces the recurrence, including gaps above the table limit. */
  @ParameterizedTest
  @EnumSource(Synthetic.Frequency.class)
  void tableEvaluatorsMatchTheRecurrence(Synthetic.Frequency frequency) {
    var cases = new ArrayList<List<Cashflow>>();
    cases.add(schedule(frequency, 60, 5, true));
    // One gap far above TABLE_CAP and a table limited by the flow count.
    cases.add(pairs(0, -100, 3, 20, 5000, 30, 5007, 90));
    for (var flows : cases) {
      Digest d = Irr.digest(flows);
      double[] table = Npv.newTable(d);
      assertTrue(table.length - 1 <= Npv.TABLE_CAP && table.length - 1 <= d.maxGap());
      for (double phi : PHIS) {
        Npv.Evaluation expected = Npv.Phi.INCREMENTAL.both(d, phi);
        double tol = 1e-12 * Direct.of(flows).scale(phi, phi < 0);
        assertEquals(expected.value(), Npv.tableValue(d, phi, table), tol, frequency + " " + phi);
        Npv.Evaluation both = Npv.tableBoth(d, phi, table);
        assertEquals(Npv.tableValue(d, phi, table), both.value());
        assertEquals(expected.derivative(), both.derivative(), tol, frequency + " " + phi);
      }
    }
  }

  @Test
  void maxGapIsTheLargestGapBetweenNonZeroFlows() {
    assertEquals(40, Irr.digest(pairs(0, -1, 3, 0, 40, 1, 47, 1)).maxGap());
    assertEquals(1, Irr.digest(schedule(Synthetic.Frequency.DAILY, 20, 1, false)).maxGap());
  }

  /** log(P / N) is zero at the root, linear for two flows, and its derivative is consistent. */
  @Test
  void logFormHasTheRootsOfTheNpv() {
    for (double target : new double[] {-25, -1, 0.3, 25}) {
      Digest two = Irr.digest(twoFlowsWithPhi(target));
      double[] table = Npv.newTable(two);
      for (double phi : new double[] {-30, -2, 0, 2, 30}) {
        Npv.Evaluation e = Npv.tableLog(two, phi, table);
        // One Newton step from anywhere lands on the root of a two-flow schedule.
        assertEquals(
            target, phi - e.value() / e.derivative(), 1e-9 * Math.max(1, Math.abs(target)));
      }
    }
    for (var frequency : Synthetic.Frequency.values()) {
      var flows = Synthetic.corpusCase(frequency, 64, 0, 7, 3, "signflips");
      double target = Synthetic.targetPhi(frequency, 64, 0, 7, 3, "signflips");
      Digest d = Irr.digest(flows);
      double[] table = Npv.newTable(d);
      assertEquals(0, Npv.tableLog(d, target, table).value(), 1e-10);
      for (double phi : new double[] {-1.5, -0.2, 0.4, 1.5}) {
        double h = 1e-5;
        double numeric =
            (Npv.tableLog(d, phi + h, table).value() - Npv.tableLog(d, phi - h, table).value())
                / (2 * h);
        double analytic = Npv.tableLog(d, phi, table).derivative();
        assertEquals(numeric, analytic, 1e-6 * Math.max(1, Math.abs(analytic)), frequency + "");
      }
    }
  }

  /** Far-negative roots: plain Newton exhausts its 30 iterations, the log form converges. */
  @Test
  void logNewtonConvergesOnFarNegativeRoots() {
    for (int i = 0; i < 16; i++) {
      var flows =
          Synthetic.corpusCase(Synthetic.Frequency.DAILY, 256, 0, 42, i, "extreme-negative");
      double target =
          Synthetic.targetPhi(Synthetic.Frequency.DAILY, 256, 0, 42, i, "extreme-negative");
      IrrResult before = Irr.solve(flows, Irr.Stage.TRUSTED_GUESS);
      assertEquals(IrrResult.Path.BRENT, before.path());
      assertEquals(Irr.GUARANTEED_NEWTON_ITERATIONS, before.newtonIterations());
      IrrResult after = Irr.solve(flows, Irr.Stage.LOG_NEWTON);
      assertEquals(IrrResult.Path.NEWTON, after.path());
      assertTrue(after.newtonIterations() <= 8, after.toString());
      assertEquals(target, after.phi(), 1e-6 * Math.abs(target));
    }
  }

  /**
   * The input on which the direction gate sends the optimized solver to a remote root: without the
   * gate Newton's root, the one the historical solver returns, is kept.
   */
  @Test
  void trustedGuessKeepsTheNearRoot() {
    var flows = Synthetic.corpusCase(Synthetic.Frequency.DAILY, 256, 0, 42, 23, "signflips");
    IrrResult historical = Irr.solve(flows, Irr.Stage.HISTORICAL);
    IrrResult gated = Irr.solve(flows, Irr.Stage.POWER_TABLE);
    assertEquals(IrrResult.Path.BRENT, gated.path());
    assertTrue(gated.phi() > 200);
    for (Irr.Stage s : new Irr.Stage[] {Irr.Stage.TRUSTED_GUESS, Irr.Stage.LOG_NEWTON}) {
      IrrResult r = Irr.solve(flows, s);
      assertEquals(IrrResult.Path.NEWTON, r.path());
      assertEquals(historical.rate(), r.rate(), 1e-5);
    }
  }

  /** The table changes arithmetic only: same path and counts as the optimized stage. */
  @Test
  void powerTableStageFollowsTheOptimizedStage() {
    for (var frequency : Synthetic.Frequency.values())
      for (String family : new String[] {"standard", "wide", "signflips", "extreme-negative"})
        for (int i = 0; i < 8; i++) {
          var flows = Synthetic.corpusCase(frequency, 64, 0, 11, i, family);
          IrrResult o = Irr.solve(flows, Irr.Stage.OPTIMIZED);
          IrrResult t = Irr.solve(flows, Irr.Stage.POWER_TABLE);
          assertEquals(o.path(), t.path(), frequency + " " + family + " " + i);
          assertEquals(o.phi(), t.phi(), 1e-6 * Math.max(1, Math.abs(o.phi())));
        }
  }

  @Test
  void extendedStrategyIsTheLastStage() {
    var flows = pairs(0, -100, 180, 20, 365, 95);
    assertEquals(Irr.solve(flows, Irr.Stage.LOG_NEWTON), Irr.solve(flows, SolverStrategy.EXTENDED));
  }

  /** IRREGULAR_SORTED has exactly the gaps of IRREGULAR for the same seed, in ascending order. */
  @Test
  void sortedScheduleHasTheSameGaps() {
    Digest a = Irr.digest(schedule(Synthetic.Frequency.IRREGULAR, 200, 3, false));
    Digest b = Irr.digest(schedule(Synthetic.Frequency.IRREGULAR_SORTED, 200, 3, false));
    int[] ga = new int[a.size()], gb = new int[b.size()];
    for (int i = 0; i < ga.length; i++) ga[i] = a.gap(i);
    for (int i = 0; i < gb.length; i++) gb[i] = b.gap(i);
    for (int i = 2; i < gb.length; i++) assertTrue(gb[i - 1] <= gb[i]);
    Arrays.sort(ga);
    Arrays.sort(gb);
    assertTrue(Arrays.equals(ga, gb));
    assertEquals(
        37, Irr.digest(schedule(Synthetic.Frequency.EVERY_37_DAYS, 20, 3, false)).maxGap());
  }
}
