package com.naraesigning.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.naraesigning.NaraeSigningApplication;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest(classes = NaraeSigningApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MultipartBodyLimitHttpTest.RoutesConfiguration.class)
final class MultipartBodyLimitHttpTest {
    private static final String BOUNDARY = "narae-http-boundary";
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @org.springframework.beans.factory.annotation.Autowired private ParserCounter parserCounter;

    @BeforeEach
    void resetCounter() { parserCounter.entries.set(0); }

    @ParameterizedTest(name = "{0} accepts valid multipart through Spring MultipartFile")
    @MethodSource("routes")
    void parserRuns_whenValidMultipartIsWithinLimit(String path, int ignoredLimit) throws Exception {
        var response = send(path, HttpRequest.BodyPublishers.ofByteArray(multipartBody(256)));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());
        assertThat(parserCounter.entries).hasValue(1);
    }

    @ParameterizedTest(name = "{0} rejects declared multipart cap+1 before Spring parser")
    @MethodSource("routes")
    void parserDoesNotRun_whenDeclaredMultipartExceedsLimit(String path, int limit) throws Exception {
        var response = send(path, HttpRequest.BodyPublishers.ofByteArray(multipartBody(limit + 1)));

        assertTooLarge(response);
        assertThat(parserCounter.entries).hasValue(0);
    }

    @ParameterizedTest(name = "{0} rejects chunked multipart cap+1 before Spring parser")
    @MethodSource("routes")
    void parserDoesNotRun_whenChunkedMultipartExceedsLimit(String path, int limit) throws Exception {
        var body = multipartBody(limit + 1);
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));

        assertThat(publisher.contentLength()).isEqualTo(-1);
        var response = send(path, publisher);

        assertTooLarge(response);
        assertThat(parserCounter.entries).hasValue(0);
    }

    private HttpResponse<String> send(String path, HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(body)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).isEqualTo("{\"code\":\"request_too_large\"}");
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
                Arguments.of("/api/v1/admin/boards/id/roster/import", 1_065_000),
                Arguments.of("/api/v1/admin/boards/id/background", 52_500_000));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RoutesConfiguration {
        @Bean ParserCounter parserCounter() { return new ParserCounter(); }
        @Bean MultipartRoutes multipartRoutes(ParserCounter counter) { return new MultipartRoutes(counter); }
    }

    static final class ParserCounter { private final AtomicInteger entries = new AtomicInteger(); }

    @RestController
    static final class MultipartRoutes {
        private final ParserCounter counter;
        MultipartRoutes(ParserCounter counter) { this.counter = counter; }

        @PostMapping(
                path = {"/api/v1/admin/boards/{boardId}/roster/import", "/api/v1/admin/boards/{boardId}/background"},
                consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        ResponseEntity<Void> parse(@RequestPart("file") MultipartFile file) {
            if (!file.isEmpty()) counter.entries.incrementAndGet();
            return ResponseEntity.noContent().build();
        }
    }
}
