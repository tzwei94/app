package dev.banking.account.domain;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
public interface AccountRepository {
    Optional<Balance> findOwned(UUID id, String subject, boolean lock);
    List<AccountSummary> findAllOwned(String subject, int limit, int offset);
    Optional<Operation> findOperation(UUID id, String key);
    void saveOperation(UUID id, String key, String kind, BigDecimal amount, BigDecimal balance);
}
