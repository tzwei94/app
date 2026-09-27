package dev.banking.account.domain;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
public interface AccountRepository {
    Optional<Balance> findOwned(UUID id, String subject, boolean lock);
    Optional<Operation> findOperation(UUID id, String key);
    void saveOperation(UUID id, String key, String kind, BigDecimal amount, BigDecimal balance);
}
