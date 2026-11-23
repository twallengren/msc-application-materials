package org.example.irr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Historical against optimized: agreement of roots and the cases where only one has a root. */
class ParityTest {
  static final int[] COUNTS = {16, 256};
  static final long[] SEEDS = {42, 20261122, 8675309};
  static final int INDICES = 64;
  static final List<String> UNIQUE_ROOT =
      List.of("standard", "wide", "jcurve", "extreme-positive", "extreme-negative");
  static final List<String> CATEGORIES =
      List.of("agree", "disagree", "onlyHistorical", "onlyOptimized", "neither", "histMinusOne");

  static final Map<String, Map<String, LongAdder>> TABLE = new ConcurrentHashMap<>();

  static void count(String family, String category) {
    TABLE
        .computeIfAbsent(family, k -> new ConcurrentHashMap<>())
        .computeIfAbsent(category, k -> new LongAdder())
        .increment();
  }

  @AfterAll
  static void printTable() {
    var out = new StringBuilder("\nParity, historical vs optimized (cases per outcome)\n");
    out.append(String.format("%-18s", "family"));
    for (String c : CATEGORIES) out.append(String.format("%16s", c));
    out.append('\n');
    for (var e : new TreeMap<>(TABLE).entrySet()) {
      out.append(String.format("%-18s", e.getKey()));
      for (String c : CATEGORIES)
        out.append(String.format("%16d", e.getValue().getOrDefault(c, new LongAdder()).sum()));
      out.append('\n');
    }
    System.out.print(out);
  }

  /** Rates within 1e-5 * max(1, |rate|); in phi within 1e-5 when |phi| exceeds 5. */
  static boolean agree(IrrResult h, IrrResult o) {
    if (Math.abs(o.phi()) > 5 || Math.abs(h.phi()) > 5) return Math.abs(h.phi() - o.phi()) <= 1e-5;
    return Math.abs(h.rate() - o.rate()) <= 1e-5 * Math.max(1, Math.abs(o.rate()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "standard",
        "wide",
        "jcurve",
        "signflips",
        "extreme-positive",
        "extreme-negative",
        "dominance",
        "excluded"
      })
  void historicalAgainstOptimized(String family) {
    var jobs = new ArrayList<long[]>();
    for (var f : Synthetic.Frequency.values())
      for (int count : COUNTS)
        for (long seed : SEEDS)
          for (int index = 0; index < INDICES; index++)
            jobs.add(new long[] {f.ordinal(), count, seed, index});
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    long[] total = new long[1];
    jobs.parallelStream()
        .forEach(
            j -> {
              var f = Synthetic.Frequency.values()[(int) j[0]];
              String label = family + " " + f + "/" + j[1] + "/seed" + j[2] + "/idx" + j[3];
              var flows = Synthetic.corpusCase(f, (int) j[1], 0, j[2], (int) j[3], family);
              IrrResult h = Irr.solve(flows, Irr.Stage.HISTORICAL);
              IrrResult o = Irr.solve(flows, Irr.Stage.OPTIMIZED);
              if (h.status() == IrrResult.Status.MINUS_ONE_FALLBACK) count(family, "histMinusOne");
              String category;
              if (h.hasRoot() && o.hasRoot()) {
                category = agree(h, o) ? "agree" : "disagree";
                if (category.equals("disagree") && UNIQUE_ROOT.contains(family))
                  failures.add(label + " disagree: historical " + h + " optimized " + o);
              } else if (h.hasRoot()) category = "onlyHistorical";
              else if (o.hasRoot()) category = "onlyOptimized";
              else category = "neither";
              count(family, category);
              boolean mustHaveRoots =
                  family.equals("standard") || family.equals("wide") || family.equals("jcurve");
              if (mustHaveRoots && !(h.hasRoot() && o.hasRoot() && category.equals("agree")))
                failures.add(
                    label + " expected agreeing roots: historical " + h + " optimized " + o);
            });
    var sorted = new ArrayList<>(failures);
    Collections.sort(sorted);
    assertTrue(
        sorted.isEmpty(),
        sorted.size()
            + " failures, first ones:\n"
            + String.join("\n", sorted.subList(0, Math.min(40, sorted.size()))));
    long sum = 0;
    for (String c : CATEGORIES.subList(0, 5))
      sum += TABLE.get(family).getOrDefault(c, new LongAdder()).sum();
    assertEquals(jobs.size(), sum);
  }
}
