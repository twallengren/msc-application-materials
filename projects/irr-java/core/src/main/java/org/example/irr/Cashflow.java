package org.example.irr;

import java.time.LocalDate;

/** A dated amount. Validation occurs in Irr.prepare. */
public record Cashflow(LocalDate date, double amount) {}
