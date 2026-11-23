package org.example.irr.bench;

import java.util.concurrent.TimeUnit;
import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.example.irr.*;
import org.example.irr.historical.HistoricalIrr;
import org.example.irr.historical.HistoricalNpv;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Layer 1: one NPV evaluation of a whole schedule, no solving. Rate-space calls receive expm1(phi)
 * for the same phi the phi-space calls receive.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx512m"})
public class KernelBench {
  public enum Call {
    /** Historical value: Math.pow per flow over boxed pairs. */
    OLD_POW_VALUE,
    /** Historical value and derivative: automatic differentiation over boxed pairs. */
    OLD_AUTODIFF,
    /** Math.pow per flow over primitive arrays. */
    ARRAY_POW_VALUE,
    /** Analytic rate derivative with Math.pow over primitive arrays. */
    ARRAY_ANALYTIC_BOTH,
    PHI_DIRECT_VALUE,
    PHI_DIRECT_BOTH,
    /** Recurrence with plain square-and-multiply integer powers. */
    PHI_GENERIC_VALUE,
    PHI_INCREMENTAL_VALUE,
    PHI_INCREMENTAL_BOTH
  }

  @State(Scope.Thread)
  public static class Inputs extends Corpus {
    @Param({
      "OLD_POW_VALUE",
      "OLD_AUTODIFF",
      "ARRAY_POW_VALUE",
      "ARRAY_ANALYTIC_BOTH",
      "PHI_DIRECT_VALUE",
      "PHI_DIRECT_BOTH",
      "PHI_GENERIC_VALUE",
      "PHI_INCREMENTAL_VALUE",
      "PHI_INCREMENTAL_BOTH"
    })
    public Call call;

    public HistoricalNpv[] historical;
    public Digest[] digests;
    public double[] rates;

    @Setup(Level.Trial)
    public void setup() {
      generate("wide");
      historical = new HistoricalNpv[SIZE];
      digests = new Digest[SIZE];
      rates = new double[SIZE];
      for (int i = 0; i < SIZE; i++) {
        historical[i] = HistoricalIrr.prepare(dated.get(i)).npv();
        digests[i] = Irr.digest(dated.get(i));
        rates[i] = Math.expm1(phis[i]);
      }
    }
  }

  @Benchmark
  @OperationsPerInvocation(Corpus.SIZE)
  public void evaluate(Inputs s, Blackhole bh) {
    for (int i = 0; i < Corpus.SIZE; i++) {
      Digest d = s.digests[i];
      double phi = s.phis[i], rate = s.rates[i];
      switch (s.call) {
        case OLD_POW_VALUE -> bh.consume(s.historical[i].value(rate));
        case OLD_AUTODIFF ->
            bh.consume(s.historical[i].value(new DerivativeStructure(1, 1, 0, rate)));
        case ARRAY_POW_VALUE -> bh.consume(Npv.rateValue(d, rate));
        case ARRAY_ANALYTIC_BOTH -> bh.consume(Npv.rateAnalytic(d, rate));
        case PHI_DIRECT_VALUE -> bh.consume(Npv.Phi.DIRECT.value(d, phi));
        case PHI_DIRECT_BOTH -> bh.consume(Npv.Phi.DIRECT.both(d, phi));
        case PHI_GENERIC_VALUE -> bh.consume(Npv.Phi.INCREMENTAL_GENERIC.value(d, phi));
        case PHI_INCREMENTAL_VALUE -> bh.consume(Npv.Phi.INCREMENTAL.value(d, phi));
        case PHI_INCREMENTAL_BOTH -> bh.consume(Npv.Phi.INCREMENTAL.both(d, phi));
      }
    }
  }
}
