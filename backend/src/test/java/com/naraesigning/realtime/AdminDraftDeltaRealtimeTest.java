package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.session.AdminSessionContract;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class AdminDraftDeltaRealtimeTest {
    private static final UUID BOARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BOARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SLOT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CLAIM = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ADMIN = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private LiveSignatureRegistry live;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var boards = new BoardRealtimeRegistry(clock);
        live = new LiveSignatureRegistry(new ObjectMapper(), clock, boards, new PublicBoardRealtimeRegistry());
        mvc = MockMvcBuilders.standaloneSetup(new BoardRealtimeController(boards,
                new StaticListableBeanFactory().getBeanProvider(BoardSnapshotService.class))).build();
    }

    @Test
    void acceptedDeltasFlushBodyFreeAdminEventsBeforeAnySnapshotPoll() throws Exception {
        // Given
        var matching = stream(BOARD);
        var unrelated = stream(OTHER_BOARD);
        var version = fullDraft();
        var baseline = matching.getResponse().getContentAsString();
        var unrelatedBaseline = unrelated.getResponse().getContentAsString();
        assertThat(eventCount(baseline)).isOne();
        var packets = List.of(
                delta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, List.of(point(1, 2))),
                delta(DraftDelta.Operation.APPEND, 2, version.draftEpoch(), 1, List.of(point(3, 4))),
                delta(DraftDelta.Operation.END, 3, version.draftEpoch(), 2, List.of()));

        // When / Then
        for (var packet : packets) {
            var result = live.apply(BOARD, SLOT, CLAIM, packet, () -> NOW.plusSeconds(90));
            var body = matching.getResponse().getContentAsString();
            assertThat(eventCount(body))
                    .as("accepted %s must emit before apply returns; no snapshot poll runs", packet.operation())
                    .isEqualTo(1 + packet.clientSequence());
            assertThat(body.lines().filter(line -> line.startsWith("data:")).toList())
                    .hasSize((int) (1 + packet.clientSequence())).containsOnly("data:{}");
            assertThat(body).doesNotContain("points", "slotId", "strokes", SLOT.toString(), CLAIM.toString());
            assertThat(unrelated.getResponse().getContentAsString()).isEqualTo(unrelatedBaseline);
            assertThat(result.duplicate()).isFalse();

            var duplicate = live.apply(BOARD, SLOT, CLAIM, packet, () -> NOW.plusSeconds(90));
            assertThat(duplicate.duplicate()).isTrue();
            assertThat(matching.getResponse().getContentAsString()).isEqualTo(body);
        }
    }

    @Test
    void invalidDeltasLeaveAdminStreamUnchanged() throws Exception {
        // Given
        var matching = stream(BOARD);
        var version = fullDraft();
        var baseline = matching.getResponse().getContentAsString();

        // When / Then
        assertThatThrownBy(() -> live.apply(BOARD, SLOT, CLAIM,
                delta(DraftDelta.Operation.BEGIN, 2, version.draftEpoch(), 0, List.of(point(1, 2))),
                () -> NOW.plusSeconds(90))).isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
        assertThat(matching.getResponse().getContentAsString()).isEqualTo(baseline);
        assertThatThrownBy(() -> live.apply(BOARD, SLOT, CLAIM,
                delta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, List.of(point(-1, 2))),
                () -> NOW.plusSeconds(90))).isInstanceOf(LiveSignatureRegistry.InvalidDeltaException.class);
        assertThat(matching.getResponse().getContentAsString()).isEqualTo(baseline);
    }

    private MvcResult stream(UUID boardId) throws Exception {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, ADMIN, NOW);
        return mvc.perform(get("/api/v1/admin/boards/" + boardId + "/events").session(session))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    }

    private LiveSignatureRegistry.Version fullDraft() {
        return live.update(BOARD, SLOT, CLAIM,
                "{\"version\":1,\"strokes\":[]}".getBytes(StandardCharsets.UTF_8), NOW.plusSeconds(90));
    }

    private static DraftDelta delta(DraftDelta.Operation operation, long sequence, long epoch,
            long revision, List<DraftDelta.Point> points) {
        return new DraftDelta(operation, sequence, epoch, revision, 0, points);
    }

    private static DraftDelta.Point point(int x, int y) { return new DraftDelta.Point(x, y); }

    private static long eventCount(String body) {
        return body.lines().filter("event:signature-draft"::equals).count();
    }
}
