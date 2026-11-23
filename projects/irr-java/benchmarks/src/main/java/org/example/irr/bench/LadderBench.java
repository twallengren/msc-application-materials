package org.example.irr.bench;

import java.util.concurrent.TimeUnit;
import org.example.irr.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Layer 3: complete solves at every cumulative stage, on identical inputs per input class.
 * "prepared" times the solve on stage-specific prepared input; "dated" also times preparation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx512m"})
public class LadderBench {
  @State(Scope.Thread)
  public static class Inputs extends Corpus {
    @Param({
      "standard",
      "wide",
      "jcurve",
      "signflips",
      "extreme-positive",
      "extreme-negative",
      "dominance"
    })
    public String family;

    @Param({
      "HISTORICAL",
      "PRIMITIVE_ARRAYS",
      "ANALYTIC_DERIVATIVE",
      "PHI_DIRECT",
      "INCREMENTAL",
      "SMALL_POWERS",
      "ANALYTIC_BOUNDS",
      "NEWTON_FIRST",
      "OPTIMIZED",
      "POWER_TABLE",
      "TRUSTED_GUESS",
      "LOG_NEWTON"
    })
    public Irr.Stage stage;

    public Irr.Prepared[] prepared;

    @Setup(Level.Trial)
    public void setup() {
      generate(family);
      prepared = new Irr.Prepared[SIZE];
      for (int i = 0; i < SIZE; i++) prepared[i] = Irr.prepare(dated.get(i), stage);
    }
  }

  @Benchmark
  @OperationsPerInvocation(Corpus.SIZE)
  public void prepared(Inputs s, Blackhole bh) {
    for (var p : s.prepared) bh.consume(Irr.solve(p));
  }

  @Benchmark
  @OperationsPerInvocation(Corpus.SIZE)
  public void dated(Inputs s, Blackhole bh) {
    for (var flows : s.dated) bh.consume(Irr.solve(flows, s.stage));
  }
}
