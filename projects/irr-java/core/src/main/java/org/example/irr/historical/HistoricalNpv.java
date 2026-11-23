package org.example.irr.historical;

import java.util.List;
import org.apache.commons.math3.analysis.UnivariateFunction;
import org.apache.commons.math3.analysis.differentiation.DerivativeStructure;
import org.apache.commons.math3.analysis.differentiation.UnivariateDifferentiableFunction;

/**
 * Net present value of dated flows as a function of the rate. Flows are held as a boxed list of
 * (amount, period fraction) pairs, and every evaluation recomputes one power per flow.
 */
public final class HistoricalNpv implements UnivariateDifferentiableFunction {
  private final List<Pair<Double, Double>> cashFlows;
  private int valueCalls;
  private int derivativeCalls;

  public HistoricalNpv(List<Pair<Double, Double>> cashFlows) {
    this.cashFlows = cashFlows;
  }

  @Override
  public double value(double rate) {
    valueCalls++;
    double total = 0;
    for (Pair<Double, Double> cf : cashFlows) {
      total += cf.left() * Math.pow(1 + rate, -cf.right());
    }
    return total;
  }

  @Override
  public DerivativeStructure value(DerivativeStructure rate) {
    derivativeCalls++;
    DerivativeStructure base = rate.add(1);
    DerivativeStructure total =
        new DerivativeStructure(rate.getFreeParameters(), rate.getOrder(), 0);
    for (Pair<Double, Double> cf : cashFlows) {
      total = total.add(base.pow(-cf.right()).multiply(cf.left()));
    }
    return total;
  }

  /** The same sum in x-space, with x = 1 / (1 + rate): sum of amount * x^fraction. */
  public UnivariateFunction asXFunction() {
    return x -> {
      valueCalls++;
      double total = 0;
      for (Pair<Double, Double> cf : cashFlows) {
        total += cf.left() * Math.pow(x, cf.right());
      }
      return total;
    };
  }

  public int size() {
    return cashFlows.size();
  }

  /** Number of value(double) calls, including x-space calls, since the last reset. */
  public int valueCalls() {
    return valueCalls;
  }

  /** Number of value(DerivativeStructure) calls since the last reset. */
  public int derivativeCalls() {
    return derivativeCalls;
  }

  public void resetCounters() {
    valueCalls = 0;
    derivativeCalls = 0;
  }
}
