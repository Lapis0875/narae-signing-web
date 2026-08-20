package com.naraesigning.web;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class HealthController {
    private static final Map<String, String> STATUS = Map.of("status", "UP");
    private final ProductionReadiness readiness;

    HealthController(ObjectProvider<ProductionReadiness> readiness) {
        this.readiness = readiness.getIfAvailable();
    }

    @GetMapping("/health")
    ResponseEntity<Map<String, String>> health() {
        return readiness == null || readiness.ready()
                ? ResponseEntity.ok(STATUS)
                : ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }
}
