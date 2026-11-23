package org.example.irr;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Generator v3: java.util.Random, fixed anchor, bounded inputs; no generation in timed methods.
 *
 * <p>Input classes for {@link #corpusCase}: {@code standard} (conventional, modest return), {@code
 * jcurve} (several early contributions), {@code wide} (conventional, phi in [-2,2)), {@code
 * signflips} (many interior sign changes around a constructed root), {@code extreme-positive} and
 * {@code extreme-negative} (roots outside the historical rate brackets), and the no-root classes
 * {@code dominance} and {@code excluded}. {@code unconventional} keeps random signs with no
 * constructed root.
 */
public final class Synthetic {
  public static final LocalDate ANCHOR = LocalDate.of(2024, 1, 1);

  public enum Frequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    /** Random gaps of 1 to 40 days. */
    IRREGULAR,
    /** One fixed gap of 37 days: a square-and-multiply exponent that never changes. */
    EVERY_37_DAYS,
    /** The gaps of IRREGULAR for the same seed, in ascending order: same work, predictable. */
    IRREGULAR_SORTED
  }

  private Synthetic() {}

  public static List<LocalDate> dates(Frequency frequency, int count, int years, Random random) {
    var out = new ArrayList<LocalDate>();
    out.add(ANCHOR);
    LocalDate end = years == 0 ? null : ANCHOR.plusYears(years);
    for (int i = 1; years != 0 || i < count; i++) {
      LocalDate next =
          switch (frequency) {
            case DAILY -> ANCHOR.plusDays(i);
            case WEEKLY -> ANCHOR.plusWeeks(i);
            case MONTHLY -> ANCHOR.plusMonths(i);
            case EVERY_37_DAYS -> ANCHOR.plusDays(37L * i);
            case IRREGULAR, IRREGULAR_SORTED -> out.getLast().plusDays(1 + random.nextInt(40));
          };
      if (end != null && !next.isBefore(end)) break;
      out.add(next);
    }
    if (frequency == Frequency.IRREGULAR_SORTED) {
      long[] gaps = new long[out.size() - 1];
      for (int i = 0; i < gaps.length; i++)
        gaps[i] = ChronoUnit.DAYS.between(out.get(i), out.get(i + 1));
      Arrays.sort(gaps);
      for (int i = 0; i < gaps.length; i++) out.set(i + 1, out.get(i).plusDays(gaps[i]));
    }
    if (end != null) out.add(end); // Explicit endpoint preserves the common horizon.
    return List.copyOf(out);
  }

  public static List<Cashflow> knownRoot(
      List<LocalDate> dates, double phi, Random random, boolean logUniform, double scale) {
    return knownRoot(dates, phi, random, logUniform, scale, 0);
  }

  /**
   * Sets the first amount so that phi is a root. With flipProbability > 0 later amounts change sign
   * at random, so the constructed root need not be unique.
   */
  public static List<Cashflow> knownRoot(
      List<LocalDate> dates,
      double phi,
      Random random,
      boolean logUniform,
      double scale,
      double flipProbability) {
    long days = ChronoUnit.DAYS.between(dates.getFirst(), dates.getLast());
    var flows = new ArrayList<Cashflow>();
    double total = 0;
    flows.add(new Cashflow(dates.getFirst(), 0));
    for (int i = 1; i < dates.size(); i++) {
      double a =
          scale
              * (logUniform ? Math.exp(-6 + 12 * random.nextDouble()) : 0.1 + random.nextDouble());
      if (flipProbability > 0 && random.nextDouble() < flipProbability) a = -a;
      double t = (double) ChronoUnit.DAYS.between(dates.getFirst(), dates.get(i)) / days;
      total += a * Math.exp(-phi * t);
      flows.add(new Cashflow(dates.get(i), a));
    }
    flows.set(0, new Cashflow(dates.getFirst(), -total));
    return List.copyOf(flows);
  }

  /**
   * Turns a conventional case into a J-curve: the first eighth of the flows (at least two) become
   * contributions, rescaled so that phi is still the root. One sign change, so the root is unique.
   */
  static List<Cashflow> jcurve(List<Cashflow> known, double phi) {
    int n = known.size(), early = Math.max(2, n / 8);
    if (n < 3) return known;
    early = Math.min(early, n - 1);
    long days = ChronoUnit.DAYS.between(known.getFirst().date(), known.getLast().date());
    double contributed = 0, distributed = 0;
    for (int i = 0; i < n; i++) {
      double t =
          (double) ChronoUnit.DAYS.between(known.getFirst().date(), known.get(i).date()) / days;
      double pv = Math.abs(known.get(i).amount()) * Math.exp(-phi * t);
      if (i < early) contributed += pv;
      else distributed += pv;
    }
    double scale = distributed / contributed;
    var out = new ArrayList<Cashflow>(n);
    for (int i = 0; i < n; i++) {
      double a = Math.abs(known.get(i).amount());
      out.add(new Cashflow(known.get(i).date(), i < early ? -a * scale : a));
    }
    return List.copyOf(out);
  }

  /** The phi used to construct a known-root class case; NaN for classes without one. */
  public static double targetPhi(
      Frequency frequency, int count, int years, long seed, int index, String family) {
    Random random = new Random(seed + 0x9e3779b97f4a7c15L * index);
    dates(frequency, count, years, random);
    double u = random.nextDouble();
    return switch (family) {
      case "standard", "jcurve" -> -0.3 + 0.8 * u;
      case "wide" -> -2 + 4 * u;
      case "signflips" -> -1 + 2 * u;
      case "extreme-positive" -> 7.5 + 22.5 * u;
      case "extreme-negative" -> -30 + 22.5 * u;
      default -> Double.NaN;
    };
  }

  public static List<Cashflow> corpusCase(
      Frequency frequency, int count, int years, long seed, int index, String family) {
    Random random = new Random(seed + 0x9e3779b97f4a7c15L * index);
    var dates = dates(frequency, count, years, random);
    double u = random.nextDouble();
    double phi =
        switch (family) {
          case "standard", "jcurve" -> -0.3 + 0.8 * u;
          case "signflips" -> -1 + 2 * u;
          case "extreme-positive" -> 7.5 + 22.5 * u;
          case "extreme-negative" -> -30 + 22.5 * u;
          default -> -2 + 4 * u;
        };
    // signflips: between a quarter and a half of the later amounts change sign.
    double flips = family.equals("signflips") ? 0.25 + 0.25 * random.nextDouble() : 0;
    boolean logUniform = (index & 1) == 0;
    double scale = Math.pow(10, -6 + 12 * random.nextDouble());
    var known = knownRoot(dates, phi, random, logUniform, scale, flips);
    if (family.equals("jcurve")) return jcurve(known, phi);
    switch (family) {
      case "standard", "wide", "signflips", "extreme-positive", "extreme-negative", "jcurve" -> {
        return known;
      }
      default -> {}
    }
    var out = new ArrayList<Cashflow>();
    for (var f : known)
      out.add(
          new Cashflow(
              f.date(),
              Math.abs(f.amount())
                  * (family.equals("unconventional") && random.nextBoolean() ? -1 : 1)));
    if (family.equals("dominance")) {
      double interior = 0;
      for (int i = 1; i < out.size() - 1; i++) {
        var f = out.get(i);
        interior += f.amount();
        out.set(i, new Cashflow(f.date(), -f.amount()));
      }
      out.set(0, new Cashflow(dates.getFirst(), 2 * interior + 1));
      out.set(out.size() - 1, new Cashflow(dates.getLast(), 2 * interior + 1));
    } else if (!family.equals("excluded") && !family.equals("unconventional"))
      throw new IllegalArgumentException("Unknown family");
    return List.copyOf(out);
  }
}
