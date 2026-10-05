package dev.banking.account.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountSummary(UUID id, BigDecimal balance, String currency) {}
