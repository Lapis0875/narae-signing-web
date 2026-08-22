package com.naraesigning.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.session.AdminSessionContract;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class FinalPngHttpTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID BOARD = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void currentOwnerDownloadsPngWithPrivateHeaders() throws Exception {
        // Given
        var reads = new AtomicInteger();
        var mvc = mvc((owner, board) -> {
            reads.incrementAndGet();
            if (!OWNER.equals(owner)) throw FinalPngException.forbidden();
            return new FinalPngSnapshot(1920, 1080, null, List.of());
        });

        // When / Then
        var result = mvc.perform(get(path()).session(session(OWNER, NOW)).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).startsWith(
                (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(reads).hasValue(1);
    }

    @Test
    void ownerBOpenAndExpiredRequestsFailWithoutProtectedData() throws Exception {
        // Given
        var reads = new AtomicInteger();
        var mvc = mvc((owner, board) -> {
            reads.incrementAndGet();
            if (!OWNER.equals(owner)) throw FinalPngException.forbidden();
            throw FinalPngException.notClosed();
        });

        // When / Then
        mvc.perform(get(path()).session(session(OTHER, NOW)).secure(true))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FINAL_PNG_FORBIDDEN"));
        mvc.perform(get(path()).session(session(OWNER, NOW)).secure(true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FINAL_PNG_NOT_CLOSED"));
        mvc.perform(get(path()).session(session(OWNER,
                        NOW.minus(AdminSessionContract.ABSOLUTE_LIFETIME))).secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(reads).hasValue(2);
    }

    @Test
    void busyResponseCarriesRetryAfter() throws Exception {
        // Given
        var mvc = mvc((owner, board) -> { throw FinalPngException.busy(); });

        // When / Then
        mvc.perform(get(path()).session(session(OWNER, NOW)).secure(true))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("FINAL_PNG_BUSY"));
    }

    private static MockMvc mvc(FinalPngSnapshotRepository repository) {
        var objects = new BackgroundObjectStore() {
            @Override public byte[] get(String objectKey) { throw new AssertionError(); }
            @Override public void put(String objectKey, byte[] ciphertext) { throw new AssertionError(); }
            @Override public void delete(String objectKey) { throw new AssertionError(); }
        };
        var service = new FinalPngService(repository, objects,
                new VersionedCryptoService(Map.of(7, new byte[32]), 7));
        return MockMvcBuilders.standaloneSetup(new FinalPngController(service,
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new FinalPngControllerAdvice())
                .build();
    }

    private static MockHttpSession session(UUID owner, Instant issuedAt) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, owner, issuedAt);
        return session;
    }

    private static String path() {
        return "/api/v1/admin/boards/" + BOARD + "/final.png";
    }
}
