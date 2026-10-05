package dev.banking.user.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    // Internal authentication data; never use as an API response or log payload.
    record Credentials(UUID id, String username, String passwordHash, long version) {}
    UserProfile create(UUID id, String username, String displayName, String passwordHash);
    List<UserProfile> list(int limit, int offset);
    Optional<UserProfile> find(UUID id);
    Optional<UserProfile> update(UUID id, String username, String displayName, String passwordHash);
    boolean delete(UUID id);
    Optional<Credentials> credentials(String username);
    boolean validToken(UUID id, long version);
}
