package com.naraesigning.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class HealthController {
    private static final Map<String, String> STATUS = Map.of("status", "UP");

    @GetMapping("/health")
    Map<String, String> health() {
        return STATUS;
    }
}
