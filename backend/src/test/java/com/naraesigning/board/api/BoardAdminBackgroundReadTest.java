package com.naraesigning.board.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.background.BackgroundContent;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardUnavailableException;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SessionCookieActions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class BoardAdminBackgroundReadTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final UUID BOARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OWNER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private BoardAdminFacade facade;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        facade = org.mockito.Mockito.mock(BoardAdminFacade.class);
        var filter = new AdminBoardFilter(facade, org.mockito.Mockito.mock(SessionCookieActions.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new BoardAdminController(facade))
                .setControllerAdvice(new BoardAdminApiAdvice()).addFilters(filter).build();
    }

    @Test
    void ownerReadsBytesWithPrivateHeadersAndMissingReturnsNoContent() throws Exception {
        var session = session(NOW);
        var bytes = new byte[] {1, 2, 3};
        when(facade.currentBackground(org.mockito.ArgumentMatchers.any(BoardOwner.class),
                org.mockito.ArgumentMatchers.eq(BOARD)))
                .thenReturn(Optional.of(new BackgroundContent(bytes, "image/png")))
                .thenReturn(Optional.empty());

        mvc.perform(get(path()).session(session).secure(true)).andExpect(status().isOk())
                .andExpect(content().bytes(bytes)).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get(path()).session(session).secure(true)).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void expiredAndWrongOwnerRejectBeforeBackgroundLookup() throws Exception {
        mvc.perform(get(path()).session(session(NOW.minus(AdminSessionContract.ABSOLUTE_LIFETIME))).secure(true))
                .andExpect(status().isUnauthorized());
        verify(facade, never()).currentBackground(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        org.mockito.Mockito.doThrow(new BoardUnavailableException()).when(facade)
                .authorize(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(BOARD));
        mvc.perform(get(path()).session(session(NOW)).secure(true)).andExpect(status().isNotFound());
        verify(facade, never()).currentBackground(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static MockHttpSession session(Instant issuedAt) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, OWNER, issuedAt);
        return session;
    }

    private static String path() { return "/api/v1/admin/boards/" + BOARD + "/background"; }
}
