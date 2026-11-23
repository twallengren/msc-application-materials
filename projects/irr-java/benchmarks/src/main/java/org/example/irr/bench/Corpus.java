package org.example.irr.bench;

import java.util.*;
import org.example.irr.*;
import org.openjdk.jmh.annotations.*;

/** 64 dated inputs per configuration, generated outside timed methods. */
@State(Scope.Thread)
public class Corpus {
  public static final int SIZE = 64;

  @Param({"DAILY", "WEEKLY", "MONTHLY", "IRREGULAR", "EVERY_37_DAYS", "IRREGULAR_SORTED"})
  public Synthetic.Frequency frequency;

  /** nK = K flows; y1 = a fixed one-year horizon. */
  @Param({"n16", "n100", "n256", "n365", "n1000", "n3650", "n4096", "n10000", "y1"})
  public String configuration;

  @Param({"42", "20261122", "8675309"})
  public long seed;

  public List<List<Cashflow>> dated;

  /** Predetermined evaluation points, identical for every evaluator. */
  public double[] phis;

  public static int count(String configuration) {
    return configuration.startsWith("n") ? Integer.parseInt(configuration.substring(1)) : 16;
  }

  public static int years(String configuration) {
    return configuration.startsWith("y") ? Integer.parseInt(configuration.substring(1)) : 0;
  }

  public void generate(String family) {
    dated = new ArrayList<>();
    phis = new double[SIZE];
    for (int i = 0; i < SIZE; i++) {
      dated.add(
          Synthetic.corpusCase(
              frequency, count(configuration), years(configuration), seed, i, family));
      phis[i] = -2 + 4 * new Random(seed + 31 * i).nextDouble();
    }
  }
}
