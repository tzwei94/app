package dev.banking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"banking.demo.cpu.enabled=true", "otel.sdk.disabled=true",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
@Import(CpuDemoHttpSupport.NoDatabase.class)
class CpuDemoSecurityTest extends CpuDemoHttpSupport {
    @Autowired MockMvc http;

    @Test void ordinaryAuthenticatedSubjectCanRunBoundedWorkWithoutDatabase() throws Exception {
        Thread.sleep(120);
        var result = http.perform(post("/demo/cpu").header("Authorization", bearer("bob"))
            .contentType("application/json").content("{\"workMs\":50}"))
            .andExpect(request().asyncStarted()).andReturn();
        http.perform(asyncDispatch(result)).andExpect(status().isOk())
            .andExpect(jsonPath("workMs").value(50)).andExpect(jsonPath("iterations").isNumber())
            .andExpect(jsonPath("checksum").isString()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void missingAndInvalidJwtCannotStartWork() throws Exception {
        http.perform(post("/demo/cpu").contentType("application/json").content("{\"workMs\":50}"))
            .andExpect(status().isUnauthorized());
        http.perform(post("/demo/cpu").header("Authorization", "Bearer invalid")
            .contentType("application/json").content("{\"workMs\":50}"))
            .andExpect(status().isUnauthorized());
        var expired = "Bearer " + BankingAcceptanceTest.token("bob", "banking-api", "cpu-test",
            java.time.Instant.now().minusSeconds(300), KEYS);
        http.perform(post("/demo/cpu").header("Authorization", expired)
            .contentType("application/json").content("{\"workMs\":50}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void invalidWorkBoundsAreRejectedBeforeAdmission() throws Exception {
        for (var json : new String[]{"{\"workMs\":49}", "{\"workMs\":501}", "{\"workMs\":-1}", "{\"workMs\":2147483648}", "{\"workMs\":50.5}"}) {
            http.perform(post("/demo/cpu").header("Authorization", bearer("alice"))
                .contentType("application/json").content(json)).andExpect(status().isBadRequest());
        }
    }

    @Test void defaultWorkAndBusyResponseAreBoundedWhileLivenessStillResponds() throws Exception {
        Thread.sleep(120);
        var first = http.perform(post("/demo/cpu").header("Authorization", bearer("alice"))
            .contentType("application/json").content("{}"))
            .andExpect(request().asyncStarted()).andReturn();
        var busy = http.perform(post("/demo/cpu").header("Authorization", bearer("alice"))
            .contentType("application/json").content("{\"workMs\":50}"))
            .andExpect(request().asyncStarted()).andReturn();
        http.perform(asyncDispatch(busy)).andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("code").value("cpu_demo_busy"));
        http.perform(get("/livez")).andExpect(status().isOk());
        http.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(jsonPath("workMs").value(250));
        Thread.sleep(120);
        var explicitNull = http.perform(post("/demo/cpu").header("Authorization", bearer("alice"))
            .contentType("application/json").content("{\"workMs\":null}"))
            .andExpect(request().asyncStarted()).andReturn();
        http.perform(asyncDispatch(explicitNull)).andExpect(status().isOk()).andExpect(jsonPath("workMs").value(250));
    }
}
