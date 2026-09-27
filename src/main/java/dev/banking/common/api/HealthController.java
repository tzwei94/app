package dev.banking.common.api;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class HealthController {
    private final JdbcTemplate db;
    private final ObjectProvider<BuildProperties> build;
    HealthController(JdbcTemplate db, ObjectProvider<BuildProperties> build) { this.db = db; this.build = build; }
    @GetMapping("/readyz") ResponseEntity<Map<String, String>> ready() {
        try { db.queryForObject("SELECT 1", Integer.class); return ResponseEntity.ok(Map.of("status", "UP")); }
        catch (Exception e) { return ResponseEntity.status(503).body(Map.of("status", "DOWN")); }
    }
    @GetMapping("/livez") Map<String, String> live() { return Map.of("status", "UP"); }
    @GetMapping("/version") Map<String, String> version() {
        return Map.of("version", build.getIfAvailable() == null ? "dev" : build.getObject().getVersion(), "source", System.getenv().getOrDefault("SOURCE_SHA", "local"));
    }
}
