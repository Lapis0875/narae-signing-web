package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.background.BackgroundContent;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.PublicBoardLink;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SessionCookieActions;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class PublicBoardDisplayLeaseHttpTest {
    private static final UUID BOARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADMIN = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String TOKEN = "valid-share-token";
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private MutableClock clock;
    private PublicDisplayLeaseRegistry leases;
    private PublicBoardRealtimeRegistry realtime;
    private PublicBoardDisplayController displayController;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        leases = new PublicDisplayLeaseRegistry(clock);
        realtime = new PublicBoardRealtimeRegistry();
        var boards = mock(BoardService.class);
        var snapshots = mock(BoardSnapshotService.class);
        var backgrounds = mock(BackgroundAssetService.class);
        when(boards.findPublic(TOKEN)).thenReturn(Optional.of(
                new PublicBoardLink(BOARD, "Secret board title", "OPEN", 1)));
        when(snapshots.publiclyVisible(BOARD)).thenReturn(true);
        when(snapshots.readPublic(BOARD)).thenReturn(new BoardSnapshot(BOARD, 1920, 1080, false, List.of()));
        when(backgrounds.current(BOARD)).thenReturn(Optional.of(new BackgroundContent(
                new byte[] {1, 2, 3}, "image/png")));
        var admin = adminFixture();
        displayController = new PublicBoardDisplayController(
                boards, snapshots, backgrounds, realtime, leases);
        mvc = MockMvcBuilders.standaloneSetup(displayController, admin.controller())
                .addFilters(admin.filter()).build();
    }

    @Test
    void baselineVisiblePublicSnapshotIsReadable() throws Exception {
        // Given
        var owner = claimOwner();

        // When / Then
        mvc.perform(get(path("snapshot")).cookie(owner)).andExpect(status().isOk());
    }

    @Test
    void isolatedOwnersCannotBothReadDisplaySnapshot() throws Exception {
        // Given
        var owner = claimOwner();

        // When / Then
        mvc.perform(post(path("claim"))).andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        PublicBoardDisplayController.BLOCKED_MESSAGE)))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Secret board title"))));
        mvc.perform(get(path("snapshot")).cookie(owner)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(BOARD.toString())));
    }

    @Test
    void deniedOwnerGetsNoTitleSnapshotBackgroundOrEventsContent() throws Exception {
        // Given
        claimOwner();

        // When / Then
        for (var suffix : List.of("title", "snapshot", "background", "events")) {
            var body = mvc.perform(get(path(suffix))).andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Secret board title", BOARD.toString(), "connected");
        }
    }

    @Test
    void sameCookieReattachesAndKeepsOneEventEmitter() throws Exception {
        // Given
        var owner = claimOwner();

        // When
        mvc.perform(post(path("claim")).cookie(owner)).andExpect(status().isNoContent());
        mvc.perform(get(path("events")).cookie(owner).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        mvc.perform(get(path("events")).cookie(owner)).andExpect(request().asyncStarted());

        // Then
        assertThat(realtime.connectionCount()).isOne();
    }

    @Test
    void threeMissedHeartbeatsExpireLeaseAndFormerOwnerIsDenied() throws Exception {
        // Given
        var formerOwner = claimOwner();

        // When
        clock.advanceSeconds(30);
        var replacementOwner = claimOwner();

        // Then
        mvc.perform(get(path("snapshot")).cookie(formerOwner)).andExpect(status().isConflict());
        mvc.perform(post(path("heartbeat")).cookie(replacementOwner)).andExpect(status().isNoContent());
    }

    @Test
    void heartbeatAtTenSecondsExtendsLease() throws Exception {
        // Given
        var owner = claimOwner();
        clock.advanceSeconds(10);

        // When
        mvc.perform(post(path("heartbeat")).cookie(owner)).andExpect(status().isNoContent());
        clock.advanceSeconds(29);

        // Then
        mvc.perform(get(path("snapshot")).cookie(owner)).andExpect(status().isOk());
    }

    @Test
    void ownerReleaseFreesLeaseAndMalformedHeartbeatCannotReleaseIt() throws Exception {
        // Given
        var owner = claimOwner();

        // When / Then
        mvc.perform(post(path("heartbeat")).cookie(
                        new Cookie(PublicBoardDisplayController.COOKIE, "malformed")))
                .andExpect(status().isConflict());
        mvc.perform(get(path("snapshot")).cookie(owner)).andExpect(status().isOk());
        mvc.perform(post(path("release")).cookie(owner)).andExpect(status().isNoContent());
        claimOwner();
    }

    @Test
    void invalidShareTokenCannotClaimOrRevealContent() throws Exception {
        // Given / When / Then
        mvc.perform(post("/api/v1/public/links/invalid/display/claim"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Secret board title"))));
    }

    @Test
    void invalidSseTokensReturnJsonNotFoundWithoutBoardContent() throws Exception {
        // Given / When / Then
        for (var token : List.of("invalid-share-token", "x")) {
            MockMvcBuilders.standaloneSetup(displayController)
                    .setControllerAdvice(apiExceptionHandler()).build()
                    .perform(get("/api/v1/public/links/" + token + "/display/events")
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("PUBLIC_DISPLAY_UNAVAILABLE"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.containsString("Secret board title"),
                            org.hamcrest.Matchers.containsString(BOARD.toString()),
                            org.hamcrest.Matchers.containsString("image/png"),
                            org.hamcrest.Matchers.containsString("event:")))));
        }
    }

    @Test
    void authorizedForceReplacementSendsEventBeforeOldStreamCloses() throws Exception {
        // Given
        var owner = claimOwner();
        MvcResult stream = mvc.perform(get(path("events")).cookie(owner))
                .andDo(print()).andExpect(request().asyncStarted()).andReturn();
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, ADMIN, NOW);

        // When
        mvc.perform(post("/api/v1/admin/boards/" + BOARD + "/display/force-replace"))
                .andDo(print()).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/boards/" + BOARD + "/display/force-replace")
                        .session(session).secure(true))
                .andDo(print()).andExpect(status().isNoContent());

        // Then
        print().handle(stream);
        assertThat(stream.getResponse().getContentAsString())
                .contains("event:display-replaced", "data:{}");
        mvc.perform(get(path("snapshot")).cookie(owner)).andDo(print()).andExpect(status().isConflict());
        claimOwner();
    }

    @Test
    void cookieIsSecureHttpOnlySameSiteLaxAndNeverReturnedInBody() throws Exception {
        // Given / When
        var result = mvc.perform(post(path("claim"))).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))))
                .andReturn();

        // Then
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(result.getResponse().getHeader("Set-Cookie")).doesNotContain(TOKEN);
    }

    private Cookie claimOwner() throws Exception {
        var header = mvc.perform(post(path("claim"))).andExpect(status().isNoContent())
                .andDo(print()).andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(header).isNotNull();
        return new Cookie(PublicBoardDisplayController.COOKIE,
                header.substring(header.indexOf('=') + 1, header.indexOf(';')));
    }

    private AdminFixture adminFixture() {
        try {
            var facadeType = Class.forName("com.naraesigning.board.api.BoardAdminFacade");
            var controllerType = Class.forName("com.naraesigning.board.api.BoardAdminController");
            var constructor = controllerType.getDeclaredConstructor(
                    facadeType, PublicDisplayLeaseRegistry.class, PublicBoardRealtimeRegistry.class);
            constructor.setAccessible(true);
            var facade = mock(facadeType);
            var filterType = Class.forName("com.naraesigning.board.api.AdminBoardFilter");
            var filterConstructor = filterType.getDeclaredConstructor(
                    facadeType, SessionCookieActions.class, Clock.class);
            filterConstructor.setAccessible(true);
            return new AdminFixture(constructor.newInstance(facade, leases, realtime),
                    (Filter) filterConstructor.newInstance(
                            facade, mock(SessionCookieActions.class), Clock.fixed(NOW, ZoneOffset.UTC)));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Object apiExceptionHandler() {
        try {
            var type = Class.forName("com.naraesigning.web.ApiExceptionHandler");
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record AdminFixture(Object controller, Filter filter) {}

    private static String path(String suffix) {
        return "/api/v1/public/links/" + TOKEN + "/display/" + suffix;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        void advanceSeconds(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
