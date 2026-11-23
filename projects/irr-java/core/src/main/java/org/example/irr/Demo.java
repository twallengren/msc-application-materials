package org.example.irr;

import java.time.LocalDate;
import java.util.List;

public final class Demo {
  public static void main(String[] args) {
    var flows =
        List.of(
            new Cashflow(LocalDate.of(2024, 1, 1), -1000),
            new Cashflow(LocalDate.of(2024, 7, 1), 200),
            new Cashflow(LocalDate.of(2025, 1, 1), 900));
    System.out.println(
        "Actual period days=" + Irr.digest(flows).periodDays() + "; rates are not annualized");
    for (var stage : Irr.Stage.values()) System.out.println(stage + ": " + Irr.solve(flows, stage));
    System.out.println(
        "No contributions: "
            + Irr.solve(
                List.of(
                    new Cashflow(LocalDate.of(2024, 1, 1), 1),
                    new Cashflow(LocalDate.of(2025, 1, 1), 2)),
                SolverStrategy.REVISED));
    System.out.println(
        "No root: "
            + Irr.solve(
                List.of(
                    new Cashflow(LocalDate.of(2024, 1, 1), 10),
                    new Cashflow(LocalDate.of(2024, 7, 1), -9),
                    new Cashflow(LocalDate.of(2025, 1, 1), 10)),
                SolverStrategy.REVISED));
  }
}
