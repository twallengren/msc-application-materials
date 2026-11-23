package org.example.irr;

public enum SolverStrategy {
  /** The optimized solver as originally designed (stage OPTIMIZED). */
  REVISED,
  HISTORICAL,
  /** The optimized solver with the three extensions of this study (stage LOG_NEWTON). */
  EXTENDED
}
