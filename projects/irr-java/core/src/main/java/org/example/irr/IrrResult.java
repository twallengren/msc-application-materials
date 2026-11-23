package org.example.irr;

/**
 * Outcome of one solve. Residuals are not computed inside a solve; tests and diagnostics evaluate
 * them independently. A returned root is not a claim of uniqueness.
 */
public record IrrResult(
    double rate,
    double phi,
    Status status,
    Path path,
    int valueEvaluations,
    int derivativeEvaluations,
    int newtonIterations) {
  public enum Status {
    /** A solver converged on a root. */
    CONVERGED,
    /** A root in phi whose rate overflows or rounds to -1; phi is preserved. */
    RATE_UNREPRESENTABLE,
    /** A terminal case of the input, or a return proven to be zero; no search ran. */
    TERMINAL,
    /** The historical cascade's assumed -100% after its solvers failed; not a computed root. */
    MINUS_ONE_FALLBACK,
    /** The analysis proved that no root exists. */
    NO_SOLUTION,
    /** Every solver in the plan failed. */
    FAILED
  }

  public enum Path {
    TERMINAL,
    ZERO,
    BRENT,
    NEWTON,
    MINUS_ONE,
    X_BRENT,
    NONE
  }

  /** True when a solver actually located a root. */
  public boolean hasRoot() {
    return status == Status.CONVERGED || status == Status.RATE_UNREPRESENTABLE;
  }

  public int evaluations() {
    return valueEvaluations + derivativeEvaluations;
  }
}
