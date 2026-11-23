package org.example.irr.bench;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.example.irr.*;
import org.example.irr.historical.HistoricalIrr;
import org.junit.jupiter.api.Test;

class StudyTest {
  static Corpus corpus(String family) {
    var c = new Corpus();
    c.frequency = Synthetic.Frequency.DAILY;
    c.configuration = "n256";
    c.seed = 42;
    c.generate(family);
    return c;
  }

  /** Every kernel call timed by KernelBench computes the same NPV at the same point. */
  @Test
  void kernelCallsAgree() {
    for (var frequency : Synthetic.Frequency.values()) {
      var flows = Synthetic.corpusCase(frequency, 256, 0, 42, 9, "wide");
      var npv = HistoricalIrr.prepare(flows).npv();
      var d = Irr.digest(flows);
      for (double phi : new double[] {-2, -0.1, 0.1, 2}) {
        double rate = Math.expm1(phi), scale = 0;
        for (int i = 0; i < d.size(); i++)
          scale += Math.abs(d.amount(i)) * Math.exp(-phi * d.time(i));
        double expected = npv.value(rate), tolerance = 1e-10 * scale;
        var automatic = npv.value(new DerivativeStructure(1, 1, 0, rate));
        var analytic = Npv.rateAnalytic(d, rate);
        assertEquals(expected, automatic.getValue(), tolerance);
        assertEquals(expected, Npv.rateValue(d, rate), tolerance);
        assertEquals(expected, analytic.value(), tolerance);
        assertEquals(
            automatic.getPartialDerivative(1),
            analytic.derivative(),
            1e-9 * Math.abs(analytic.derivative()));
        assertEquals(expected, Npv.Phi.DIRECT.value(d, phi), tolerance);
        // For phi < 0 the incremental evaluators return exp(phi * T) times the NPV.
        double factor = phi < 0 ? Math.exp(phi * d.time(d.size() - 1)) : 1;
        double[] table = Npv.newTable(d);
        assertEquals(expected * factor, Npv.tableValue(d, phi, table), tolerance * factor);
        assertEquals(Npv.tableValue(d, phi, table), Npv.tableBoth(d, phi, table).value(), 0);
        for (var e : new Npv.Phi[] {Npv.Phi.INCREMENTAL_GENERIC, Npv.Phi.INCREMENTAL}) {
          assertEquals(expected * factor, e.value(d, phi), tolerance * factor);
          assertEquals(e.value(d, phi), e.both(d, phi).value(), 0);
          // Same Newton step as the direct evaluator.
          var direct = Npv.Phi.DIRECT.both(d, phi);
          var both = e.both(d, phi);
          assertEquals(
              direct.value() / direct.derivative(),
              both.value() / both.derivative(),
              1e-9 * Math.max(1, Math.abs(direct.value() / direct.derivative())));
        }
      }
    }
  }

  /** On the happy-path corpora every stage times a successful solve of the same root. */
  @Test
  void everyStageSolvesTheHappyPathCorpora() {
    for (String family : new String[] {"standard", "jcurve"}) {
      var c = corpus(family);
      for (int i = 0; i < Corpus.SIZE; i++) {
        double target = Synthetic.targetPhi(c.frequency, 256, 0, c.seed, i, family);
        for (var stage : Irr.Stage.values()) {
          var result = Irr.solve(c.dated.get(i), stage);
          assertTrue(result.hasRoot(), stage + " " + result);
          assertEquals(target, result.phi(), 1e-5);
        }
      }
    }
  }

  /** Prepared and dated benchmarks time the same solve. */
  @Test
  void preparedAndDatedAgree() {
    for (String family : LadderDiagnostics.CLASSES) {
      var c = corpus(family);
      for (int i = 0; i < 8; i++)
        for (var stage : Irr.Stage.values())
          assertEquals(
              Irr.solve(c.dated.get(i), stage).toString(),
              Irr.solve(Irr.prepare(c.dated.get(i), stage)).toString());
    }
  }

  @Test
  void diagnosticResidualIsSmallAtConstructedRoots() {
    var c = corpus("wide");
    for (int i = 0; i < Corpus.SIZE; i++) {
      double target = Synthetic.targetPhi(c.frequency, 256, 0, c.seed, i, "wide");
      assertTrue(LadderDiagnostics.residual(Irr.digest(c.dated.get(i)), target) < 1e-12);
    }
  }
}
