package com.naraesigning.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.naraesigning.config.PlatformProperties;
import com.naraesigning.security.CsrfContractFilter;
import com.naraesigning.security.CsrfTokenContract;
import com.naraesigning.session.AdminSessionContract;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@ExtendWith(OutputCaptureExtension.class)
final class SafeDiagnosticsHttpTest {
    private static final String BOARD_ID = "11111111-1111-4111-8111-111111111111";
    private static final String CSRF = "TOKEN_SENTINEL";

    @Test
    void preservesSafeCallerRequestId_whenRequestCompletes() throws Exception {
        // Given: the existing correlation boundary and a safe caller identifier.
        var mvc = MockMvcBuilders.standaloneSetup(new DiagnosticController())
                .addFilters(new RequestCorrelationFilter())
                .build();

        // When: a request completes through the real filter chain.
        var response = mvc.perform(get("/diagnostic-baseline").header("X-Request-ID", "baseline-request-1"))
                .andReturn().getResponse();

        // Then: the existing safe correlation contract is unchanged.
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("baseline-request-1");
    }

    @Test
    void emitsOneSafeStructuredRecord_forRepresentativeRequestOutcomes(CapturedOutput output) throws Exception {
        // Given: real request-boundary filters and fixtures containing every prohibited data class.
        var mvc = diagnosticsMvc();
        var validCsrf = new Cookie(CsrfTokenContract.COOKIE_NAME, CSRF);

        // When: representative completion and error paths cross the HTTP surface.
        mvc.perform(withCsrf(post("/api/v1/auth/login")
                        .header("X-Request-ID", "login-request-1")
                        .cookie(new Cookie("ADMIN_SESSION", "COOKIE_SENTINEL"))
                        .header("X-Filename", "FILENAME_SENTINEL")
                        .header("X-Object-Key", "OBJECT_KEY_SENTINEL")
                        .contentType("application/json")
                        .content("{\"credential\":\"CREDENTIAL_SENTINEL\",\"identity\":\"IDENTITY_SENTINEL\"}"),
                        validCsrf))
                .andReturn();
        mvc.perform(get("/api/v1/admin/boards/{boardId}", BOARD_ID)
                        .header("X-Request-ID", "expired-request-1")
                        .session(authenticatedSession()))
                .andReturn();
        mvc.perform(post("/api/v1/auth/login").header("X-Request-ID", "csrf-request-1"))
                .andReturn();
        mvc.perform(post("/api/v1/auth/login")
                        .header("X-Request-ID", "../../HEADER_SENTINEL")
                        .header("X-Narae-Client-IP", "198.51.100.7, RAW_IP_SENTINEL"))
                .andReturn();
        mvc.perform(withCsrf(post("/api/v1/admin/boards/{boardId}/roster", BOARD_ID)
                        .header("X-Request-ID", "roster-request-1"), validCsrf))
                .andReturn();
        mvc.perform(get("/api/v1/test-diagnostics/unexpected")
                        .header("X-Request-ID", "unexpected-request-1"))
                .andReturn();

        // Then: six fresh records contain only the safe diagnostics contract.
        var records = output.getAll().lines()
                .filter(line -> line.contains("requestId=") && line.contains(" route="))
                .map(line -> line.substring(line.indexOf("requestId=")))
                .toList();
        assertThat(records).hasSize(6);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.split(" ")).hasSize(8);
            assertThat(record).matches("requestId=[A-Za-z0-9._-]+ route=/[^ ]+ method=(GET|POST) "
                    + "status=[1-5][0-9]{2} errorCode=[A-Za-z0-9_]+ outcome=(success|error) "
                    + "elapsedMs=[0-9]+ authCategory=(authenticated|anonymous)");
        });
        assertThat(records).anyMatch(record -> record.contains(
                "requestId=login-request-1 route=/api/v1/auth/login method=POST status=200 "
                        + "errorCode=NONE outcome=success") && record.endsWith("authCategory=authenticated"));
        assertThat(records).anyMatch(record -> record.contains(
                "requestId=expired-request-1 route=/api/v1/admin/boards/{boardId} method=GET status=401 "
                        + "errorCode=UNAUTHORIZED outcome=error") && record.endsWith("authCategory=anonymous"));
        assertThat(records).anyMatch(record -> record.contains(
                "requestId=csrf-request-1 route=/api/v1/auth/login method=POST status=403 "
                        + "errorCode=csrf_invalid outcome=error"));
        assertThat(records).anyMatch(record -> record.contains(
                "route=/api/v1/auth/login method=POST status=400 errorCode=INVALID_CLIENT_IP outcome=error"));
        assertThat(records).anyMatch(record -> record.contains(
                "requestId=roster-request-1 route=/api/v1/admin/boards/{boardId}/roster method=POST status=400 "
                        + "errorCode=ROSTER_INVALID outcome=error"));
        assertThat(records).anyMatch(record -> record.contains(
                "requestId=unexpected-request-1 route=/api/v1/test-diagnostics/unexpected method=GET status=500 "
                        + "errorCode=INTERNAL_ERROR outcome=error"));
        assertThat(String.join("\n", records)).doesNotContain(
                "COOKIE_SENTINEL", "CREDENTIAL_SENTINEL", "IDENTITY_SENTINEL", "TOKEN_SENTINEL",
                "FILENAME_SENTINEL", "OBJECT_KEY_SENTINEL", "RAW_IP_SENTINEL", "HEADER_SENTINEL",
                "STACK_TRACE_SENTINEL", "\tat ");
    }

    private static MockMvc diagnosticsMvc() {
        var properties = new PlatformProperties(
                "http://localhost", "http://minio", "bucket", "access", "secret", "/key", 1, "127.0.0.1");
        return MockMvcBuilders.standaloneSetup(new DiagnosticController())
                .setControllerAdvice(new DiagnosticAdvice(), new ApiExceptionHandler())
                .addFilters(
                        new RequestCorrelationFilter(),
                        new TrustedProxyFilter(properties),
                        new CsrfContractFilter(new CsrfTokenContract()))
                .build();
    }

    private static MockHttpServletRequestBuilder withCsrf(
            MockHttpServletRequestBuilder request, Cookie csrf) {
        return request.cookie(csrf).header(CsrfTokenContract.HEADER_NAME, csrf.getValue());
    }

    private static MockHttpSession authenticatedSession() {
        var session = new MockHttpSession();
        AdminSessionContract.issue(
                session,
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                Instant.parse("2026-08-23T00:00:00Z"));
        return session;
    }

    @RestController
    static final class DiagnosticController {
        @GetMapping("/diagnostic-baseline")
        ResponseEntity<Void> baseline() {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/api/v1/auth/login")
        ResponseEntity<Void> login(@RequestBody String ignored, HttpServletRequest request) {
            AdminSessionContract.issue(
                    request.getSession(true),
                    UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                    Instant.parse("2026-08-23T00:00:00Z"));
            return ResponseEntity.ok().build();
        }

        @GetMapping("/api/v1/admin/boards/{boardId}")
        ResponseEntity<Void> expired(@PathVariable UUID boardId, HttpServletRequest request) {
            request.getSession(false).invalidate();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        @PostMapping("/api/v1/admin/boards/{boardId}/roster")
        ResponseEntity<Void> invalidRoster(@PathVariable UUID boardId) {
            throw new RosterRejected();
        }

        @GetMapping("/api/v1/test-diagnostics/unexpected")
        ResponseEntity<Void> unexpected() {
            throw new IllegalStateException("STACK_TRACE_SENTINEL");
        }
    }

    @RestControllerAdvice
    static final class DiagnosticAdvice {
        @ExceptionHandler(RosterRejected.class)
        ResponseEntity<Void> rosterRejected() {
            return ResponseEntity.badRequest().build();
        }
    }

    static final class RosterRejected extends RuntimeException {
    }
}
