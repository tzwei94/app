package dev.banking.account.domain;
import java.math.BigDecimal;
public final class MoneyRules {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private MoneyRules() {}
    public static BigDecimal next(BigDecimal balance, BigDecimal amount, String kind) {
        var next = kind.equals("deposit") ? balance.add(amount) : balance.subtract(amount);
        if (next.signum() < 0) throw new BankingFailure(409, "insufficient_funds");
        if (next.compareTo(MAX_BALANCE) > 0) throw new BankingFailure(409, "balance_limit");
        return next;
    }
}
