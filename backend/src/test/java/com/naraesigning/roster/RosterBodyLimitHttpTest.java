package com.naraesigning.roster;

import static org.assertj.core.api.Assertions.assertThat;

import com.naraesigning.NaraeSigningApplication;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = NaraeSigningApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(RosterBodyLimitHttpTest.Routes.class)
final class RosterBodyLimitHttpTest {
    private static final String PUT_PATH = "/api/v1/admin/boards/id/roster";
    private static final String IMPORT_PATH = "/api/v1/admin/boards/id/roster/import";
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort private int port;
    @org.springframework.beans.factory.annotation.Autowired private Counter counter;

    @BeforeEach
    void reset() { counter.value.set(0); }

    @Test
    void acceptsInclusiveRawBodyLimit_thenRejectsLimitPlusOneBeforeParser() throws Exception {
        var accepted = sendPut(HttpRequest.BodyPublishers.ofByteArray(new byte[1_048_576]));
        assertThat(accepted.statusCode()).isEqualTo(204);
        assertThat(counter.value).hasValue(1);
        counter.value.set(0);

        var rejected = sendPut(HttpRequest.BodyPublishers.ofByteArray(new byte[1_048_577]));
        assertTooLarge(rejected);
        assertThat(counter.value).hasValue(0);
    }

    @Test
    void rejectsChunkedMultipartCapPlusOneBeforeParser() throws Exception {
        var acceptedRequest = HttpRequest.newBuilder(uri(IMPORT_PATH))
                .header("Content-Type", "multipart/form-data; boundary=roster-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(1_065_000))).build();
        var accepted = client.send(acceptedRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(204);
        assertThat(counter.value).hasValue(1);
        counter.value.set(0);

        var body = multipartBody(1_065_001);
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
        var request = HttpRequest.newBuilder(uri(IMPORT_PATH))
                .header("Content-Type", "multipart/form-data; boundary=roster-boundary")
                .POST(publisher).build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertTooLarge(response);
        assertThat(counter.value).hasValue(0);
        System.out.println("QA roster_oversize_preparser=true multipart_cap=1065000");
    }

    private HttpResponse<String> sendPut(HttpRequest.BodyPublisher body) throws Exception {
        var request = HttpRequest.newBuilder(uri(PUT_PATH))
                .header("Content-Type", "application/json").PUT(body).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private static void assertTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).isEqualTo("{\"code\":\"request_too_large\"}");
    }

    private static byte[] multipartBody(int size) {
        var prefix = ("--roster-boundary\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"roster.csv\"\r\nContent-Type: text/csv\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var suffix = "\r\n--roster-boundary--\r\n".getBytes(StandardCharsets.UTF_8);
        var body = new byte[size];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(suffix, 0, body, size - suffix.length, suffix.length);
        return body;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Routes {
        @Bean Counter counter() { return new Counter(); }
        @Bean LimitRoute route(Counter counter) { return new LimitRoute(counter); }
    }

    static final class Counter { private final AtomicInteger value = new AtomicInteger(); }

    @RestController
    static final class LimitRoute {
        private final Counter counter;
        LimitRoute(Counter counter) { this.counter = counter; }
        @PutMapping(PUT_PATH)
        ResponseEntity<Void> put(@RequestBody byte[] body) {
            counter.value.incrementAndGet();
            return ResponseEntity.noContent().build();
        }
        @PostMapping(IMPORT_PATH)
        ResponseEntity<Void> post() {
            counter.value.incrementAndGet();
            return ResponseEntity.noContent().build();
        }
    }
}
