package org.example.irr.historical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.example.irr.Cashflow;
import org.example.irr.historical.HistoricalIrr.Outcome;
import org.example.irr.historical.HistoricalIrr.Path;
import org.junit.jupiter.api.Test;

class HistoricalIrrTest {
  private static final LocalDate ANCHOR = LocalDate.of(2020, 1, 1);

  private static Cashflow cf(int day, double amount) {
    return new Cashflow(ANCHOR.plusDays(day), amount);
  }

  private static List<Cashflow> flows(Cashflow... flows) {
    return new ArrayList<>(Arrays.asList(flows));
  }

  private static void assertRelative(double expected, double actual, double tolerance) {
    assertTrue(
        Math.abs(expected - actual) <= tolerance * Math.max(Math.abs(expected), 1e-300),
        "expected " + expected + " but was " + actual);
  }

  private static List<Cashflow> sample() {
    return flows(cf(0, -100), cf(100, 30), cf(250, 40), cf(365, 60));
  }

  @Test
  void emptyListIsZeroRate() {
    Outcome o = HistoricalIrr.solve(flows());
    assertEquals(Path.TERMINAL, o.path());
    assertEquals(0.0, o.rate());
    assertEquals(0, o.valueEvaluations());
    assertEquals(0, o.derivativeEvaluations());
    assertTrue(o.hasRate());
  }

  @Test
  void onlyZeroAmountsIsZeroRate() {
    Outcome o = HistoricalIrr.solve(flows(cf(0, 0), cf(5, 0.0)));
    assertEquals(Path.TERMINAL, o.path());
    assertEquals(0.0, o.rate());
  }

  @Test
  void noNegativeAmountsIsPositiveInfinity() {
    Outcome o = HistoricalIrr.solve(flows(cf(0, 0), cf(1, 10), cf(30, 5)));
    assertEquals(Path.TERMINAL, o.path());
    assertEquals(Double.POSITIVE_INFINITY, o.rate());
  }

  @Test
  void noPositiveAmountsIsMinusOne() {
    Outcome o = HistoricalIrr.solve(flows(cf(0, -10), cf(30, -5)));
    assertEquals(Path.TERMINAL, o.path());
    assertEquals(-1.0, o.rate());
    assertEquals(0, o.valueEvaluations());
    assertEquals(0, o.derivativeEvaluations());
  }

  @Test
  void singleDayIsSimpleReturn() {
    Outcome o = HistoricalIrr.solve(flows(cf(7, -100), cf(7, 30), cf(7, 90)));
    assertEquals(Path.TERMINAL, o.path());
    assertEquals(120.0 / 100.0 - 1, o.rate(), 1e-15);
  }

  @Test
  void terminalProblemCarriesNoNpv() {
    HistoricalIrr.Problem p = HistoricalIrr.prepare(flows());
    assertTrue(p.terminal() != null);
    assertNull(p.npv());
  }

  @Test
  void conventionalCaseIsSolvedByBrent() {
    Outcome o = HistoricalIrr.solve(flows(cf(0, -100), cf(365, 110)));
    assertEquals(Path.BRENT, o.path());
    assertEquals(0.10, o.rate(), 1e-5);
    assertEquals(0, o.derivativeEvaluations());
    assertTrue(o.valueEvaluations() > 0);
  }

  @Test
  void brentResultIsRoundedDownToSixDecimals() {
    Outcome o = HistoricalIrr.solve(flows(cf(0, -100), cf(365, 110.5)));
    assertEquals(Path.BRENT, o.path());
    assertEquals(0.105, o.rate(), 1e-5);
    assertEquals(Math.floor(o.rate() * 1e6) / 1e6, o.rate());
  }

  @Test
  void rootAboveBracketFallsThroughToNewton() {
    Outcome o = HistoricalIrr.solve(flows(cf(0, -1), cf(365, 10001)));
    assertEquals(Path.NEWTON, o.path());
    assertEquals(10000.0, o.rate(), 1e-3);
    assertTrue(o.derivativeEvaluations() > 0);
  }

  @Test
  void failingSearchesWithOpposingLastFlowGiveMinusOne() {
    HistoricalIrr.Problem p = HistoricalIrr.prepare(flows(cf(0, 100), cf(182, 1000), cf(365, -1)));
    assertTrue(p.negativeRootExists());
    Outcome o = HistoricalIrr.solve(p);
    assertEquals(Path.MINUS_ONE, o.path());
    assertEquals(-1.0, o.rate());
    assertTrue(o.derivativeEvaluations() > 0);
  }

