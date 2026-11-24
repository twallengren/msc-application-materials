package org.example.irr.bench;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.example.irr.*;
import org.junit.jupiter.api.Test;

class LibraryTest {
  static final LocalDate DAY0 = LocalDate.of(2024, 1, 1);

  static Corpus corpus(Synthetic.Frequency frequency, String family) {
    var c = new Corpus();
    c.frequency = frequency;
    c.configuration = "n256";
    c.seed = 42;
    c.generate(family);
    return c;
  }

  @Test
  void xirrAnnualRateConvertsToPhi() {
    // One year of 365 days: R is the period rate.
    assertEquals(Math.log(1.21), Libraries.phiFromXirr(0.21, 365), 1e-15);
    // Two years: (1 + R)^2 = 1 + r.
    assertEquals(Math.log(1.21), Libraries.phiFromXirr(0.1, 730), 1e-15);
    assertEquals(0, Libraries.phiFromXirr(0, 123), 0);
    assertTrue(Double.isNaN(Libraries.phiFromXirr(-1.5, 365)));
  }

  @Test
  void poiPerDayRateConvertsToPhi() {
    assertEquals(Math.log(1.21), Libraries.phiFromPoi(0.1, 2), 1e-15);
    assertEquals(0, Libraries.phiFromPoi(0, 40), 0);
    assertEquals(-Double.MIN_VALUE, Libraries.phiFromPoi(-Double.MIN_VALUE, 1), 0);
  }

  /** Both conversions reproduce our phi on a hand-built case whose rate is known. */
  @Test
  void conversionsMatchOurRootOnKnownCases() {
    // -100 now, 121 after 730 days: r = 0.21 over the period.
    var flows = List.of(new Cashflow(DAY0, -100), new Cashflow(DAY0.plusDays(730), 121));
    var in = Libraries.input(flows);
    double phi = Irr.solve(flows, SolverStrategy.EXTENDED).phi();
    assertEquals(Math.log(1.21), phi, 1e-12);
    assertEquals(730, in.days());
    assertEquals(phi, Libraries.phiFromXirr(Libraries.xirr(in.transactions()), in.days()), 1e-6);
    // Two days apart, one empty day in between: POI sees [-100, 0, 121] and a per-day rate of 10%.
    var daily = List.of(new Cashflow(DAY0, -100), new Cashflow(DAY0.plusDays(2), 121));
    var d = Libraries.input(daily);
    assertArrayEquals(new double[] {-100, 0, 121}, d.daily(), 0);
    assertEquals(
        Math.log(1.21), Libraries.phiFromPoi(Libraries.poi(d.daily(), 0.1), d.days()), 1e-6);
  }

  /**
   * The DAILY corpus has one flow on every consecutive day, so POI sees the amounts as they are.
   */
  @Test
  void dailyCorpusHasOneFlowPerConsecutiveDay() {
    for (String family : new String[] {"standard", "wide"})
      for (var flows : corpus(Synthetic.Frequency.DAILY, family).dated) {
        var in = Libraries.input(flows);
        assertEquals(flows.size(), in.daily().length);
        assertEquals(in.days() + 1, in.daily().length);
        for (int i = 0; i < flows.size(); i++)
          assertEquals(flows.get(i).amount(), in.daily()[i], 0);
      }
  }

  @Test
  void dailyValuesPlaceIrregularFlowsByDayOffset() {
    var flows =
        List.of(
            new Cashflow(DAY0, 0),
            new Cashflow(DAY0.plusDays(1), -5),
            new Cashflow(DAY0.plusDays(4), 2),
            new Cashflow(DAY0.plusDays(4), 4),
            new Cashflow(DAY0.plusDays(6), 0));
    assertArrayEquals(new double[] {-5, 0, 0, 6}, Libraries.dailyValues(flows), 0);
  }

  /**
   * On inputs scaled so that XIRR's absolute stopping rule |NPV| &lt; 1e-7 is a relative one, every
   * input converges and its root converted to phi matches our extended root.
   */
  @Test
  void xirrRootMatchesExtendedOnNormalizedStandardInputs() {
    int compared = 0;
    for (var frequency :
        new Synthetic.Frequency[] {Synthetic.Frequency.DAILY, Synthetic.Frequency.IRREGULAR})
      for (var raw : corpus(frequency, "standard").dated) {
        var flows = Libraries.normalized(raw);
        var in = Libraries.input(flows);
        double expected = Irr.solve(flows, SolverStrategy.EXTENDED).phi();
        double rate = Libraries.xirr(in.transactions());
        assertEquals(expected, Libraries.phiFromXirr(rate, in.days()), 1e-6, frequency.toString());
        compared++;
      }
    assertEquals(128, compared);
  }

