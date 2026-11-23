package org.example.irr.bench;

import java.util.concurrent.TimeUnit;
import org.example.irr.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Throughput of independent solves on several threads (set with JMH -t). Every thread owns its
 * prepared inputs, so the only shared resources are the cores, their caches and the allocator. The
 * score is solves per second summed over threads.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class ScalingBench {
  @State(Scope.Thread)
  public static class Inputs extends Corpus {
    @Param({"standard", "wide"})
    public String family;

    @Param({"HISTORICAL", "LOG_NEWTON"})
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
  public void solve(Inputs s, Blackhole bh) {
    for (var p : s.prepared) bh.consume(Irr.solve(p));
  }
}
