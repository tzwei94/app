package dev.banking.common.security;

import dev.banking.user.domain.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

final class ManagedUserDetails extends User {
    private final UUID id;
    private final long version;
    ManagedUserDetails(UserRepository.Credentials credentials) {
        super(credentials.username(), credentials.passwordHash(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
        id = credentials.id(); version = credentials.version();
    }
    UUID id() { return id; }
    long version() { return version; }
}