  /**
   * On the raw corpus XIRR stops when |NPV| &lt; 1e-7 in absolute terms, whatever the amounts'
   * scale (10^-6 to 10^6), so its phi can differ from ours by more than 1e-6, and some inputs never
   * reach the tolerance: two irregular inputs exhaust the 10,000 iterations. Every returned root
   * passes the independent residual check.
   */
  @Test
  void xirrOnRawStandardInputsHasTwoNonconvergingIrregularInputs() {
    int exceptions = 0;
    for (var frequency :
        new Synthetic.Frequency[] {Synthetic.Frequency.DAILY, Synthetic.Frequency.IRREGULAR})
      for (var flows : corpus(frequency, "standard").dated) {
        var in = Libraries.input(flows);
        double expected = Irr.solve(flows, SolverStrategy.EXTENDED).phi();
        var o = Libraries.outcome(Libraries.Solver.XIRR, in);
        if (o.status() == Libraries.Status.EXCEPTION) {
          assertEquals("NonconvergenceException", o.detail());
          assertEquals(10_000, o.iterations());
          exceptions++;
          continue;
        }
        assertEquals(Libraries.Status.ROOT, o.status());
        assertEquals(expected, o.phi(), 1e-3);
        assertTrue(LadderDiagnostics.residual(Irr.digest(flows), o.phi()) < 1e-4);
      }
    assertEquals(2, exceptions);
  }

  /** The counted reproduction returns exactly the library's rate, or throws the same exception. */
  @Test
  void countedXirrRunReproducesTheLibrary() {
    int converged = 0, threw = 0;
    for (var amounts : Libraries.Amounts.values())
      for (var frequency :
          new Synthetic.Frequency[] {Synthetic.Frequency.DAILY, Synthetic.Frequency.IRREGULAR})
        for (String family : new String[] {"standard", "wide"})
          for (var raw : corpus(frequency, family).dated) {
            var flows = amounts == Libraries.Amounts.NORMALIZED ? Libraries.normalized(raw) : raw;
            var transactions = Libraries.input(flows).transactions();
            var counted = Libraries.xirrCounted(transactions);
            try {
              double rate = new org.decampo.xirr.Xirr(transactions).xirr();
              assertEquals(rate, counted.rate(), 0);
              assertEquals("", counted.exception());
              assertTrue(counted.iterations() >= 1 && counted.iterations() < 10_000);
              converged++;
            } catch (RuntimeException e) {
              assertEquals(e.getClass().getSimpleName(), counted.exception());
              threw++;
            }
          }
    assertTrue(converged > 0 && threw > 0, converged + " " + threw);
  }

  @Test
  void poiRootMatchesExtendedOnDailyInputs() {
    for (String family : new String[] {"standard", "wide"})
      for (var flows : corpus(Synthetic.Frequency.DAILY, family).dated) {
        var in = Libraries.input(flows);
        double expected = Irr.solve(flows, SolverStrategy.EXTENDED).phi();
        for (var solver :
            new Libraries.Solver[] {
              Libraries.Solver.POI_IRR, Libraries.Solver.POI_IRR_ZERO_GUESS
            }) {
          var o = Libraries.outcome(solver, in);
          if (o.status() == Libraries.Status.ROOT)
            assertEquals(expected, o.phi(), 1e-6, solver.toString());
        }
      }
  }

  @Test
  void poiIsRefusedOffTheDailySchedule() {
    var inputs = new LibraryBench.Inputs();
    inputs.frequency = Synthetic.Frequency.IRREGULAR;
    inputs.configuration = "n16";
    inputs.seed = 42;
    inputs.amounts = Libraries.Amounts.RAW;
    inputs.family = "standard";
    inputs.solver = Libraries.Solver.POI_IRR;
    var e = assertThrows(IllegalStateException.class, inputs::setup);
    assertTrue(e.getMessage().contains("DAILY"));
  }
}
