package dev.banking;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BankingAcceptanceTest {
    static final String ACCOUNT="00000000-0000-0000-0000-000000000001";
    static final KeyPair KEYS=keys();
    @Autowired MockMvc http;
    @Autowired JdbcTemplate db;

    static KeyPair keys() { try { var g=KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair(); } catch(Exception e) {throw new IllegalStateException(e);} }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("BANK_TEST_DB_URL"));
        p.add("spring.datasource.username",()->"banking_test");
        p.add("spring.datasource.password",()->System.getenv("BANK_TEST_DB_PASSWORD"));
        p.add("spring.liquibase.enabled",()->true);
        p.add("banking.jwt.private-key",()->"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder().encodeToString(KEYS.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        p.add("banking.token.username",()->"demo-login");
        p.add("banking.token.password",()->"test-only-password");
        p.add("banking.token.subject",()->"alice");
        p.add("banking.jwt.issuer",()->"banking-test");
        p.add("banking.jwt.audience",()->"banking-api");
    }
    static String token(String subject,String audience,String issuer,Instant expires,KeyPair keys)throws Exception {
        var claims=new JWTClaimsSet.Builder().subject(subject).audience(audience).issuer(issuer).issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expires)).build();
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey)keys.getPrivate())); return jwt.serialize();
    }
    static String bearer(String subject)throws Exception {return "Bearer "+token(subject,"banking-api","banking-test",Instant.now().plusSeconds(300),KEYS);}
    @BeforeEach void reset() {
        db.update("DELETE FROM banking_operations"); db.update("DELETE FROM banking_accounts");
        db.update("INSERT INTO banking_accounts(id,owner_subject,balance,currency) VALUES (?,?,100.00,'SGD')",UUID.fromString(ACCOUNT),"alice");
    }
    int mutate(String operation,String amount,String key,String subject)throws Exception {
        return http.perform(post("/accounts/"+ACCOUNT+"/"+operation).header("Authorization",bearer(subject)).header("Idempotency-Key",key).contentType("application/json").content("{\"amount\":"+amount+"}")).andReturn().getResponse().getStatus();
    }
    static String basic(String username,String password) {
        return "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void repeatedAdministrativeMigrationPreservesBalancesAndOperations()throws Exception {
        assertThat(mutate("deposits","23.45","before-migration","alice")).isEqualTo(200);
        var output=java.nio.file.Files.createTempFile("banking-migration-", ".log");
        try {
            for(int attempt=0;attempt<2;attempt++) {
                var command=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",
                    System.getProperty("java.class.path"),BankingApplication.class.getName(),"migrate");
                command.environment().put("DB_URL",System.getenv("BANK_TEST_DB_URL"));
                command.environment().put("DB_USERNAME","banking_test");
                command.environment().put("DB_PASSWORD",System.getenv("BANK_TEST_DB_PASSWORD"));
                command.environment().put("SEED_SYNTHETIC","true");
                var process=command.redirectErrorStream(true).redirectOutput(output.toFile()).start();
                try {
                    assertThat(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    assertThat(process.exitValue()).withFailMessage(java.nio.file.Files.readString(output)).isZero();
                } finally {process.destroyForcibly();}
            }
            assertThat(db.queryForObject("SELECT balance FROM banking_accounts WHERE id=?",java.math.BigDecimal.class,
                UUID.fromString(ACCOUNT))).isEqualByComparingTo("123.45");
            assertThat(db.queryForObject("SELECT count(*) FROM banking_operations",Integer.class)).isEqualTo(1);
            assertThat(mutate("deposits","23.45","before-migration","alice")).isEqualTo(200);
            assertThat(db.queryForObject("SELECT balance FROM banking_accounts WHERE id=?",java.math.BigDecimal.class,
                UUID.fromString(ACCOUNT))).isEqualByComparingTo("123.45");
        } finally {java.nio.file.Files.deleteIfExists(output);}
    }
    @Test void basicLoginIssuesUsableShortLivedToken()throws Exception {
        var response=http.perform(post("/auth/token").header("Authorization",basic("demo-login","test-only-password"))
                .contentType("application/json").content("{\"subject\":\"bob\",\"expires_in\":999999}"))
            .andExpect(status().isOk()).andExpect(jsonPath("token_type").value("Bearer"))
            .andExpect(jsonPath("expires_in").value(900)).andExpect(jsonPath("access_token").isString())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Pragma","no-cache")).andReturn().getResponse();
        var body=com.nimbusds.jose.util.JSONObjectUtils.parse(response.getContentAsString());
        var jwt=SignedJWT.parse((String)body.get("access_token"));
        var claims=jwt.getJWTClaimsSet();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.getIssuer()).isEqualTo("banking-test");
        assertThat(claims.getAudience()).containsExactly("banking-api");
        assertThat(claims.getExpirationTime().getTime()-claims.getIssueTime().getTime()).isEqualTo(900000);
        http.perform(get("/accounts/"+ACCOUNT+"/balance").header("Authorization","Bearer "+jwt.serialize()))
            .andExpect(status().isOk()).andExpect(jsonPath("balance").value(100.00));
    }
    @Test void tokenEndpointRejectsMissingInvalidAndBearerCredentials()throws Exception {
        http.perform(post("/auth/token")).andExpect(status().isUnauthorized());
        for(var auth:new String[]{basic("demo-login","wrong"),basic("wrong","test-only-password"),"Basic invalid!",bearer("alice")})
            http.perform(post("/auth/token").header("Authorization",auth)).andExpect(status().isUnauthorized());
        http.perform(get("/accounts/"+ACCOUNT+"/balance").header("Authorization",basic("demo-login","test-only-password")))
            .andExpect(status().isUnauthorized());
        http.perform(get("/auth/token").header("Authorization",basic("demo-login","test-only-password")))
            .andExpect(status().isForbidden());
    }
    @Test void balanceAndMutationsPersistExactDecimals()throws Exception {
        assertThat(mutate("deposits","0.10","d1","alice")).isEqualTo(200);
        assertThat(mutate("withdrawals","0.20","w1","alice")).isEqualTo(200);
        http.perform(get("/accounts/"+ACCOUNT+"/balance").header("Authorization",bearer("alice"))).andExpect(status().isOk()).andExpect(jsonPath("balance").value(99.90)).andExpect(jsonPath("currency").value("SGD"));
        assertThat(db.queryForObject("SELECT balance FROM banking_accounts",java.math.BigDecimal.class)).isEqualByComparingTo("99.90");
    }
    @Test void retriesReturnOriginalResultWithoutDoubleMutation()throws Exception {
        assertThat(mutate("deposits","10","same","alice")).isEqualTo(200);
        assertThat(mutate("deposits","5","different","alice")).isEqualTo(200);
        http.perform(post("/accounts/"+ACCOUNT+"/deposits").header("Authorization",bearer("alice")).header("Idempotency-Key","same").contentType("application/json").content("{\"amount\":10.00}")).andExpect(status().isOk()).andExpect(jsonPath("balance").value(110.00));
        assertThat(mutate("deposits","11","same","alice")).isEqualTo(409);
        assertThat(mutate("withdrawals","10","same","alice")).isEqualTo(409);
        assertThat(db.queryForObject("SELECT balance FROM banking_accounts",java.math.BigDecimal.class)).isEqualByComparingTo("115.00");
    }
    @Test void rejectsInvalidMoneyAndMissingIdempotency()throws Exception {
        for(var amount:new String[]{"0","-1","0.001","100000000000000000","null","\"abc\""}) assertThat(mutate("deposits",amount,UUID.randomUUID().toString(),"alice")).isEqualTo(400);
        http.perform(post("/accounts/"+ACCOUNT+"/deposits").header("Authorization",bearer("alice")).contentType("application/json").content("{\"amount\":10}")).andExpect(status().isBadRequest());
        assertThat(mutate("deposits","1"," ","alice")).isEqualTo(400);
    }
    @Test void deniesMissingTokenAndOtherAccountOwner()throws Exception {
        http.perform(get("/accounts/"+ACCOUNT+"/balance")).andExpect(status().isUnauthorized());
        http.perform(get("/accounts/"+ACCOUNT+"/balance").header("Authorization",bearer("bob"))).andExpect(status().isNotFound());
        assertThat(mutate("withdrawals","5","x","bob")).isEqualTo(404);
        http.perform(get("/accounts/00000000-0000-0000-0000-000000000099/balance").header("Authorization",bearer("alice"))).andExpect(status().isNotFound());
    }
    @Test void accountListIsOwnedOrderedPaginatedAndUsesSafeFields()throws Exception {
        var second=UUID.fromString("00000000-0000-0000-0000-000000000002");
        var other=UUID.fromString("00000000-0000-0000-0000-000000000003");
        db.update("INSERT INTO banking_accounts(id,owner_subject,balance,currency) VALUES (?,?,200,'SGD')",second,"alice");
        db.update("INSERT INTO banking_accounts(id,owner_subject,balance,currency) VALUES (?,?,300,'SGD')",other,"bob");
        http.perform(get("/accounts/list").header("Authorization",bearer("alice")))
            .andExpect(status().isOk()).andExpect(content().json("[{\"id\":\""+ACCOUNT+"\",\"balance\":100,\"currency\":\"SGD\"},"
                +"{\"id\":\""+second+"\",\"balance\":200,\"currency\":\"SGD\"}]",org.springframework.test.json.JsonCompareMode.STRICT));
        http.perform(get("/accounts/list?limit=1&offset=1").header("Authorization",bearer("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(second.toString()));
        http.perform(get("/accounts/list").header("Authorization",bearer("bob")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(other.toString()));
        http.perform(get("/accounts/list").header("Authorization",bearer("no-accounts")))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        http.perform(get("/accounts/list")).andExpect(status().isUnauthorized());
        for(var query:new String[]{"limit=0","limit=101","offset=-1","limit=abc"})
            http.perform(get("/accounts/list?"+query).header("Authorization",bearer("alice"))).andExpect(status().isBadRequest());
    }
    @Test void validatesSignatureIssuerAudienceAndExpiry()throws Exception {
        var valid=Instant.now().plusSeconds(300);
        for(var t:new String[]{token("alice",null,"banking-test",valid,KEYS),token("alice","wrong","banking-test",valid,KEYS),token("alice","banking-api","wrong",valid,KEYS),token("alice","banking-api","banking-test",Instant.now().minusSeconds(3600),KEYS),token("alice","banking-api","banking-test",valid,keys())})
            http.perform(get("/accounts/"+ACCOUNT+"/balance").header("Authorization","Bearer "+t)).andExpect(status().isUnauthorized());
    }
    @Test void concurrentWithdrawalsCannotOverspend()throws Exception {
        var pool=Executors.newFixedThreadPool(10);
        try {var jobs=IntStream.range(0,10).mapToObj(i->(Callable<Integer>)()->mutate("withdrawals","20","w"+i,"alice")).toList();
            var results=pool.invokeAll(jobs);int ok=0,conflict=0;for(var r:results){int s=r.get();if(s==200)ok++;if(s==409)conflict++;}
            assertThat(ok).isEqualTo(5);assertThat(conflict).isEqualTo(5);
            assertThat(db.queryForObject("SELECT balance FROM banking_accounts",java.math.BigDecimal.class)).isEqualByComparingTo("0");
            assertThat(db.queryForObject("SELECT count(*) FROM banking_operations",Integer.class)).isEqualTo(5);
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentRetryCommitsOnce()throws Exception {
        var pool=Executors.newFixedThreadPool(6);
        try {var results=pool.invokeAll(IntStream.range(0,6).mapToObj(i->(Callable<Integer>)()->mutate("deposits","1","one","alice")).toList());for(var r:results)assertThat(r.get()).isEqualTo(200);
            assertThat(db.queryForObject("SELECT balance FROM banking_accounts",java.math.BigDecimal.class)).isEqualByComparingTo("101");
            assertThat(db.queryForObject("SELECT count(*) FROM banking_operations",Integer.class)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void failedLedgerInsertRollsBackBalance()throws Exception {
        db.execute("ALTER TABLE banking_operations ADD CONSTRAINT reject_probe CHECK (idempotency_key <> 'rollback-probe')");
        try {assertThat(mutate("deposits","7","rollback-probe","alice")).isEqualTo(500);
            assertThat(db.queryForObject("SELECT balance FROM banking_accounts",java.math.BigDecimal.class)).isEqualByComparingTo("100");
        }finally{db.execute("ALTER TABLE banking_operations DROP CONSTRAINT reject_probe");}
    }
    @Test void healthIsMinimalAndVersionAvailable()throws Exception {
        http.perform(get("/readyz")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}"));
        http.perform(get("/version")).andExpect(status().isOk()).andExpect(jsonPath("version").exists());
    }
}
