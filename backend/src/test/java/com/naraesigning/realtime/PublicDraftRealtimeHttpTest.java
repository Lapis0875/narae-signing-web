package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.PublicBoardLink;
import com.naraesigning.board.core.SignatureInkColor;
import com.naraesigning.session.AdminSessionContract;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class PublicDraftRealtimeHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID BOARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BOARD = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SLOT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CLAIM = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String TOKEN = "valid-share-token";
    private static final String OTHER_TOKEN = "other-share-token";
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private BoardRealtimeRegistry adminRealtime;
    private PublicBoardRealtimeRegistry publicRealtime;
    private LiveSignatureRegistry live;
    private BoardService boards;
    private MockMvc publicMvc;
    private MockMvc adminMvc;
    private Cookie owner;

    @BeforeEach
    void setUp() {
        adminRealtime = new BoardRealtimeRegistry(Clock.fixed(NOW, ZoneOffset.UTC));
        publicRealtime = new PublicBoardRealtimeRegistry();
        live = new LiveSignatureRegistry(JSON, Clock.fixed(NOW, ZoneOffset.UTC), adminRealtime, publicRealtime);

        boards = mock(BoardService.class);
        var snapshots = mock(BoardSnapshotService.class);
        when(boards.findPublic(TOKEN)).thenReturn(Optional.of(new PublicBoardLink(BOARD, "Board", "OPEN", 1)));
        when(boards.findPublic(OTHER_TOKEN)).thenReturn(Optional.of(
                new PublicBoardLink(OTHER_BOARD, "Other board", "OPEN", 1)));
        when(snapshots.publiclyVisible(BOARD)).thenReturn(true);
        when(snapshots.publiclyVisible(OTHER_BOARD)).thenReturn(true);
        when(snapshots.readPublic(BOARD)).thenAnswer(invocation -> snapshot());
        publicMvc = MockMvcBuilders.standaloneSetup(new PublicBoardDisplayController(
                boards, snapshots, mock(BackgroundAssetService.class), publicRealtime,
                new PublicDisplayLeaseRegistry(Clock.fixed(NOW, ZoneOffset.UTC)))).build();

        @SuppressWarnings("unchecked")
        var snapshotProvider = (ObjectProvider<BoardSnapshotService>) mock(ObjectProvider.class);
        adminMvc = MockMvcBuilders.standaloneSetup(
                new BoardRealtimeController(adminRealtime, snapshotProvider)).build();
    }

    @Test
    void publicStreamGetsContiguousJsonDeltasWhileAdminGetsBodyFreeInvalidations() throws Exception {
        // Given
        var publicStream = publicStream();
        var adminStream = adminStream();
        var version = live.update(BOARD, SLOT, CLAIM, bytes("{\"version\":1,\"strokes\":[]}"),
                NOW.plusSeconds(90));

        // When
        live.apply(BOARD, SLOT, CLAIM, delta(DraftDelta.Operation.BEGIN, 1, version, 0, point(1, 2)),
                () -> NOW.plusSeconds(90));
        live.apply(BOARD, SLOT, CLAIM, delta(DraftDelta.Operation.APPEND, 2,
                new LiveSignatureRegistry.Version(version.draftEpoch(), 1), 0, point(3, 4)),
                () -> NOW.plusSeconds(90));
        live.apply(BOARD, SLOT, CLAIM, delta(DraftDelta.Operation.END, 3,
                new LiveSignatureRegistry.Version(version.draftEpoch(), 2), 0),
                () -> NOW.plusSeconds(90));

        // Then
        var publicBody = publicStream.getResponse().getContentAsString();
        assertThat(publicBody)
                .containsSubsequence("\"revision\":0", "\"revision\":1", "\"revision\":2", "\"revision\":3")
                .contains("\"slotId\":\"" + SLOT + "\"",
                        "\"operation\":\"full-reset\"",
                        "\"operation\":\"begin\"",
                        "\"strokeIndex\":0",
                        "\"points\":[{\"x\":1,\"y\":2}]",
                        "\"operation\":\"append\"",
                        "\"points\":[{\"x\":3,\"y\":4}]",
                        "\"operation\":\"end\"");
        var adminBody = adminStream.getResponse().getContentAsString();
        assertThat(adminBody).contains("event:signature-draft", "data:{}").doesNotContain("points");
        assertThat(occurrences(adminBody, "event:signature-draft")).isEqualTo(4);
        System.out.println("PUBLIC_SSE\n" + publicBody + "ADMIN_SSE\n" + adminBody);
    }

    @Test
    void publicEventIdsStayContiguousPerBoardWhenPublicationsInterleave() throws Exception {
        // Given
        var boardA = publicStream(TOKEN);
        var boardB = publicStream(OTHER_TOKEN);

        // When
        publicRealtime.publish(BOARD, "board-updated");
        publicRealtime.publish(OTHER_BOARD, "board-updated");
        publicRealtime.publish(BOARD, "layout-updated");

        // Then
        var boardABody = boardA.getResponse().getContentAsString();
        var boardBBody = boardB.getResponse().getContentAsString();
        assertThat(boardABody)
                .containsSubsequence("id:1", "id:2")
                .doesNotContain("id:3");
        assertThat(boardBBody).contains("id:1").doesNotContain("id:2");
        System.out.println("BOARD_A_SSE\n" + boardABody + "BOARD_B_SSE\n" + boardBBody);
    }

    @Test
    void fullResetAndClearEventsCarryTheSameCanonicalStateAsSnapshot() throws Exception {
        // Given
        var stream = publicStream();
        live.update(BOARD, SLOT, CLAIM, bytes(payload(1, 2)), NOW.plusSeconds(90));

        // When
        live.update(BOARD, SLOT, CLAIM, bytes(payload(3, 4)), NOW.plusSeconds(90));
        var resetSnapshot = publicMvc.perform(get(path("snapshot")).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        live.clear(BOARD, SLOT, CLAIM, NOW.plusSeconds(90));
        var clearSnapshot = publicMvc.perform(get(path("snapshot")).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // Then
        var events = stream.getResponse().getContentAsString();
        assertThat(events)
                .contains("\"operation\":\"full-reset\"", "\"signature\":" + payload(3, 4),
                        "\"operation\":\"clear\"",
                        "\"signature\":{\"version\":1,\"strokes\":[]}");
        assertThat(resetSnapshot).contains("\"draftEpoch\":2", "\"revision\":0", payload(3, 4));
        assertThat(clearSnapshot).contains("\"draftEpoch\":3", "\"revision\":0",
                "\"draftSignature\":{\"version\":1,\"strokes\":[]}");
        System.out.println("RESET_CLEAR_SSE\n" + events
                + "RESET_SNAPSHOT\n" + resetSnapshot + "\nCLEAR_SNAPSHOT\n" + clearSnapshot);
    }

    @Test
    void cancelClearEventCarriesNullCanonicalStateMatchingSnapshot() throws Exception {
        // Given
        var stream = publicStream();
        live.update(BOARD, SLOT, CLAIM, bytes(payload(1, 2)), NOW.plusSeconds(90));

        // When
        live.cancel(BOARD, SLOT, CLAIM);

        // Then
        var snapshot = publicMvc.perform(get(path("snapshot")).cookie(owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stream.getResponse().getContentAsString())
                .contains("\"draftEpoch\":2", "\"revision\":0",
                        "\"operation\":\"clear\"", "\"signature\":null");
        assertThat(snapshot).contains("\"draftSignature\":null", "\"draftEpoch\":2", "\"revision\":0");
    }

    @Test
    void publicHeartbeatAndSnapshotFallbackRemainAvailable() throws Exception {
        // Given
        var stream = publicStream();
        live.update(BOARD, SLOT, CLAIM, bytes(payload(5, 6)), NOW.plusSeconds(90));

        // When
        publicRealtime.heartbeat();

        // Then
        assertThat(stream.getResponse().getContentAsString()).contains(":heartbeat");
        publicMvc.perform(get(path("snapshot")).cookie(owner))
                .andExpect(status().isOk());
    }

    @Test
    void shareReissueDisconnectsOldPublicStreamBeforeFutureDraftPublication() throws Exception {
        // Given
        var oldStream = publicStream();
        var before = oldStream.getResponse().getContentAsString();

        // When
        when(boards.findPublic(TOKEN)).thenReturn(Optional.empty());
        new BoardEventForwarder(adminRealtime, publicRealtime)
                .forward(new BoardMutationEvent(BOARD, "share-reissued"));
        live.update(BOARD, SLOT, CLAIM, bytes(payload(1, 2)), NOW.plusSeconds(90));

        // Then
        assertThat(publicRealtime.connectionCount()).isZero();
        assertThat(oldStream.getResponse().getContentAsString()).isEqualTo(before);
        System.out.println("MOCKMVC_SHARE_REISSUE initialStatus=" + oldStream.getResponse().getStatus()
                + " oldStreamConnections=0 futureDraftDelivered=false");
    }

    private MvcResult publicStream() throws Exception {
        return publicStream(TOKEN);
    }

    private MvcResult publicStream(String token) throws Exception {
        var setCookie = publicMvc.perform(post(path(token, "claim"))).andExpect(status().isNoContent())
                .andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        owner = new Cookie(PublicBoardDisplayController.COOKIE,
                setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
        return publicMvc.perform(get(path(token, "events")).cookie(owner))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private MvcResult adminStream() throws Exception {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, ADMIN, NOW);
        return adminMvc.perform(get("/api/v1/admin/boards/" + BOARD + "/events").session(session))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private BoardSnapshot snapshot() {
        var current = live.snapshot(BOARD, SLOT);
        return new BoardSnapshot(BOARD, 1920, 1080, false, SignatureInkColor.BLACK,
                List.of(new BoardSnapshot.Slot(
                SLOT, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                null, current.signature(), current.draftEpoch(), current.revision())));
    }

    private static DraftDelta delta(DraftDelta.Operation operation, long sequence,
            LiveSignatureRegistry.Version version, int strokeIndex, DraftDelta.Point... points) {
        return new DraftDelta(operation, sequence, version.draftEpoch(), version.revision(), strokeIndex,
                List.of(points));
    }

    private static DraftDelta.Point point(int x, int y) { return new DraftDelta.Point(x, y); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String payload(int x, int y) {
        return "{\"version\":1,\"strokes\":[{\"points\":[{\"x\":" + x + ",\"y\":" + y + "}]}]}";
    }
    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
    private static String path(String suffix) {
        return path(TOKEN, suffix);
    }
    private static String path(String token, String suffix) {
        return "/api/v1/public/links/" + token + "/display/" + suffix;
    }
}
