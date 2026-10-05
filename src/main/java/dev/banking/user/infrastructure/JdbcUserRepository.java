package dev.banking.user.infrastructure;

import dev.banking.user.domain.UserProfile;
import dev.banking.user.domain.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {
    private static final RowMapper<UserProfile> PROFILE = (r, n) ->
        new UserProfile(r.getObject("id", UUID.class), r.getString("username"), r.getString("display_name"));
    private final JdbcTemplate db;
    public JdbcUserRepository(JdbcTemplate db) { this.db = db; }

    @Override public UserProfile create(UUID id, String username, String displayName, String passwordHash) {
        db.update("INSERT INTO banking_users(id,username,display_name,password_hash) VALUES (?,?,?,?)",
            id, username, displayName, passwordHash);
        return new UserProfile(id, username, displayName);
    }
    @Override public List<UserProfile> list(int limit, int offset) {
        return db.query("SELECT id,username,display_name FROM banking_users WHERE deleted=false ORDER BY username,id LIMIT ? OFFSET ?",
            PROFILE, limit, offset);
    }
    @Override public Optional<UserProfile> find(UUID id) {
        return db.query("SELECT id,username,display_name FROM banking_users WHERE id=? AND deleted=false", PROFILE, id)
            .stream().findFirst();
    }
    @Override public Optional<UserProfile> update(UUID id, String username, String displayName, String passwordHash) {
        return db.query("UPDATE banking_users SET username=?,display_name=?,password_hash=COALESCE(?,password_hash),"
            + "token_version=token_version+? WHERE id=? AND deleted=false RETURNING id,username,display_name",
            PROFILE, username, displayName, passwordHash, passwordHash == null ? 0 : 1, id).stream().findFirst();
    }
    @Override public boolean delete(UUID id) {
        // Keep the identity and reserved username; never remove accounts or their ledger.
        return db.update("UPDATE banking_users SET deleted=true,token_version=token_version+1 WHERE id=? AND deleted=false", id) == 1;
    }
    @Override public Optional<Credentials> credentials(String username) {
        return db.query("SELECT id,username,password_hash,token_version FROM banking_users WHERE username=? AND deleted=false",
            (r, n) -> new Credentials(r.getObject("id", UUID.class), r.getString("username"), r.getString("password_hash"),
                r.getLong("token_version")), username).stream().findFirst();
    }
    @Override public boolean validToken(UUID id, long version) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM banking_users WHERE id=? AND token_version=? AND deleted=false)",
            Boolean.class, id, version));
    }
}
