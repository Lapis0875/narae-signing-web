package com.naraesigning.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HealthAndErrorApiTest.MutationTestController.class)
@ExtendWith(OutputCaptureExtension.class)
class HealthAndErrorApiTest {
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsOnlyServiceStatusWhenHealthIsRequested() throws Exception {
        // Given: application runs with test profile.

        // When: public health endpoint is requested.
        var result = mockMvc.perform(get("/health"));

        // Then: response exposes only service status.
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.*", hasSize(1)));
    }

    @Test
    void returnsStableKoreanErrorWithoutSensitiveInputWhenJsonIsMalformed(CapturedOutput output)
            throws Exception {
        // Given: malformed JSON contains text that must never reach response or logs.
        var sensitiveInput = "SYNTHETIC_PRIVATE_CONFIGURATION_VALUE";

        // When: malformed mutation request crosses real MVC boundary.
        var result = mockMvc.perform(post("/api/v1/test-mutations")
                .header(REQUEST_ID_HEADER, "qa-request-1")
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"" + sensitiveInput + "\""));

        // Then: stable envelope and redacted structured log are observable.
        result.andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID_HEADER, "qa-request-1"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value("요청 본문을 확인해 주세요."))
                .andExpect(jsonPath("$.requestId").value("qa-request-1"))
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString(sensitiveInput))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("stackTrace"))));
        org.assertj.core.api.Assertions.assertThat(output.getAll())
                .contains("INVALID_REQUEST_BODY", "qa-request-1")
                .doesNotContain(
                        sensitiveInput,
                        "\tat ",
                        "application.yml",
                        "master-key-file",
                        "minio-endpoint");
    }

    @Test
    void replacesUntrustedCorrelationIdWhenHeaderIsUnsafe() throws Exception {
        // Given: caller supplies path-like text instead of a safe correlation ID.
        var untrustedId = "../../configuration";

        // When: request enters correlation boundary.
        var result = mockMvc.perform(get("/health").header(REQUEST_ID_HEADER, untrustedId));

        // Then: server emits a fresh opaque UUID and does not reflect input.
        result.andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID_HEADER, not(equalTo(untrustedId))))
                .andExpect(header().string(REQUEST_ID_HEADER,
                        matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
    }

    @RestController
    @RequestMapping("/api/v1/test-mutations")
    static class MutationTestController {
        @PostMapping
        ResponseEntity<Void> mutate(@RequestBody MutationRequest request) {
            return ResponseEntity.noContent().build();
        }
    }

    record MutationRequest(String name) {}
}
