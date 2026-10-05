package dev.banking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"otel.sdk.disabled=true",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
@Import(CpuDemoHttpSupport.NoDatabase.class)
class CpuDemoDisabledTest extends CpuDemoHttpSupport {
    @Autowired MockMvc http;

    @Test void defaultConfigurationDisablesCpuWorkForAuthenticatedClients() throws Exception {
        var result = http.perform(post("/demo/cpu").header("Authorization", bearer("alice"))
            .contentType("application/json").content("{\"workMs\":50}"))
            .andExpect(request().asyncStarted()).andReturn();
        http.perform(asyncDispatch(result)).andExpect(status().isNotFound())
            .andExpect(jsonPath("code").value("cpu_demo_disabled"));
    }
}
