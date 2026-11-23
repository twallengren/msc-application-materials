package org.example.irr;

import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;

/**
 * NPV evaluation on a {@link Digest}. Rate-space methods serve the intermediate ladder stages; the
 * {@link Phi} evaluators are the optimized design. Nothing here normalizes amounts or compensates
 * sums.
 */
public final class Npv {
  private Npv() {}

  /** Value and derivative from one loop. */
  public record Evaluation(double value, double derivative) {}

  // ---------------------------------------------------------------- rate space, primitive arrays

  /** sum(C * Math.pow(1 + r, -t)) on primitive arrays. */
  public static double rateValue(Digest d, double rate) {
    double total = 0;
    for (int i = 0, n = d.size(); i < n; i++)
      total += d.amounts[i] * Math.pow(1 + rate, -d.times[i]);
    return total;
  }

  /** The same expression through automatic differentiation, on primitive arrays. */
  public static DerivativeStructure rateAutomatic(Digest d, DerivativeStructure rate) {
    DerivativeStructure base = rate.add(1);
    DerivativeStructure total =
        new DerivativeStructure(rate.getFreeParameters(), rate.getOrder(), 0);
    for (int i = 0, n = d.size(); i < n; i++)
      total = total.add(base.pow(-d.times[i]).multiply(d.amounts[i]));
    return total;
  }

  /** Value and analytic rate derivative: -sum(t * C * (1 + r)^(-t - 1)). */
  public static Evaluation rateAnalytic(Digest d, double rate) {
    double total = 0, derivative = 0, base = 1 + rate;
    for (int i = 0, n = d.size(); i < n; i++) {
      double term = d.amounts[i] * Math.pow(base, -d.times[i]);
      total += term;
      derivative -= d.times[i] * term;
    }
    return new Evaluation(total, derivative / base);
  }

  /** x-space form sum(C * Math.pow(x, t)), x = 1 / (1 + r). */
  public static double xValue(Digest d, double x) {
    double total = 0;
    for (int i = 0, n = d.size(); i < n; i++) total += d.amounts[i] * Math.pow(x, d.times[i]);
    return total;
  }

  // ---------------------------------------------------------------------------------- phi space

  /**
   * phi-space evaluators. For phi &lt; 0 the incremental evaluators return G = exp(phi * T) * F and
   * the derivative exp(phi * T) * F', so the sign, the roots and the Newton step F / F' are those
   * of F, but the value is not the true NPV. DIRECT always returns the true NPV.
   */
  public enum Phi {
    /** One Math.exp per flow. */
    DIRECT {
      @Override
      public double value(Digest d, double phi) {
        double npv = 0;
        for (int i = 0, n = d.size(); i < n; i++)
          npv = Math.fma(d.amounts[i], Math.exp(-d.times[i] * phi), npv);
        return npv;
      }

      @Override
      public Evaluation both(Digest d, double phi) {
        double npv = 0, dn = 0;
        for (int i = 0, n = d.size(); i < n; i++) {
          double k = Math.exp(-d.times[i] * phi);
          npv = Math.fma(d.amounts[i], k, npv);
          dn = Math.fma(d.amounts[i] * d.times[i], k, dn);
        }
        return new Evaluation(npv, -dn);
      }
    },
    /** Gap recurrence with plain square-and-multiply integer powers. */
    INCREMENTAL_GENERIC {
      @Override
      public double value(Digest d, double phi) {
        int n = d.size();
        if (n == 0) return 0;
        double k = 1, npv;
        if (phi >= 0) {
          double c = Math.exp(-phi * d.dayFraction());
          k = Math.exp(-phi * d.times[0]);
          npv = d.amounts[0] * k;
          for (int i = 1; i < n; i++) {
            k *= binaryPower(c, d.gaps[i]);
            npv = Math.fma(d.amounts[i], k, npv);
          }
        } else {
          double c = Math.exp(phi * d.dayFraction());
          npv = d.amounts[n - 1];
          for (int i = n - 2; i >= 0; i--) {
            k *= binaryPower(c, d.gaps[i + 1]);
            npv = Math.fma(d.amounts[i], k, npv);
          }
        }
        return npv;
      }

      @Override
      public Evaluation both(Digest d, double phi) {
        int n = d.size();
        if (n == 0) return new Evaluation(0, 0);
        double k = 1, npv, dn;
        if (phi >= 0) {
          double c = Math.exp(-phi * d.dayFraction());
          k = Math.exp(-phi * d.times[0]);
          npv = d.amounts[0] * k;
          dn = d.amounts[0] * d.times[0] * k;
          for (int i = 1; i < n; i++) {
            k *= binaryPower(c, d.gaps[i]);
            npv = Math.fma(d.amounts[i], k, npv);
            dn = Math.fma(d.amounts[i] * d.times[i], k, dn);
          }
        } else {
          double c = Math.exp(phi * d.dayFraction());
          npv = d.amounts[n - 1];
          dn = d.amounts[n - 1] * d.times[n - 1];
          for (int i = n - 2; i >= 0; i--) {
            k *= binaryPower(c, d.gaps[i + 1]);
            npv = Math.fma(d.amounts[i], k, npv);
            dn = Math.fma(d.amounts[i] * d.times[i], k, dn);
          }
        }
        return new Evaluation(npv, -dn);
      }
    },
    /** Gap recurrence with written-out small powers: the optimized evaluator. */
    INCREMENTAL {
      @Override
      public double value(Digest d, double phi) {
        int n = d.size();
        if (n == 0) return 0;
        double k = 1, npv;
        if (phi >= 0) {
          double c = Math.exp(-phi * d.dayFraction());
          k = Math.exp(-phi * d.times[0]);
          npv = d.amounts[0] * k;
          for (int i = 1; i < n; i++) {
            k *= intPower(c, d.gaps[i]);
            npv = Math.fma(d.amounts[i], k, npv);
          }
        } else {
          // Backward from the last flow: every weight is at most one, so nothing overflows.
          double c = Math.exp(phi * d.dayFraction());
          npv = d.amounts[n - 1];
          for (int i = n - 2; i >= 0; i--) {
            k *= intPower(c, d.gaps[i + 1]);
            npv = Math.fma(d.amounts[i], k, npv);
          }
        }
        return npv;
      }

      @Override
      public Evaluation both(Digest d, double phi) {
        int n = d.size();
        if (n == 0) return new Evaluation(0, 0);
        double k = 1, npv, dn;
        if (phi >= 0) {
          double c = Math.exp(-phi * d.dayFraction());
          k = Math.exp(-phi * d.times[0]);
          npv = d.amounts[0] * k;
          dn = d.amounts[0] * d.times[0] * k;
          for (int i = 1; i < n; i++) {
            k *= intPower(c, d.gaps[i]);
            npv = Math.fma(d.amounts[i], k, npv);
            dn = Math.fma(d.amounts[i] * d.times[i], k, dn);
          }
        } else {
          double c = Math.exp(phi * d.dayFraction());
          npv = d.amounts[n - 1];
          dn = d.amounts[n - 1] * d.times[n - 1];
          for (int i = n - 2; i >= 0; i--) {
            k *= intPower(c, d.gaps[i + 1]);
            npv = Math.fma(d.amounts[i], k, npv);
            dn = Math.fma(d.amounts[i] * d.times[i], k, dn);
          }
        }
        // G' - T*G = -sum(C * t * k): the scaled ORIGINAL derivative, deliberately not dG/dphi.
        return new Evaluation(npv, -dn);
      }
    };

