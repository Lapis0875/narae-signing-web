package com.naraesigning.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

final class BodyLimitFilterTest {
    private static final String BOUNDARY = "narae-test-boundary";
    private final BodyLimitFilter filter = new BodyLimitFilter();

    @ParameterizedTest(name = "{0} {1} accepts {2} bytes")
    @MethodSource("routes")
    void parserRuns_whenBodyIsAtLimit(String method, String path, int limit) throws Exception {
        var request = request(method, path, limit);
        var response = new MockHttpServletResponse();
        var parserEntries = new AtomicInteger();

        filter.doFilter(request, response, parser(parserEntries));

        assertThat(parserEntries).hasValue(1);
    }

    @ParameterizedTest(name = "{0} {1} rejects declared {2}+1 bytes")
    @MethodSource("routes")
    void parserDoesNotRun_whenDeclaredBodyExceedsLimit(String method, String path, int limit) throws Exception {
        var request = request(method, path, limit + 1);
        var response = new MockHttpServletResponse();
        var parserEntries = new AtomicInteger();

        filter.doFilter(request, response, parser(parserEntries));

        assertThat(parserEntries).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"request_too_large\"}");
    }

    @ParameterizedTest(name = "{0} {1} rejects chunked {2}+1 bytes")
    @MethodSource("routes")
    void parserDoesNotRun_whenChunkedBodyExceedsLimit(String method, String path, int limit) throws Exception {
        var request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setMethod(method);
        request.setRequestURI(path);
        request.setContent(new byte[limit + 1]);
        request.addHeader("Transfer-Encoding", "chunked");
        var response = new MockHttpServletResponse();
        var parserEntries = new AtomicInteger();

        filter.doFilter(request, response, parser(parserEntries));

        assertThat(parserEntries).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"request_too_large\"}");
    }

    @ParameterizedTest(name = "{0} rejects declared multipart cap+1 before getParts")
    @MethodSource("multipartRoutes")
    void multipartParserDoesNotRun_whenDeclaredBodyExceedsLimit(String path, int limit) throws Exception {
        var request = multipartRequest(path, multipartBody(limit + 1));
        var response = new MockHttpServletResponse();
        var parserEntries = new AtomicInteger();

        filter.doFilter(request, response, partsParser(parserEntries));

        assertThat(parserEntries).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"request_too_large\"}");
    }

    @ParameterizedTest(name = "{0} rejects chunked multipart cap+1 before getParts")
    @MethodSource("multipartRoutes")
    void multipartParserDoesNotRun_whenChunkedBodyExceedsLimit(String path, int limit) throws Exception {
        var request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setMethod("POST");
        request.setRequestURI(path);
        request.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        request.setContent(multipartBody(limit + 1));
        request.addHeader("Transfer-Encoding", "chunked");
        var response = new MockHttpServletResponse();
        var parserEntries = new AtomicInteger();

        filter.doFilter(request, response, partsParser(parserEntries));

        assertThat(parserEntries).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"request_too_large\"}");
    }

    @ParameterizedTest(name = "{0} parses accepted multipart through getParts")
    @MethodSource("multipartRoutes")
    void multipartParserRuns_whenValidBodyIsWithinLimit(String path, int ignoredLimit) throws Exception {
        var body = multipartBody("organization,job,name");
        var request = multipartRequest(path, body);
        var response = new MockHttpServletResponse();
        var parserEntries = new AtomicInteger();

        filter.doFilter(request, response, (raw, ignored) -> {
            var parts = ((HttpServletRequest) raw).getParts();
            assertThat(parts).hasSize(1);
            var file = parts.iterator().next();
            assertThat(file.getName()).isEqualTo("file");
            assertThat(file.getSubmittedFileName()).isEqualTo("roster.csv");
            assertThat(file.getInputStream().readAllBytes()).isEqualTo("organization,job,name".getBytes(StandardCharsets.UTF_8));
            parserEntries.incrementAndGet();
        });

        assertThat(parserEntries).hasValue(1);
    }

    private static MockHttpServletRequest request(String method, String path, int bytes) {
        var request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(path);
        request.setContent(new byte[bytes]);
        return request;
    }

    private static FilterChain parser(AtomicInteger entries) {
        return (request, response) -> {
            request.getInputStream().readAllBytes();
            entries.incrementAndGet();
        };
    }

    private static FilterChain partsParser(AtomicInteger entries) {
        return (request, response) -> {
            ((HttpServletRequest) request).getParts();
            entries.incrementAndGet();
        };
    }

    private static MockHttpServletRequest multipartRequest(String path, byte[] body) {
        var request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(path);
        request.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        request.setContent(body);
        return request;
    }

    private static byte[] multipartBody(String value) {
        return ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"roster.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + value + "\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] multipartBody(int size) {
        var prefix = ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"payload.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var suffix = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var body = new byte[size];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(suffix, 0, body, size - suffix.length, suffix.length);
        return body;
    }

    private static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of("POST", "/api/v1/auth/login", 16_384),
                Arguments.of("POST", "/api/v1/public/links/token/identify", 16_384),
                Arguments.of("POST", "/api/v1/public/signing-session/signature", 1_048_576),
                Arguments.of("PUT", "/api/v1/admin/boards/id/roster", 1_048_576),
                Arguments.of("POST", "/api/v1/admin/boards/id/roster/import", 1_065_000),
                Arguments.of("POST", "/api/v1/admin/boards/id/background", 52_500_000),
                Arguments.of("PATCH", "/api/v1/admin/boards/id", 65_536));
    }

    private static Stream<Arguments> multipartRoutes() {
        return Stream.of(
                Arguments.of("/api/v1/admin/boards/id/roster/import", 1_065_000),
                Arguments.of("/api/v1/admin/boards/id/background", 52_500_000));
    }
}
