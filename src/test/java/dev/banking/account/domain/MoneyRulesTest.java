package dev.banking.account.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyRulesTest {
    @Test void preservesExactDecimals() {
        assertThat(MoneyRules.next(new BigDecimal("0.10"), new BigDecimal("0.20"), "deposit"))
            .isEqualByComparingTo("0.30");
    }
    @Test void rejectsOverdraftAndOverflow() {
        assertThatThrownBy(() -> MoneyRules.next(new BigDecimal("1.00"), new BigDecimal("1.01"), "withdrawal"))
            .isInstanceOf(BankingFailure.class).hasMessage("insufficient_funds");
        assertThatThrownBy(() -> MoneyRules.next(new BigDecimal("99999999999999999.99"), new BigDecimal("0.01"), "deposit"))
            .isInstanceOf(BankingFailure.class).hasMessage("balance_limit");
    }
}
