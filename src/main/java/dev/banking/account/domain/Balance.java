package dev.banking.account.domain;
import java.math.BigDecimal;
public record Balance(BigDecimal balance, String currency) {}
