package org.example.irr;

import org.example.irr.Digest.Moments;

/** Initial guesses in phi from the moments m_k = sum(C * t^k). */
public final class Guess {
  private Guess() {}

  public static final double PADE_GUARD = 0.05;

  /** Root of the linear Taylor model: one Newton step from phi = 0. */
  public static double linear(Moments m) {
    return m.m0() / m.m1();
  }

  /** Root of the [1/1] Pade approximant; NaN when its denominator is poorly conditioned. */
  public static double pade(Moments m) {
    double denominator = m.m1() * m.m1() - 0.5 * m.m0() * m.m2();
    return denominator > PADE_GUARD * m.m1() * m.m1() ? m.m0() * m.m1() / denominator : Double.NaN;
  }

  /** Guess waterfall for a guaranteed direction (+1 or -1). */
  public static double waterfall(Digest d, Moments m, int direction, boolean usePade) {
    if (usePade) {
      double p = pade(m);
      if (Double.isFinite(p) && (p * direction > 0 || p * m.m0() > 0)) return p;
    }
    double l = linear(m);
    if (Double.isFinite(l) && l * direction > 0) return l;
    double simple = Math.log(1 - m.m0() / d.firstFlow());
    if (Double.isFinite(simple) && simple * direction > 0) return simple;
    return direction > 0 ? Math.log(1.1) : Math.log(0.9);
  }

  /** The Pade guess whenever its guard accepts it, whatever the direction; else the waterfall. */
  public static double trusted(Digest d, Moments m, int direction) {
    double p = pade(m);
    return Double.isFinite(p) ? p : waterfall(d, m, direction, false);
  }

  /** Guess when no root is guaranteed. */
  public static double heuristic(Moments m, boolean usePade) {
    if (usePade) {
      double p = pade(m);
      if (Double.isFinite(p)) return p;
    }
    double l = linear(m);
    return Double.isFinite(l) ? l : 0.1;
  }

  // Convenience forms that compute the moments themselves.

  public static double linear(Digest d) {
    return linear(d.moments());
  }

  public static double pade(Digest d) {
    return pade(d.moments());
  }

  public static double waterfall(Digest d, int direction, boolean usePade) {
    return waterfall(d, d.moments(), direction, usePade);
  }

  public static double heuristic(Digest d, boolean usePade) {
    return heuristic(d.moments(), usePade);
  }
}
