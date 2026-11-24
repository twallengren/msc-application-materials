package org.example.irr.bench;

import java.util.Locale;
import org.example.irr.*;

/**
 * Untimed per-input outcomes of every solver in {@link LibraryBench}; identifiers match its JMH
 * parameters. XIRR's iterations come from a counted reproduction of its Newton. The residual is
 * {@link LadderDiagnostics#residual}, computed independently of every solver on the same digest.
 * The last column is |phi - phi of EXTENDED| where both returned roots.
 */
public final class LibraryDiagnostics {
  static final String[] FAMILIES = {"standard", "wide"};
  static final Synthetic.Frequency[] FREQUENCIES = {
    Synthetic.Frequency.DAILY, Synthetic.Frequency.IRREGULAR
  };

  public static void main(String[] args) {
    System.out.println(
        "generator,configuration,seed,amounts,family,frequency,solver,index,status,detail,phi,"
            + "residual,phi_diff_extended,iterations");
    for (var amounts : Libraries.Amounts.values())
      for (var frequency : FREQUENCIES)
        for (String family : FAMILIES) emit(frequency, "n256", 42, family, amounts);
  }

  static boolean runs(Libraries.Solver solver, Synthetic.Frequency frequency) {
    return (!solver.isPoi() && solver != Libraries.Solver.EXTENDED_PREPARED)
        || frequency == Synthetic.Frequency.DAILY;
  }

  static void emit(
      Synthetic.Frequency frequency,
      String configuration,
      long seed,
      String family,
      Libraries.Amounts amounts) {
    var c = new Corpus();
    c.frequency = frequency;
    c.configuration = configuration;
    c.seed = seed;
    c.generate(family);
    for (var solver : Libraries.Solver.values()) {
      if (!runs(solver, frequency)) continue;
      for (int i = 0; i < Corpus.SIZE; i++) {
        var flows =
            amounts == Libraries.Amounts.NORMALIZED
                ? Libraries.normalized(c.dated.get(i))
                : c.dated.get(i);
        var in = Libraries.input(flows);
        var digest = Irr.digest(flows);
        var o = Libraries.outcome(solver, in);
        var reference = Libraries.outcome(Libraries.Solver.EXTENDED, in);
        boolean root = o.status() == Libraries.Status.ROOT;
        System.out.printf(
            Locale.ROOT,
            "v3,%s,%d,%s,%s,%s,%s,%d,%s,%s,%.17g,%.17g,%.17g,%s%n",
            configuration,
            seed,
            amounts,
            family,
            frequency,
            solver,
            i,
            o.status(),
            o.detail(),
            o.phi(),
            root ? LadderDiagnostics.residual(digest, o.phi()) : Double.NaN,
            root && reference.status() == Libraries.Status.ROOT
                ? Math.abs(o.phi() - reference.phi())
                : Double.NaN,
            o.iterations() < 0 ? "" : Integer.toString(o.iterations()));
      }
    }
  }
}
