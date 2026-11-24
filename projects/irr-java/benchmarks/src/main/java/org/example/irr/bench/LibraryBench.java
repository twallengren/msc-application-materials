package org.example.irr.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.decampo.xirr.Transaction;
import org.example.irr.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Our historical and extended solvers against org.decampo:xirr and Apache POI Irr, on identical
 * inputs. Our solvers start from dated flows, so their preparation is timed; library-native inputs
 * (xirr transactions, POI arrays) are converted in setup, and EXTENDED_PREPARED solves input
 * prepared in setup. With NORMALIZED amounts every flow is divided by its input's sum |C| for all
 * solvers. A library call that throws or fails to converge feeds NaN to the Blackhole instead of
 * aborting the run.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgsAppend = {"-Xms256m", "-Xmx512m"})
public class LibraryBench {
  @State(Scope.Thread)
  public static class Inputs extends Corpus {
    @Param({"standard", "wide"})
    public String family;

    @Param({"NORMALIZED", "RAW"})
    public Libraries.Amounts amounts;

    @Param({"HISTORICAL", "EXTENDED", "EXTENDED_PREPARED", "XIRR", "POI_IRR", "POI_IRR_ZERO_GUESS"})
    public Libraries.Solver solver;

    public List<Transaction>[] transactions;
    public double[][] daily;
    public Irr.Prepared[] prepared;

    @SuppressWarnings("unchecked")
    @Setup(Level.Trial)
    public void setup() {
      if (solver.isPoi() && frequency != Synthetic.Frequency.DAILY)
        throw new IllegalStateException(
            solver
                + " takes equally spaced periods and is only run on the DAILY schedule, not "
                + frequency);
      generate(family);
      // Every solver sees the same flows: normalizing replaces the dated input itself.
      if (amounts == Libraries.Amounts.NORMALIZED)
        for (int i = 0; i < SIZE; i++) dated.set(i, Libraries.normalized(dated.get(i)));
      transactions = new List[SIZE];
      daily = new double[SIZE][];
      prepared = new Irr.Prepared[SIZE];
      for (int i = 0; i < SIZE; i++) {
        var in = Libraries.input(dated.get(i));
        prepared[i] = in.prepared();
        transactions[i] = new ArrayList<>(in.transactions());
        daily[i] = in.daily();
      }
    }
  }

  @Benchmark
  @OperationsPerInvocation(Corpus.SIZE)
  public void solve(Inputs s, Blackhole bh) {
    switch (s.solver) {
      case HISTORICAL -> {
        for (var flows : s.dated) bh.consume(Irr.solve(flows, SolverStrategy.HISTORICAL));
      }
      case EXTENDED -> {
        for (var flows : s.dated) bh.consume(Irr.solve(flows, SolverStrategy.EXTENDED));
      }
      case EXTENDED_PREPARED -> {
        for (var p : s.prepared) bh.consume(Irr.solve(p));
      }
      case XIRR -> {
        for (var transactions : s.transactions) {
          double rate;
          try {
            rate = Libraries.xirr(transactions);
          } catch (RuntimeException e) {
            rate = Double.NaN;
          }
          bh.consume(rate);
        }
      }
      case POI_IRR -> {
        for (var values : s.daily) bh.consume(poi(values, 0.1));
      }
      case POI_IRR_ZERO_GUESS -> {
        for (var values : s.daily) bh.consume(poi(values, 0.0));
      }
    }
  }

  private static double poi(double[] values, double guess) {
    try {
      return Libraries.poi(values, guess);
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}