  @Test
  void spaceSearchIsReachedWhenOtherStagesFail() {
    HistoricalIrr.Problem p =
        HistoricalIrr.prepare(flows(cf(0, 0.08), cf(10, -20), cf(72, 60), cf(204, -7000)));
    assertTrue(p.trySolveForX());
    assertFalse(p.negativeRootExists());
    Outcome o = HistoricalIrr.solve(p);
    assertEquals(Path.X_BRENT, o.path());
    assertTrue(o.hasRate());
    assertTrue(o.rate() > 0);
    assertTrue(o.valueEvaluations() > 0);
    assertTrue(o.derivativeEvaluations() > 0);
  }

  @Test
  void unsolvableCaseHasNoRate() {
    HistoricalIrr.Problem p = HistoricalIrr.prepare(flows(cf(0, -100), cf(182, 10), cf(365, -100)));
    assertFalse(p.negativeRootExists());
    assertFalse(p.trySolveForX());
    Outcome o = HistoricalIrr.solve(p);
    assertEquals(Path.NONE, o.path());
    assertTrue(Double.isNaN(o.rate()));
    assertFalse(o.hasRate());
  }

  @Test
  void npvValueMatchesHandComputedSum() {
    HistoricalNpv npv = HistoricalIrr.prepare(sample()).npv();
    assertEquals(4, npv.size());
    double r = 0.07;
    double expected = 0;
    expected += -100.0 * Math.pow(1 + r, -(0 / 365.0));
    expected += 30.0 * Math.pow(1 + r, -(100 / 365.0));
    expected += 40.0 * Math.pow(1 + r, -(250 / 365.0));
    expected += 60.0 * Math.pow(1 + r, -(365 / 365.0));
    assertEquals(expected, npv.value(r));
  }

  @Test
  void derivativeStructureAgreesWithAnalyticDerivative() {
    HistoricalNpv npv = HistoricalIrr.prepare(sample()).npv();
    double[] t = {0, 100 / 365.0, 250 / 365.0, 1};
    double[] c = {-100, 30, 40, 60};
    for (double r : new double[] {-0.5, 0.0, 0.07, 1.5}) {
      DerivativeStructure ds = npv.value(new DerivativeStructure(1, 1, 0, r));
      double value = 0;
      double derivative = 0;
      for (int i = 0; i < t.length; i++) {
        value += c[i] * Math.pow(1 + r, -t[i]);
        derivative -= t[i] * c[i] * Math.pow(1 + r, -t[i] - 1);
      }
      assertRelative(value, ds.getValue(), 1e-10);
      assertRelative(derivative, ds.getPartialDerivative(1), 1e-10);
    }
  }

  @Test
  void xFunctionMatchesRateFunction() {
    HistoricalNpv npv = HistoricalIrr.prepare(sample()).npv();
    for (double x : new double[] {0.2, 0.5, 0.9, 1.0}) {
      assertRelative(npv.value(1 / x - 1), npv.asXFunction().value(x), 1e-12);
    }
  }

  @Test
  void countersCountAndReset() {
    HistoricalNpv npv = HistoricalIrr.prepare(sample()).npv();
    npv.value(0.1);
    npv.asXFunction().value(0.5);
    npv.value(new DerivativeStructure(1, 1, 0, 0.1));
    assertEquals(2, npv.valueCalls());
    assertEquals(1, npv.derivativeCalls());
    npv.resetCounters();
    assertEquals(0, npv.valueCalls());
    assertEquals(0, npv.derivativeCalls());
  }

  @Test
  void invalidInputIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> HistoricalIrr.prepare(null));
    List<Cashflow> withNull = flows(cf(0, -1));
    withNull.add(null);
    assertThrows(IllegalArgumentException.class, () -> HistoricalIrr.prepare(withNull));
    assertThrows(
        IllegalArgumentException.class,
        () -> HistoricalIrr.prepare(flows(new Cashflow(null, 1), cf(1, 2))));
    assertThrows(
        IllegalArgumentException.class,
        () -> HistoricalIrr.prepare(flows(cf(0, Double.NaN), cf(1, 2))));
    assertThrows(
        IllegalArgumentException.class,
        () -> HistoricalIrr.prepare(flows(cf(0, Double.POSITIVE_INFINITY))));
    assertThrows(
        IllegalArgumentException.class, () -> HistoricalIrr.prepare(flows(cf(5, -1), cf(4, 2))));
    assertThrows(
        IllegalArgumentException.class, () -> HistoricalIrr.prepare(flows(cf(5, 0), cf(4, 0))));
  }

  @Test
  void callersListIsUnchanged() {
    List<Cashflow> input = flows(cf(0, 0), cf(0, -100), cf(100, 0), cf(365, 110));
    List<Cashflow> copy = new ArrayList<>(input);
    HistoricalIrr.solve(input);
    assertEquals(copy, input);
  }

  @Test
  void solveOfPreparedEqualsSolveOfFlows() {
    List<Cashflow> input = sample();
    assertEquals(HistoricalIrr.solve(input), HistoricalIrr.solve(HistoricalIrr.prepare(input)));
  }
}
