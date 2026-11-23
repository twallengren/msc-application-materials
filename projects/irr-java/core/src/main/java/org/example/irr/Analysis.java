package org.example.irr;

/** Existence analysis: analytic phi bounds, guarantees, and the resulting search directive. */
public record Analysis(Directive directive, boolean guaranteed, double phiLower, double phiUpper) {
  public enum Directive {
    /** The return is zero. */
    ZERO,
    /** No root exists on either side. */
    NO_SOLUTION,
    POSITIVE,
    NEGATIVE
  }

  public static final double DEGENERATE = 1e-9, NOISE = 1e-10, WIDENING = 1.001;

  public static Analysis of(Digest d) {
    double first = Math.abs(d.firstFlow()), last = Math.abs(d.lastFlow());
    double opposingFirst = d.firstFlow() > 0 ? d.negativeTotal() : d.positiveTotal();
    double opposingLast = d.lastFlow() > 0 ? d.negativeTotal() : d.positiveTotal();
    double upper =
        opposingFirst <= first
            ? 0
            : (double) d.periodDays() / d.firstOpposingGap() * Math.log(opposingFirst / first);
    double lower =
        opposingLast <= last
            ? 0
            : -(double) d.periodDays() / d.lastOpposingGap() * Math.log(opposingLast / last);
    boolean upperDegenerate = Math.abs(upper) <= DEGENERATE,
        lowerDegenerate = Math.abs(lower) <= DEGENERATE;
    boolean significant =
        Math.abs(d.totalReturn()) > NOISE * Math.max(d.positiveTotal(), d.negativeTotal());
    boolean positiveGuaranteed =
        d.firstFlow() * d.totalReturn() < 0 && significant && !upperDegenerate;
    boolean negativeGuaranteed =
        d.lastFlow() * d.totalReturn() < 0 && significant && !lowerDegenerate;
    Directive bySign = d.totalReturn() > 0 ? Directive.POSITIVE : Directive.NEGATIVE;
    if (upperDegenerate && lowerDegenerate)
      return new Analysis(significant ? Directive.NO_SOLUTION : Directive.ZERO, true, lower, upper);
    if (positiveGuaranteed && negativeGuaranteed) return new Analysis(bySign, true, lower, upper);
    if (positiveGuaranteed) return new Analysis(Directive.POSITIVE, true, lower, upper);
    if (negativeGuaranteed) return new Analysis(Directive.NEGATIVE, true, lower, upper);
    if (lowerDegenerate) return new Analysis(Directive.POSITIVE, false, lower, upper);
    if (upperDegenerate) return new Analysis(Directive.NEGATIVE, false, lower, upper);
    return new Analysis(bySign, false, lower, upper);
  }

  /** Lower end of the Brent bracket for the directive's side. */
  public double bracketLow() {
    return directive == Directive.POSITIVE ? 0 : WIDENING * phiLower;
  }

  public double bracketHigh() {
    return directive == Directive.POSITIVE ? WIDENING * phiUpper : 0;
  }
}
