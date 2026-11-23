package org.example.irr;

import java.util.List;

/**
 * Single-pass digest of dated cash flows into primitive arrays, on integer day numbers. Arrays are
 * privately owned; only the first {@link #size()} entries are meaningful. A digest is immutable;
 * moments are computed on request and never cached, so every solve pays for its own.
 */
public final class Digest {
  public enum Terminal {
    NONE,
    /** No non-zero flows: rate 0. */
    EMPTY,
    /** No negative amounts: rate +infinity. */
    NO_CONTRIBUTIONS,
    /** No positive amounts: rate -1. */
    NO_DISTRIBUTIONS,
    /** All non-zero flows on one day: simple return. */
    SINGLE_DAY
  }

  final double[] amounts, times;
  final int[] gaps;
  private final int n;
  private final long periodDays;
  private final double dayFraction, totalReturn, positiveTotal, negativeTotal, firstFlow, lastFlow;
  private final double dietzWeight;
  private final long firstOpposingGap, lastOpposingGap;
  private final int maxGap;
  private final Terminal terminal;

  Digest(List<Cashflow> flows) {
    if (flows == null) throw new IllegalArgumentException("Null cash flows");
    // Locate the last non-zero flow so the period is known during the single forward pass.
    int lastIndex = -1;
    for (int i = flows.size() - 1; i >= 0; i--) {
      Cashflow f = checked(flows.get(i));
      if (f.amount() != 0) {
        lastIndex = i;
        break;
      }
    }
    amounts = new double[lastIndex + 1];
    times = new double[lastIndex + 1];
    gaps = new int[lastIndex + 1];
    long endDay = lastIndex < 0 ? 0 : flows.get(lastIndex).date().toEpochDay();
    double last = lastIndex < 0 ? 0 : flows.get(lastIndex).amount();
    long startDay = 0, previousDay = Long.MIN_VALUE, previousFlowDay = 0;
    long firstOpposing = 0, lastOpposingDay = -1;
    boolean opposingFirstSeen = false;
    double first = 0, total = 0, positive = 0, negative = 0, weighted = 0;
    int count = 0, largestGap = 0;
    long days = 0;
    for (int i = 0; i <= lastIndex; i++) {
      Cashflow f = checked(flows.get(i));
      long day = f.date().toEpochDay();
      if (day < previousDay) throw new IllegalArgumentException("Cash flows out of date order");
      previousDay = day;
      double a = f.amount();
      if (a == 0) continue;
      if (count == 0) {
        startDay = day;
        first = a;
        days = endDay - startDay;
      } else if (!opposingFirstSeen && a * first < 0) {
        opposingFirstSeen = true;
        firstOpposing = day - startDay;
      }
      long offset = day - startDay;
      if (a * last < 0) lastOpposingDay = offset;
      amounts[count] = a;
      times[count] = days == 0 ? 0 : (double) offset / days;
      gaps[count] = count == 0 ? 0 : (int) (day - previousFlowDay);
      largestGap = Math.max(largestGap, gaps[count]);
      previousFlowDay = day;
      total += a;
      if (a > 0) positive += a;
      else negative -= a;
      weighted += offset * a;
      count++;
    }
    for (int i = lastIndex + 1; i < flows.size(); i++) {
      long day = checked(flows.get(i)).date().toEpochDay();
      if (day < previousDay) throw new IllegalArgumentException("Cash flows out of date order");
      previousDay = day;
    }
    n = count;
    periodDays = days;
    dayFraction = days == 0 ? 0 : 1.0 / days;
    totalReturn = total;
    positiveTotal = positive;
    negativeTotal = negative;
    firstFlow = first;
    lastFlow = last;
    // sum(C * (end - day) / D) = sum(C) - sum((day - start) * C) / D
    dietzWeight = days == 0 ? 0 : total - weighted / days;
    maxGap = largestGap;
    firstOpposingGap = firstOpposing;
    lastOpposingGap = lastOpposingDay < 0 ? 0 : days - lastOpposingDay;
    terminal =
        n == 0
            ? Terminal.EMPTY
            : negative == 0
                ? Terminal.NO_CONTRIBUTIONS
                : positive == 0
                    ? Terminal.NO_DISTRIBUTIONS
                    : days == 0 ? Terminal.SINGLE_DAY : Terminal.NONE;
  }

  private static Cashflow checked(Cashflow f) {
    if (f == null || f.date() == null || !Double.isFinite(f.amount()))
      throw new IllegalArgumentException("Invalid cash flow");
    return f;
  }

  public int size() {
    return n;
  }

  public long periodDays() {
    return periodDays;
  }

  /** One day as a fraction of the period. */
  public double dayFraction() {
    return dayFraction;
  }

  public double amount(int i) {
    return amounts[i];
  }

  public double time(int i) {
    return times[i];
  }

  public int gap(int i) {
    return gaps[i];
  }

  /** Largest gap in days between consecutive flows. */
  public int maxGap() {
    return maxGap;
  }

  public double totalReturn() {
    return totalReturn;
  }

  public double positiveTotal() {
    return positiveTotal;
  }

  /** Magnitude of the negative amounts. */
  public double negativeTotal() {
    return negativeTotal;
  }

  public double firstFlow() {
    return firstFlow;
  }

  public double lastFlow() {
    return lastFlow;
  }

  public double dietzWeight() {
    return dietzWeight;
  }

  /** Modified Dietz estimate of the period rate. */
  public double dietzGuess() {
    return -totalReturn / dietzWeight;
  }

  public long firstOpposingGap() {
    return firstOpposingGap;
  }

  public long lastOpposingGap() {
    return lastOpposingGap;
  }

  public Terminal terminal() {
    return terminal;
  }

  /** Rate of a terminal case; NaN when the digest needs a solve. */
  public double terminalRate() {
    return switch (terminal) {
      case EMPTY -> 0;
      case NO_CONTRIBUTIONS -> Double.POSITIVE_INFINITY;
      case NO_DISTRIBUTIONS -> -1;
      case SINGLE_DAY -> positiveTotal / negativeTotal - 1;
      case NONE -> Double.NaN;
    };
  }

  /** m_k = sum(C_i * t_i^k) for k = 0, 1, 2. */
  public record Moments(double m0, double m1, double m2) {}

  /** One pass over the arrays; not cached. */
  public Moments moments() {
    double s0 = 0, s1 = 0, s2 = 0;
    for (int i = 0; i < n; i++) {
      double ct = amounts[i] * times[i];
      s0 += amounts[i];
      s1 += ct;
      s2 += ct * times[i];
    }
    return new Moments(s0, s1, s2);
  }

  public double moment(int k) {
    Moments m = moments();
    return switch (k) {
      case 0 -> m.m0();
      case 1 -> m.m1();
      case 2 -> m.m2();
      default -> throw new IllegalArgumentException("Moment order");
    };
  }
}
