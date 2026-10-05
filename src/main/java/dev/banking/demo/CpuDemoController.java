package dev.banking.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
public final class CpuDemoController {
    private final CpuDemoWorker worker;
    private final boolean enabled;

    public CpuDemoController(CpuDemoWorker worker, @Value("${banking.demo.cpu.enabled:false}") boolean enabled) {
        this.worker = worker;
        this.enabled = enabled;
    }

    @PostMapping("/demo/cpu")
    public DeferredResult<ResponseEntity<?>> cpu(@Valid @RequestBody WorkRequest request) {
        var response = new DeferredResult<ResponseEntity<?>>(1000L);
        if (!enabled) {
            response.setResult(error(404, "cpu_demo_disabled"));
            return response;
        }
        final CpuDemoWorker.Job job;
        try { job = worker.submit(request.workMs() == null ? 250 : request.workMs().intValueExact()); }
        catch (CpuDemoWorker.Unavailable e) {
            response.setResult(ResponseEntity.status(e.closed() ? 503 : 429).header("Cache-Control", "no-store")
                .header("Retry-After", "1").body(Map.of("code", e.closed() ? "cpu_demo_unavailable" : "cpu_demo_busy")));
            return response;
        }
        response.onTimeout(() -> {
            job.cancel();
            response.setResult(error(503, "cpu_demo_timeout"));
        });
        response.onError(error -> job.cancel());
        response.onCompletion(job::cancel);
        job.result().whenComplete((result, failure) -> {
            if (failure == null) response.setResult(ResponseEntity.ok().header("Cache-Control", "no-store").body(result));
            else response.setResult(error(503, "cpu_demo_cancelled"));
        });
        return response;
    }

    private static ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(Map.of("code", code));
    }

    public record WorkRequest(@DecimalMin("50") @DecimalMax("500") @Digits(integer = 3, fraction = 0) BigDecimal workMs) {}
}
