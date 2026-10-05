package dev.banking.user.application;

import dev.banking.account.domain.BankingFailure;
import dev.banking.user.domain.UserProfile;
import dev.banking.user.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String administrator;
    public UserService(UserRepository users, PasswordEncoder encoder, @Value("${banking.token.username}") String administrator) {
        this.users = users; this.encoder = encoder; this.administrator = administrator;
    }
    private void checkUsername(String username) {
        if (username.equalsIgnoreCase(administrator)) throw new BankingFailure(409, "username_reserved");
    }
    private String passwordHash(String password) {
        if (password.isBlank() || password.length() < 12 || password.length() > 72
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BankingFailure(400, "invalid_request");
        return encoder.encode(password);
    }
    public UserProfile create(String username, String displayName, String password) {
        checkUsername(username);
        var hash = passwordHash(password);
        try { return users.create(UUID.randomUUID(), username, displayName, hash); }
        catch (DuplicateKeyException e) { throw new BankingFailure(409, "username_exists"); }
    }
    public List<UserProfile> list(int limit, int offset) { return users.list(limit, offset); }
    public UserProfile get(UUID id) { return users.find(id).orElseThrow(() -> new BankingFailure(404, "user_not_found")); }
    public UserProfile update(UUID id, String username, String displayName, String password) {
        checkUsername(username);
        var hash = password == null ? null : passwordHash(password);
        try { return users.update(id, username, displayName, hash).orElseThrow(() -> new BankingFailure(404, "user_not_found")); }
        catch (DuplicateKeyException e) { throw new BankingFailure(409, "username_exists"); }
    }
    public void delete(UUID id) {
        if (!users.delete(id)) throw new BankingFailure(404, "user_not_found");
    }
}