    public abstract double value(Digest d, double phi);

    public abstract Evaluation both(Digest d, double phi);
  }

  // -------------------------------------------------------------------------- power table

  /** Largest power table built per evaluation; larger gaps use {@link #intPower}. */
  public static final int TABLE_CAP = 256;

  /** Highest exponent the table of a digest holds: bounded by the cap and by the work saved. */
  public static int tableLimit(Digest d) {
    return (int) Math.min(d.maxGap(), Math.min(TABLE_CAP, 4L * d.size()));
  }

  /** Scratch space for the table evaluators of one digest; one per solve or per thread. */
  public static double[] newTable(Digest d) {
    return new double[tableLimit(d) + 1];
  }

  /** p[g] = c^g for g up to the table length, by squaring so the error grows with log g. */
  static void fillPowers(double[] p, double c) {
    p[0] = 1;
    if (p.length > 1) p[1] = c;
    for (int g = 2; g < p.length; g++) {
      double half = p[g >> 1];
      p[g] = (g & 1) == 0 ? half * half : half * half * c;
    }
  }

  /**
   * The gap recurrence with the powers c^1 .. c^limit computed once per evaluation, so each flow
   * costs a lookup and a multiplication whatever its gap. Same scaling for phi &lt; 0 as {@link
   * Phi#INCREMENTAL}.
   */
  public static double tableValue(Digest d, double phi, double[] p) {
    int n = d.size(), limit = p.length - 1;
    if (n == 0) return 0;
    int[] gaps = d.gaps;
    double[] amounts = d.amounts;
    double k = 1, npv;
    if (phi >= 0) {
      double c = Math.exp(-phi * d.dayFraction());
      fillPowers(p, c);
      k = Math.exp(-phi * d.times[0]);
      npv = amounts[0] * k;
      for (int i = 1; i < n; i++) {
        int g = gaps[i];
        k *= g <= limit ? p[g] : intPower(c, g);
        npv = Math.fma(amounts[i], k, npv);
      }
    } else {
      double c = Math.exp(phi * d.dayFraction());
      fillPowers(p, c);
      npv = amounts[n - 1];
      for (int i = n - 2; i >= 0; i--) {
        int g = gaps[i + 1];
        k *= g <= limit ? p[g] : intPower(c, g);
        npv = Math.fma(amounts[i], k, npv);
      }
    }
    return npv;
  }

