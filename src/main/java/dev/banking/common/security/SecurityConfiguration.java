package dev.banking.common.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfiguration {
    @Bean RSAPrivateCrtKey signingKey(@Value("${banking.jwt.private-key}") String pem) throws Exception {
        var raw = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(raw)));
        if (key.getModulus().bitLength() < 2048) throw new IllegalArgumentException("JWT_PRIVATE_KEY must be at least 2048-bit RSA");
        return key;
    }
    @Bean JwtDecoder jwtDecoder(RSAPrivateCrtKey signingKey,
            @Value("${banking.jwt.issuer}") String issuer, @Value("${banking.jwt.audience}") String audience) throws Exception {
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(signingKey.getModulus(), signingKey.getPublicExponent()));
        var decoder = NimbusJwtDecoder.withPublicKey(key).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                && jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }
    @Bean @Order(1) SecurityFilterChain tokenSecurity(HttpSecurity http,
            @Value("${banking.token.username}") String username, @Value("${banking.token.password}") String password) throws Exception {
        if (username.isBlank() || username.contains(":") || password.isBlank())
            throw new IllegalArgumentException("Configure nonempty TOKEN_USERNAME (without colon) and TOKEN_PASSWORD");
        var encoder = new BCryptPasswordEncoder();
        var users = new InMemoryUserDetailsManager(User.withUsername(username).password(encoder.encode(password)).roles("TOKEN").build());
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return http.securityMatcher("/auth/token").csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.disable())
            .authenticationManager(new ProviderManager(provider))
            .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, "/auth/token").hasRole("TOKEN").anyRequest().denyAll())
            .httpBasic(b -> b.realmName("banking-token")).build();
    }
    @Bean @Order(2) SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/readyz", "/livez", "/version", "/actuator/health", "/actuator/prometheus").permitAll().anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
    }
}
