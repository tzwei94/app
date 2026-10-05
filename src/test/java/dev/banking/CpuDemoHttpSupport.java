package dev.banking;

import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

abstract class CpuDemoHttpSupport {
    static final java.security.KeyPair KEYS = BankingAcceptanceTest.keys();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("banking.jwt.private-key", () -> "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(KEYS.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        p.add("banking.jwt.issuer", () -> "cpu-test");
        p.add("banking.jwt.audience", () -> "banking-api");
        p.add("banking.token.username", () -> "cpu-test-login");
        p.add("banking.token.password", () -> "synthetic-test-password");
    }

    static String bearer(String subject) throws Exception {
        return "Bearer " + BankingAcceptanceTest.token(subject, "banking-api", "cpu-test",
            java.time.Instant.now().plusSeconds(300), KEYS);
    }

    @TestConfiguration static class NoDatabase {
        // CPU tests cannot access a database, even accidentally.
        @Bean JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:cpu-tests-no-database"));
        }
    }
}
