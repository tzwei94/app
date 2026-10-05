package dev.banking;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "otel.sdk.disabled=true")
@AutoConfigureMockMvc
class UserManagementAcceptanceTest {
    static final String PASSWORD = "synthetic-user-password";
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        BankingAcceptanceTest.properties(p);
    }
    @BeforeEach void reset() {
        db.update("DELETE FROM banking_operations");
        db.update("DELETE FROM banking_accounts");
        // Allows the first red test to reach the missing endpoint before changeset 002 exists.
        if (db.queryForObject("SELECT to_regclass('banking_users')", String.class) != null)
            db.update("DELETE FROM banking_users");
    }
    String login(String username, String password) throws Exception {
        var response = http.perform(post("/auth/token")
            .header("Authorization", BankingAcceptanceTest.basic(username, password)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse();
        return (String) JSONObjectUtils.parse(response.getContentAsString()).get("access_token");
    }
    String admin() throws Exception { return "Bearer " + login("demo-login", "test-only-password"); }
    String user(String username) throws Exception { return "Bearer " + login(username, PASSWORD); }
    UUID create(String username) throws Exception {
        var response = http.perform(post("/users").header("Authorization", admin())
            .contentType("application/json").content("{\"username\":\"" + username
                + "\",\"displayName\":\"Test User\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("password").doesNotExist()).andExpect(jsonPath("passwordHash").doesNotExist())
            .andReturn().getResponse();
        var id = UUID.fromString((String) JSONObjectUtils.parse(response.getContentAsString()).get("id"));
        assertThat(response.getHeader("Location")).isEqualTo("/users/" + id);
        return id;
    }

    @Test void adminCanCreateReadUpdateListAndDeleteUsers() throws Exception {
        var id = create("alice-user");
        create("bob-user");
        http.perform(get("/users").header("Authorization", admin()))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].username").value("alice-user"))
            .andExpect(jsonPath("$[1].username").value("bob-user"));
        http.perform(get("/users/" + id).header("Authorization", admin()))
            .andExpect(status().isOk()).andExpect(content().json("{\"id\":\"" + id
                + "\",\"username\":\"alice-user\",\"displayName\":\"Test User\"}",
                org.springframework.test.json.JsonCompareMode.STRICT));
        http.perform(put("/users/" + id).header("Authorization", admin()).contentType("application/json")
            .content("{\"username\":\"alice-renamed\",\"displayName\":\"Alice Updated\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("id").value(id.toString()))
            .andExpect(jsonPath("displayName").value("Alice Updated"));
        var token = login("alice-renamed", PASSWORD);
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getSubject()).isEqualTo(id.toString());
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getClaim("scope")).isNull();
        assertThat(db.queryForObject("SELECT password_hash FROM banking_users WHERE id=?", String.class, id))
            .startsWith("$2").doesNotContain(PASSWORD);
        http.perform(delete("/users/" + id).header("Authorization", admin())).andExpect(status().isNoContent());
        http.perform(get("/users/" + id).header("Authorization", admin())).andExpect(status().isNotFound());
        http.perform(get("/users").header("Authorization", admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        http.perform(delete("/users/" + id).header("Authorization", admin())).andExpect(status().isNotFound());
    }

    @Test void ordinaryLegacyAndAnonymousCallersCannotManageUsers() throws Exception {
        var id = create("ordinary-user");
        for (var authorization : new String[]{user("ordinary-user"), BankingAcceptanceTest.bearer("alice")}) {
            http.perform(get("/users").header("Authorization", authorization)).andExpect(status().isForbidden());
            http.perform(get("/users/" + id).header("Authorization", authorization)).andExpect(status().isForbidden());
            http.perform(post("/users").header("Authorization", authorization)).andExpect(status().isForbidden());
            http.perform(put("/users/" + id).header("Authorization", authorization)).andExpect(status().isForbidden());
            http.perform(delete("/users/" + id).header("Authorization", authorization)).andExpect(status().isForbidden());
        }
        http.perform(get("/users")).andExpect(status().isUnauthorized());
        http.perform(get("/users").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        http.perform(post("/auth/token").header("Authorization", BankingAcceptanceTest.basic("ordinary-user", "wrong")))
            .andExpect(status().isUnauthorized());
    }

    @Test void passwordChangeAndDeletionRevokeTokensButPreserveAccountHistory() throws Exception {
        var id = create("account-owner");
        var account = UUID.randomUUID();
        db.update("INSERT INTO banking_accounts(id,owner_subject,balance,currency) VALUES (?,?,125.00,'SGD')",
            account, id.toString());
        db.update("INSERT INTO banking_operations(account_id,idempotency_key,kind,amount,balance_after) VALUES (?,'historical','deposit',25,125)", account);
        var original = user("account-owner");
        http.perform(get("/accounts/" + account + "/balance").header("Authorization", original))
            .andExpect(status().isOk()).andExpect(jsonPath("balance").value(125));
        var other = create("other-owner");
        http.perform(get("/accounts/" + account + "/balance").header("Authorization", user("other-owner")))
            .andExpect(status().isNotFound());
        assertThat(other).isNotEqualTo(id);
        http.perform(put("/users/" + id).header("Authorization", admin()).contentType("application/json")
            .content("{\"username\":\"renamed-owner\",\"displayName\":\"Renamed\"}"))
            .andExpect(status().isOk());
        http.perform(get("/accounts/" + account + "/balance").header("Authorization", original)).andExpect(status().isOk());
        http.perform(put("/users/" + id).header("Authorization", admin()).contentType("application/json")
            .content("{\"username\":\"renamed-owner\",\"displayName\":\"Renamed\",\"password\":\"replacement-password\"}"))
            .andExpect(status().isOk());
        http.perform(get("/accounts/" + account + "/balance").header("Authorization", original)).andExpect(status().isUnauthorized());
        http.perform(post("/auth/token").header("Authorization", BankingAcceptanceTest.basic("renamed-owner", PASSWORD)))
            .andExpect(status().isUnauthorized());
        var replacement = "Bearer " + login("renamed-owner", "replacement-password");
        http.perform(get("/accounts/" + account + "/balance").header("Authorization", replacement)).andExpect(status().isOk());
        http.perform(delete("/users/" + id).header("Authorization", admin())).andExpect(status().isNoContent());
        http.perform(get("/accounts/" + account + "/balance").header("Authorization", replacement)).andExpect(status().isUnauthorized());
        http.perform(post("/auth/token").header("Authorization", BankingAcceptanceTest.basic("renamed-owner", "replacement-password")))
            .andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT balance FROM banking_accounts WHERE id=?", java.math.BigDecimal.class, account))
            .isEqualByComparingTo("125");
        assertThat(db.queryForObject("SELECT count(*) FROM banking_operations WHERE account_id=?", Integer.class, account)).isEqualTo(1);
    }

    @Test void listsAreOrderedPaginatedAndEmptyRatherThanNotFound() throws Exception {
        http.perform(get("/users").header("Authorization", admin())).andExpect(status().isOk()).andExpect(content().json("[]"));
        create("charlie-user"); create("alice-user"); create("bob-user");
        http.perform(get("/users?limit=1&offset=1").header("Authorization", admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].username").value("bob-user"));
        http.perform(get("/users?offset=99").header("Authorization", admin())).andExpect(status().isOk()).andExpect(content().json("[]"));
        for (var query : new String[]{"limit=0", "limit=101", "offset=-1", "limit=abc", "offset=2147483648"})
            http.perform(get("/users?" + query).header("Authorization", admin())).andExpect(status().isBadRequest());
    }

    @Test void duplicateReservedInvalidAndDeletedNamesAreRejected() throws Exception {
        var id = create("unique-user");
        var other = create("other-user");
        for (var username : new String[]{"unique-user", "demo-login"})
            http.perform(post("/users").header("Authorization", admin()).contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"displayName\":\"Test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict());
        http.perform(put("/users/" + other).header("Authorization", admin()).contentType("application/json")
            .content("{\"username\":\"unique-user\",\"displayName\":\"Test\"}"))
            .andExpect(status().isConflict());
        for (var json : new String[]{"{}", "{\"username\":\"UPPER\",\"displayName\":\"Test\",\"password\":\"" + PASSWORD + "\"}",
                "{\"username\":\"valid-user\",\"displayName\":\" \",\"password\":\"" + PASSWORD + "\"}",
                "{\"username\":\"valid-user\",\"displayName\":\"Test\",\"password\":\"short\"}",
                "{\"username\":\"valid-user\",\"displayName\":\"Test\",\"password\":\"" + "密".repeat(30) + "\"}"})
            http.perform(post("/users").header("Authorization", admin()).contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
        http.perform(delete("/users/" + id).header("Authorization", admin())).andExpect(status().isNoContent());
        http.perform(post("/users").header("Authorization", admin()).contentType("application/json")
            .content("{\"username\":\"unique-user\",\"displayName\":\"Test\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isConflict());
        http.perform(get("/users/" + UUID.randomUUID()).header("Authorization", admin())).andExpect(status().isNotFound());
        http.perform(get("/users/not-a-uuid").header("Authorization", admin())).andExpect(status().isBadRequest());
    }

    @Test void simultaneousDuplicateCreationCommitsExactlyOneUser() throws Exception {
        var authorization = admin();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, 2)
                .mapToObj(i -> (java.util.concurrent.Callable<Integer>) () -> http.perform(post("/users")
                    .header("Authorization", authorization).contentType("application/json")
                    .content("{\"username\":\"concurrent-user\",\"displayName\":\"Concurrent\",\"password\":\"" + PASSWORD + "\"}"))
                    .andReturn().getResponse().getStatus()).toList());
            assertThat(java.util.List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(201, 409);
            assertThat(db.queryForObject("SELECT count(*) FROM banking_users WHERE username='concurrent-user'", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
