package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.session.SignerSessionContract;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SignatureDraftDeltaHttpTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ROSTER_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    private TrackingRepository repository;
    private LiveSignatureRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        repository = new TrackingRepository();
        var constructor = LiveSignatureRegistry.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var parameterTypes = constructor.getParameterTypes();
        registry = (LiveSignatureRegistry) constructor.newInstance(
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(parameterTypes[2]), mock(parameterTypes[3]));
        var service = new LiveSignatureService(repository, registry);
        mvc = MockMvcBuilders.standaloneSetup(new SignatureDraftController(
                        service, new SignatureWireParser(), Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new SignatureSubmitAdvice())
                .build();
    }

    @Test
    void orderedHttpDeltasAreIdempotentAndGapRequiresFullSync() throws Exception {
        // Given
        var session = currentSession();
        var reset = mvc.perform(put("/api/v1/public/signing-session/draft")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"strokes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftEpoch").value(1))
                .andExpect(jsonPath("$.revision").value(0))
                .andReturn();

        // When
        var begin = delta(session, "begin", 1, 1, 0, 0, "[{\"x\":1,\"y\":2}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn();
        var append = delta(session, "append", 2, 1, 1, 0, "[{\"x\":3,\"y\":4}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn();
        var duplicate = delta(session, "append", 2, 1, 1, 0, "[{\"x\":3,\"y\":4}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andReturn();
        var gap = delta(session, "end", 4, 1, 2, 0, "[]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("signature_draft_out_of_sync"))
                .andReturn();
        var clear = mvc.perform(post("/api/v1/public/signing-session/draft/clear").session(session))
                .andExpect(status().isNoContent())
                .andReturn();

        // Then
        var cleared = registry.snapshot(BOARD_ID, SLOT_ID);
        assertThat(cleared.draftEpoch()).isEqualTo(2);
        assertThat(cleared.revision()).isZero();
        assertThat(cleared.signature().get("strokes")).isEmpty();
        print("full-reset", reset);
        print("begin", begin);
        print("append", append);
        print("duplicate", duplicate);
        print("gap", gap);
        System.out.printf("HTTP clear status=%d draftEpoch=%d revision=%d%n",
                clear.getResponse().getStatus(), cleared.draftEpoch(), cleared.revision());
    }

    @Test
    void malformedDeltaIsRejectedBeforeDatabaseWork() throws Exception {
        // Given
        var session = currentSession();
        var tooManyPoints = "[{\"x\":1,\"y\":1},".repeat(SignatureWireParser.MAX_POINTS)
                + "{\"x\":1,\"y\":1}]";
        var bodies = java.util.List.of(
                deltaBody("jump", 1, 0, 0, 0, "[{\"x\":1,\"y\":2}]"),
                deltaBody("begin", 1, 0, 0, 0, "[{\"x\":-1,\"y\":2}]"),
                deltaBody("begin", 1, 0, 0, 0, tooManyPoints),
                " ".repeat(SignatureWireParser.MAX_PAYLOAD_BYTES + 1));

        // When / Then
        for (var body : bodies) {
            mvc.perform(post("/api/v1/public/signing-session/draft/delta")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("signature_invalid"));
        }
        assertThat(repository.lockCalls).isZero();
    }

    @Test
    void firstAuthorizationCacheMissLocksOnceAndEveryRetryRequiresFullSyncWithoutRelocking() throws Exception {
        // Given
        var session = currentSession();
        var claim = SignerSessionContract.ensureDraftClaimId(session);
        repository.state = withClaim(repository.state, claim, NOW.plusSeconds(90));

        // When / Then
        for (var attempt = 0; attempt < 2; attempt++) {
            delta(session, "begin", 1, 0, 0, 0, "[{\"x\":1,\"y\":2}]")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("signature_draft_out_of_sync"));
        }
        assertThat(repository.lockCalls).isEqualTo(1);
    }

    @Test
    void initialDeltaRequiresFullSyncWithoutExpiringTheSignerSession() throws Exception {
        var session = currentSession();

        delta(session, "begin", 1, 0, 0, 0, "[{\"x\":1,\"y\":2}]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("signature_draft_out_of_sync"));

        assertThat(SignerSessionContract.draftClaimId(session)).isNotNull();
        assertThat(session.isInvalid()).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions delta(
            MockHttpSession session,
            String operation,
            long sequence,
            long epoch,
            long revision,
            int strokeIndex,
            String points) throws Exception {
        return mvc.perform(post("/api/v1/public/signing-session/draft/delta")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(deltaBody(operation, sequence, epoch, revision, strokeIndex, points)));
    }

    private static String deltaBody(
            String operation, long sequence, long epoch, long revision, int strokeIndex, String points) {
        return "{\"operation\":\"" + operation + "\",\"clientSequence\":" + sequence
                + ",\"draftEpoch\":" + epoch + ",\"revision\":" + revision
                + ",\"strokeIndex\":" + strokeIndex + ",\"points\":" + points + "}";
    }

    private static void print(String operation, MvcResult result) throws Exception {
        System.out.printf("HTTP %s status=%d body=%s%n", operation,
                result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private static MockHttpSession currentSession() {
        var session = new MockHttpSession();
        SignerSessionContract.issue(session, new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 1.5, NOW.minusSeconds(60)), Duration.ofHours(2));
        return session;
    }

    private static SignatureSubmissionRepository.LockedState withClaim(
            SignatureSubmissionRepository.LockedState state, UUID claimId, Instant expiresAt) {
        return new SignatureSubmissionRepository.LockedState(
                state.boardId(), state.slotId(), state.rosterEntryId(), state.boardStatus(),
                state.linkVersion(), state.placementStatus(), state.revision(), state.aspect(),
                state.rosterSubmitted(), state.ciphertextPresent(), state.noncePresent(),
                state.keyVersionPresent(), state.submittedAt(), claimId, expiresAt);
    }

    private static final class TrackingRepository implements SignatureSubmissionRepository {
        private LockedState state = new LockedState(
                BOARD_ID, SLOT_ID, ROSTER_ID, "OPEN", 3, "PLACED", 11, 1.5,
                false, false, false, false, null);
        private int lockCalls;

        @Override
        public <T> T withBoardThenSlotLocked(UUID boardId, UUID slotId, LockedAction<T> action) {
            lockCalls++;
            return action.apply(state, new SubmissionWriter() {
                @Override
                public void save(com.naraesigning.crypto.EncryptedValue encrypted, Instant submittedAt) {}

                @Override
                public void renewClaim(UUID claimId, Instant expiresAt) {
                    state = withClaim(state, claimId, expiresAt);
                }
            });
        }
    }
}
