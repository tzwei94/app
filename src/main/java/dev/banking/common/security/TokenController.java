package dev.banking.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TokenController {
    private static final long LIFETIME_SECONDS = 900;
    private final RSAPrivateCrtKey key;
    private final String issuer;
    private final String audience;
    private final String subject;

    TokenController(RSAPrivateCrtKey key, @Value("${banking.jwt.issuer}") String issuer,
            @Value("${banking.jwt.audience}") String audience, @Value("${banking.token.subject}") String subject) {
        if (issuer.isBlank() || audience.isBlank() || subject.isBlank())
            throw new IllegalArgumentException("JWT issuer, audience and TOKEN_SUBJECT must be nonempty");
        this.key = key;
        this.issuer = issuer;
        this.audience = audience;
        this.subject = subject;
    }

    @PostMapping("/auth/token") ResponseEntity<Map<String, Object>> token() throws JOSEException {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(LIFETIME_SECONDS)))
            .jwtID(UUID.randomUUID().toString()).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache")
            .body(Map.of("access_token", jwt.serialize(), "token_type", "Bearer", "expires_in", LIFETIME_SECONDS));
    }
}
