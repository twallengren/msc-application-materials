package org.example.irr;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Independent 60-digit arithmetic for tests: a decimal Taylor exponential with range reduction and
 * squaring. It shares no code with the evaluators, Math.exp, or the root solvers.
 */
final class HighPrecision {
  private HighPrecision() {}

  static final MathContext MC = new MathContext(60, RoundingMode.HALF_EVEN);

  static BigDecimal exp(BigDecimal x) {
    int squares = 0;
    while (x.abs().compareTo(new BigDecimal("0.125")) > 0) {
      x = x.divide(BigDecimal.TWO, MC);
      squares++;
      if (squares > 20) throw new AssertionError("Unexpected test argument");
    }
    BigDecimal sum = BigDecimal.ONE, term = BigDecimal.ONE;
    for (int k = 1; k < 1000; k++) {
      term = term.multiply(x, MC).divide(BigDecimal.valueOf(k), MC);
      sum = sum.add(term, MC);
      if (term.abs().compareTo(BigDecimal.ONE.scaleByPowerOfTen(-65)) < 0) break;
    }
    for (int i = 0; i < squares; i++) sum = sum.multiply(sum, MC);
    return sum;
  }

  /**
   * Relative residual |sum(C * w)| / sum(|C| * w) at phi, with weights scaled so the largest is one
   * (from the first flow for phi >= 0, from the last for phi < 0).
   */
  static BigDecimal residual(Digest d, double phi) {
    BigDecimal sum = BigDecimal.ZERO, abs = BigDecimal.ZERO;
    BigDecimal period = BigDecimal.valueOf(d.periodDays());
    long offset = 0, total = d.periodDays();
    for (int i = 0; i < d.size(); i++) {
      offset += d.gap(i);
      BigDecimal t = BigDecimal.valueOf(phi < 0 ? offset - total : offset).divide(period, MC);
      BigDecimal term =
          new BigDecimal(d.amount(i), MC)
              .multiply(exp(new BigDecimal(phi, MC).negate().multiply(t, MC)), MC);
      sum = sum.add(term, MC);
      abs = abs.add(term.abs(), MC);
    }
    return sum.abs().divide(abs, MC);
  }
}
