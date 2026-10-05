package dev.banking.account.application;
import dev.banking.account.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankingService {
    private final AccountRepository accounts;
    private final MeterRegistry metrics;
    public BankingService(AccountRepository accounts, MeterRegistry metrics) { this.accounts = accounts; this.metrics = metrics; }
    private Balance account(UUID id, String subject, boolean lock) {
        return accounts.findOwned(id, subject, lock).orElseThrow(() -> new BankingFailure(404, "account_not_found"));
    }
    public Balance balance(UUID id, String subject) { return account(id, subject, false); }
    public List<AccountSummary> list(String subject, int limit, int offset) { return accounts.findAllOwned(subject, limit, offset); }
    @Transactional
    public Balance mutate(UUID id, String subject, String kind, BigDecimal amount, String key) {
        var current = account(id, subject, true);
        var prior = accounts.findOperation(id, key);
        if (prior.isPresent()) {
            var op = prior.get();
            if (!op.kind().equals(kind) || op.amount().compareTo(amount) != 0) throw new BankingFailure(409, "idempotency_conflict");
            return new Balance(op.balance(), current.currency());
        }
        var next = MoneyRules.next(current.balance(), amount, kind);
        accounts.saveOperation(id, key, kind, amount, next);
        metrics.counter("banking.operations", "operation", kind).increment();
        // Never log account identifiers, amounts, headers or payloads.
        LoggerFactory.getLogger(BankingService.class).info("banking operation accepted: {}", kind);
        return new Balance(next, current.currency());
    }
}
