package org.example.irr.bench;

import java.util.concurrent.TimeUnit;
import org.example.irr.*;
import org.example.irr.historical.HistoricalIrr;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/** Layer 2: preparation only. Three-pass boxed preparation against the single-pass digest. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx512m"})
public class PrepBench {
  public enum Preparation {
    HISTORICAL,
    DIGEST
  }

  @State(Scope.Thread)
  public static class Inputs extends Corpus {
    @Param({"HISTORICAL", "DIGEST"})
    public Preparation preparation;

    @Setup(Level.Trial)
    public void setup() {
      generate("wide");
    }
  }

  @Benchmark
  @OperationsPerInvocation(Corpus.SIZE)
  public void prepare(Inputs s, Blackhole bh) {
    if (s.preparation == Preparation.HISTORICAL)
      for (var flows : s.dated) bh.consume(HistoricalIrr.prepare(flows));
    else for (var flows : s.dated) bh.consume(Irr.digest(flows));
  }
}
