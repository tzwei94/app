package dev.banking.common.security;

import dev.banking.user.domain.UserRepository;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class UserTokenValidator implements OAuth2TokenValidator<Jwt> {
    private final UserRepository users;
    UserTokenValidator(UserRepository users) { this.users = users; }
    @Override public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object id = jwt.getClaim("user_id"), version = jwt.getClaim("user_version");
        // Existing bank/admin tokens have no managed-user claims and need no DB lookup.
        if (id == null && version == null) return OAuth2TokenValidatorResult.success();
        if (id instanceof String subject && subject.equals(jwt.getSubject())
                && version instanceof Number number && number.longValue() >= 0) {
            try {
                if (users.validToken(UUID.fromString(subject), number.longValue())) return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException | DataAccessException e) {
                // Invalid identity or unavailable directory fails closed; never expose SQL details.
            }
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
