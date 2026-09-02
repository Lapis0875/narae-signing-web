package com.naraesigning.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.board.core.BoardUnavailableException;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SessionCookieActions;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class BoardRealtimeHttpTest {
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final UUID BOARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BOARD = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OWNER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private BoardAdminFacade facade;
    private Object registry;
    private Method connectionCount;
    private Method publish;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        facade = mock(BoardAdminFacade.class);
        var registryType = Class.forName("com.naraesigning.realtime.BoardRealtimeRegistry");
        var registryConstructor = registryType.getDeclaredConstructor(Clock.class);
        registryConstructor.setAccessible(true);
        registry = registryConstructor.newInstance(Clock.fixed(NOW, ZoneOffset.UTC));
        connectionCount = registryType.getDeclaredMethod("connectionCount");
        connectionCount.setAccessible(true);
        publish = registryType.getDeclaredMethod("publish", UUID.class, String.class);
        publish.setAccessible(true);
        var controllerType = Class.forName("com.naraesigning.realtime.BoardRealtimeController");
        var controllerConstructor = controllerType.getDeclaredConstructor(registryType, ObjectProvider.class);
        controllerConstructor.setAccessible(true);
        var controller = controllerConstructor.newInstance(registry, mock(ObjectProvider.class));
        var filter = new AdminBoardFilter(facade, mock(SessionCookieActions.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(filter).build();
    }

    @Test
    void activeOwnerGetsPrivatePayloadFreeEventStream() throws Exception {
        // Given
        MvcResult result = mvc.perform(get(path(BOARD)).session(session(NOW)).secure(true))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();

        // When
        publish.invoke(registry, BOARD, "signature-submitted");

        // Then
        assertThat(result.getResponse().getContentAsString())
                .contains(":connected", "id:1", "event:signature-submitted", "data:{}")
                .doesNotContain("boardId", BOARD.toString());
        assertThat(count()).isOne();
    }

    @Test
    void expiryAndWrongOwnerRejectBeforeRegistration() throws Exception {
        // Given / When / Then
        mvc.perform(get(path(BOARD))
                        .session(session(NOW.minus(AdminSessionContract.ABSOLUTE_LIFETIME))).secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        assertThat(count()).isZero();

        doThrow(new BoardUnavailableException()).when(facade).authorize(any(), eq(OTHER_BOARD));
        mvc.perform(get(path(OTHER_BOARD)).session(session(NOW)).secure(true))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        assertThat(count()).isZero();
    }

    private int count() throws Exception { return (int) connectionCount.invoke(registry); }

    private static MockHttpSession session(Instant issuedAt) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, OWNER, issuedAt);
        return session;
    }

    private static String path(UUID boardId) {
        return "/api/v1/admin/boards/" + boardId + "/events";
    }
}