  /**
   * Value and derivative with the power table; derivative convention of {@link Phi#INCREMENTAL}.
   */
  public static Evaluation tableBoth(Digest d, double phi, double[] p) {
    int n = d.size(), limit = p.length - 1;
    if (n == 0) return new Evaluation(0, 0);
    int[] gaps = d.gaps;
    double[] amounts = d.amounts, times = d.times;
    double k = 1, npv, dn;
    if (phi >= 0) {
      double c = Math.exp(-phi * d.dayFraction());
      fillPowers(p, c);
      k = Math.exp(-phi * times[0]);
      npv = amounts[0] * k;
      dn = amounts[0] * times[0] * k;
      for (int i = 1; i < n; i++) {
        int g = gaps[i];
        k *= g <= limit ? p[g] : intPower(c, g);
        npv = Math.fma(amounts[i], k, npv);
        dn = Math.fma(amounts[i] * times[i], k, dn);
      }
    } else {
      double c = Math.exp(phi * d.dayFraction());
      fillPowers(p, c);
      npv = amounts[n - 1];
      dn = amounts[n - 1] * times[n - 1];
      for (int i = n - 2; i >= 0; i--) {
        int g = gaps[i + 1];
        k *= g <= limit ? p[g] : intPower(c, g);
        npv = Math.fma(amounts[i], k, npv);
        dn = Math.fma(amounts[i] * times[i], k, dn);
      }
    }
    return new Evaluation(npv, -dn);
  }

  /**
   * The log form h(phi) = log(P / N) and its derivative, where P and N are the discounted sums of
   * the positive and the negative amounts. h has the roots of the NPV and is exactly linear for two
   * flows, so Newton on h does not overshoot where the NPV is nearly exponential. The common
   * scaling of the reverse path cancels in the ratio. Non-finite when either sum vanishes.
   */
  public static Evaluation tableLog(Digest d, double phi, double[] p) {
    int n = d.size(), limit = p.length - 1;
    if (n == 0) return new Evaluation(Double.NaN, Double.NaN);
    int[] gaps = d.gaps;
    double[] amounts = d.amounts, times = d.times;
    // Branch-free split of each amount: up = max(a, 0) and down = max(-a, 0), both exact.
    double k, pos, neg, dPos, dNeg;
    if (phi >= 0) {
      double c = Math.exp(-phi * d.dayFraction());
      fillPowers(p, c);
      k = Math.exp(-phi * times[0]);
      double up = 0.5 * (amounts[0] + Math.abs(amounts[0])), down = up - amounts[0];
      pos = up * k;
      neg = down * k;
      dPos = up * times[0] * k;
      dNeg = down * times[0] * k;
      for (int i = 1; i < n; i++) {
        int g = gaps[i];
        k *= g <= limit ? p[g] : intPower(c, g);
        up = 0.5 * (amounts[i] + Math.abs(amounts[i]));
        down = up - amounts[i];
        pos = Math.fma(up, k, pos);
        neg = Math.fma(down, k, neg);
        dPos = Math.fma(up * times[i], k, dPos);
        dNeg = Math.fma(down * times[i], k, dNeg);
      }
    } else {
      double c = Math.exp(phi * d.dayFraction());
      fillPowers(p, c);
      k = 1;
      double up = 0.5 * (amounts[n - 1] + Math.abs(amounts[n - 1])), down = up - amounts[n - 1];
      pos = up;
      neg = down;
      dPos = up * times[n - 1];
      dNeg = down * times[n - 1];
      for (int i = n - 2; i >= 0; i--) {
        int g = gaps[i + 1];
        k *= g <= limit ? p[g] : intPower(c, g);
        up = 0.5 * (amounts[i] + Math.abs(amounts[i]));
        down = up - amounts[i];
        pos = Math.fma(up, k, pos);
        neg = Math.fma(down, k, neg);
        dPos = Math.fma(up * times[i], k, dPos);
        dNeg = Math.fma(down * times[i], k, dNeg);
      }
    }
    return new Evaluation(Math.log(pos / neg), dNeg / neg - dPos / pos);
  }

  /** Straight-line multiplications for exponents 0 to 7; square-and-multiply above. */
  public static double intPower(double c, int g) {
    switch (g) {
      case 0:
        return 1;
      case 1:
        return c;
      case 2:
        return c * c;
      case 3:
        return c * c * c;
      case 4:
        {
          double c2 = c * c;
          return c2 * c2;
        }
      case 5:
        {
          double c2 = c * c;
          return c2 * c2 * c;
        }
      case 6:
        {
          double c2 = c * c;
          return c2 * c2 * c2;
        }
      case 7:
        {
          double c2 = c * c;
          return c2 * c2 * c2 * c;
        }
      default:
        return binaryPower(c, g);
    }
  }

  /** Square-and-multiply for any non-negative exponent. */
  public static double binaryPower(double c, int g) {
    if (g < 0) throw new IllegalArgumentException("Negative exponent");
    double r = 1;
    while (g > 0) {
      if ((g & 1) != 0) r *= c;
      g >>>= 1;
      if (g > 0) c *= c;
    }
    return r;
  }
}
