package org.example.irr.bench;

import org.example.irr.*;

/**
 * Untimed per-input outcomes for every timed solver configuration; identifiers match the JMH
 * parameters. The residual is computed here, independently of the solve, with direct exponentials
 * scaled so the largest weight is one.
 */
public final class LadderDiagnostics {
  public static final String[] CLASSES = {
    "standard", "wide", "jcurve", "signflips", "extreme-positive", "extreme-negative", "dominance"
  };

  public static void main(String[] args) {
    System.out.println(
        "generator,frequency,configuration,seed,family,stage,index,flows,target_phi,status,path,"
            + "values,derivatives,newton_iterations,phi,rate,residual");
    for (String family : CLASSES) emit(Synthetic.Frequency.DAILY, "n256", 42, family);
    for (long seed : new long[] {20261122, 8675309})
      emit(Synthetic.Frequency.DAILY, "n256", seed, "standard");
    for (var frequency :
        new Synthetic.Frequency[] {
          Synthetic.Frequency.WEEKLY, Synthetic.Frequency.MONTHLY, Synthetic.Frequency.IRREGULAR
        }) emit(frequency, "n256", 42, "standard");
    for (String configuration : new String[] {"n16", "n4096"})
      emit(Synthetic.Frequency.DAILY, configuration, 42, "standard");
  }

  /** |sum(C w)| / sum(|C| w) with w = exp(-phi (t - shift)), shift chosen so max w is one. */
  public static double residual(Digest d, double phi) {
    if (!Double.isFinite(phi)) return Double.NaN;
    double shift = phi < 0 ? d.time(d.size() - 1) : 0, sum = 0, abs = 0;
    for (int i = 0; i < d.size(); i++) {
      double term = d.amount(i) * Math.exp(-phi * (d.time(i) - shift));
      sum += term;
      abs += Math.abs(term);
    }
    return Math.abs(sum) / abs;
  }

  static void emit(Synthetic.Frequency frequency, String configuration, long seed, String family) {
    var c = new Corpus();
    c.frequency = frequency;
    c.configuration = configuration;
    c.seed = seed;
    c.generate(family);
    for (var stage : Irr.Stage.values())
      for (int i = 0; i < Corpus.SIZE; i++) {
        var flows = c.dated.get(i);
        var digest = Irr.digest(flows);
        var r = Irr.solve(flows, stage);
        System.out.printf(
            java.util.Locale.ROOT,
            "v3,%s,%s,%d,%s,%s,%d,%d,%.17g,%s,%s,%d,%d,%d,%.17g,%.17g,%.17g%n",
            frequency,
            configuration,
            seed,
            family,
            stage,
            i,
            digest.size(),
            Synthetic.targetPhi(
                frequency,
                Corpus.count(configuration),
                Corpus.years(configuration),
                seed,
                i,
                family),
            r.status(),
            r.path(),
            r.valueEvaluations(),
            r.derivativeEvaluations(),
            r.newtonIterations(),
            r.phi(),
            r.rate(),
            r.hasRoot() ? residual(digest, r.phi()) : Double.NaN);
      }
  }
}
