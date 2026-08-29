package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.session.SignerSessionContract;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SignatureSubmitHttpTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ROSTER_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.now();
    private static final Duration SIGNER_SESSION_MAXIMUM_LIFETIME = Duration.ofHours(2);
    private TrackingRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new TrackingRepository();
        var service = new SignatureSubmitService(
                repository,
                new VersionedCryptoService(Map.of(1, new byte[32]), 1),
                event -> repository.events++);
        mvc = MockMvcBuilders.standaloneSetup(new SignatureSubmitController(
                        service, new SignatureWireParser(), Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new SignatureSubmitAdvice())
                .addFilters(new SignatureSubmitResponseFilter())
                .build();
    }

    @Test
    void submitsThroughThePublicHttpBoundaryWithoutReturningPrivateData() throws Exception {
        // Given
        var session = currentSession();
        var body = "{\"version\":1,\"strokes\":[{\"points\":[{\"x\":12,\"y\":34}]}]}";

        // When / Then
        mvc.perform(post("/api/v1/public/signing-session/signature")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted").value(true))
                .andExpect(jsonPath("$.strokes").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        assertThat(repository.saves).isEqualTo(1);
        assertThat(repository.events).isEqualTo(1);
    }

    @Test
    void rejectsMalformedAndOversizedPayloadsBeforeCryptoOrDatabaseWork() throws Exception {
        // Given
        var malformed = "{\"version\":1,\"strokes\":[],\"private\":\"secret-vector\"}";
        var oversized = " ".repeat(SignatureWireParser.MAX_PAYLOAD_BYTES + 1);

        // When / Then
        for (var body : java.util.List.of(malformed, oversized)) {
            var result = mvc.perform(post("/api/v1/public/signing-session/signature")
                            .session(currentSession())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("signature_invalid"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-vector");
        }
        assertThat(repository.lockCalls).isZero();
        assertThat(repository.saves).isZero();
        assertThat(repository.events).isZero();
    }

    @Test
    void staleStateReturnsOneGenericFailureAndClearsTheSignerSession() throws Exception {
        // Given
        repository.state = repository.state.withRevision(12);
        var session = currentSession();

        // When
        var result = mvc.perform(post("/api/v1/public/signing-session/signature")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"strokes\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("signer_stale"))
                .andReturn();

        // Then
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(BOARD_ID.toString(), SLOT_ID.toString(), "strokes");
        assertThat(session.isInvalid()).isTrue();
        assertThat(repository.saves).isZero();
        assertThat(repository.events).isZero();
    }

    private static MockHttpSession currentSession() {
        var session = new MockHttpSession();
        SignerSessionContract.issue(session, new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 1.5, NOW.minusSeconds(60)), SIGNER_SESSION_MAXIMUM_LIFETIME);
        return session;
    }

    private static final class TrackingRepository implements SignatureSubmissionRepository {
        private LockedState state = new LockedState(
                BOARD_ID, SLOT_ID, ROSTER_ID, "OPEN", 3, "PLACED", 11, 1.5,
                false, false, false, false, null);
        private int lockCalls;
        private int saves;
        private int events;

        @Override
        public <T> T withBoardThenSlotLocked(UUID boardId, UUID slotId, LockedAction<T> action) {
            lockCalls++;
            return action.apply(state, (encrypted, submittedAt) -> saves++);
        }
    }
}
