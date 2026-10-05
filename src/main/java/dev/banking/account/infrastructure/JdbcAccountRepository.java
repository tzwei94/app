package dev.banking.account.infrastructure;
import dev.banking.account.domain.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {
    private final JdbcTemplate db;
    public JdbcAccountRepository(JdbcTemplate db) { this.db = db; }
    @Override public Optional<Balance> findOwned(UUID id, String subject, boolean lock) {
        return db.query("SELECT balance,currency FROM banking_accounts WHERE id=? AND owner_subject=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) -> new Balance(r.getBigDecimal(1), r.getString(2)), id, subject).stream().findFirst();
    }
    @Override public List<AccountSummary> findAllOwned(String subject, int limit, int offset) {
        return db.query("SELECT id,balance,currency FROM banking_accounts WHERE owner_subject=? ORDER BY id LIMIT ? OFFSET ?",
            (r, n) -> new AccountSummary(r.getObject("id", UUID.class), r.getBigDecimal("balance"), r.getString("currency")),
            subject, limit, offset);
    }
    @Override public Optional<Operation> findOperation(UUID id, String key) {
        return db.query("SELECT kind,amount,balance_after FROM banking_operations WHERE account_id=? AND idempotency_key=?",
            (r, n) -> new Operation(r.getString(1), r.getBigDecimal(2), r.getBigDecimal(3)), id, key).stream().findFirst();
    }
    @Override public void saveOperation(UUID id, String key, String kind, BigDecimal amount, BigDecimal balance) {
        db.update("UPDATE banking_accounts SET balance=? WHERE id=?", balance, id);
        db.update("INSERT INTO banking_operations(account_id,idempotency_key,kind,amount,balance_after) VALUES (?,?,?,?,?)", id, key, kind, amount, balance);
    }
}
