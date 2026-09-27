package dev.banking.account.domain;
import java.math.BigDecimal;
public record Operation(String kind, BigDecimal amount, BigDecimal balance) {}
