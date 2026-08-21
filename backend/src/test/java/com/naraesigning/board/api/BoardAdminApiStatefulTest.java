package com.naraesigning.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.security.CsrfContractFilter;
import com.naraesigning.security.CsrfTokenContract;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SessionCookieActions;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class BoardAdminApiStatefulTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_OWNER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String CSRF = "csrf-value";
    private final ObjectMapper mapper = new ObjectMapper();
    private StatefulBoardApiGraph graph;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        graph = new StatefulBoardApiGraph();
        var admin = new AdminBoardFilter(graph.facade(), org.mockito.Mockito.mock(SessionCookieActions.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(
                        new BoardAdminController(graph.facade()), graph.rosterController())
                .setControllerAdvice(new BoardAdminApiAdvice(), graph.rosterAdvice())
                .addFilters(admin, new CsrfContractFilter(new CsrfTokenContract()))
                .build();
    }

    @Test
    void authenticatedLifecycleProducesObservableStateSnapshotsAcrossEveryRoute() throws Exception {
        var session = session(OWNER, NOW);
        assertThat(graph.canonicalGraph()).isEqualTo("boards=[];background=null;roster=[];slots=[];events=[]");

        var created = perform(post("/api/v1/admin/boards").contentType(APPLICATION_JSON)
                .content("{\"title\":\"행사\"}"), session).andExpect(status().isOk()).andReturn();
        String oldToken = json(created, "/shareToken");
        assertThat(json(created, "/board/id")).isEqualTo(graph.boardId().toString());
        snapshot("created");
        perform(get("/api/v1/admin/boards"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(graph.boardId().toString()));
        perform(get(boardPath()), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.shareToken").doesNotExist());
        perform(patch(boardPath()).contentType(APPLICATION_JSON).content("{\"title\":\"새 행사\"}"), session)
                .andExpect(status().isOk());

        var first = perform(post(rosterPath()).contentType(APPLICATION_JSON).content(identity("A")), session)
                .andExpect(status().isOk()).andReturn();
        UUID firstEntry = UUID.fromString(json(first, "/id"));
        UUID firstSlot = UUID.fromString(json(first, "/slot/id"));
        perform(get(rosterPath()), session).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(firstEntry.toString()));
        perform(patch(rosterPath() + "/" + firstEntry).contentType(APPLICATION_JSON)
                        .content(identity("A-edited")), session)
                .andExpect(status().isOk());
        var second = perform(post(rosterPath()).contentType(APPLICATION_JSON).content(identity("B")), session)
                .andExpect(status().isOk()).andReturn();
        UUID secondEntry = UUID.fromString(json(second, "/id"));
        perform(delete(rosterPath() + "/" + secondEntry), session).andExpect(status().isNoContent());
        perform(put(rosterPath()).contentType(APPLICATION_JSON)
                        .content("{\"rows\":[" + identity("A-edited") + "]}"), session)
                .andExpect(status().isOk());
        var csv = new MockMultipartFile("file", "roster.csv", "text/csv",
                "소속사,직책,이름\nOrg,Role,A-edited".getBytes(StandardCharsets.UTF_8));
        var imported = perform(multipart(rosterPath() + "/import").file(csv), session)
                .andExpect(status().isOk()).andReturn();
        firstEntry = UUID.fromString(json(imported, "/0/id"));
        firstSlot = UUID.fromString(json(imported, "/0/slot/id"));
        snapshot("roster");

        perform(delete(boardPath() + "/slots/" + firstSlot), session).andExpect(status().isNoContent());
        perform(patch(boardPath() + "/slots/" + firstSlot).contentType(APPLICATION_JSON)
                        .content(slotBody()), session)
                .andExpect(status().isOk());
        perform(post(boardPath() + "/slots/" + firstSlot + "/reset-signature"), session)
                .andExpect(status().isOk());
        var image = new MockMultipartFile("file", "background.png", "image/png", new byte[] {1, 2, 3});
        perform(multipart(boardPath() + "/background").file(image), session).andExpect(status().isOk());
        snapshot("slots");

        perform(post(boardPath() + "/open"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("서명 진행"));
        snapshot("open");
        perform(post(boardPath() + "/close"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("마감/보관"));
        snapshot("closed");
        perform(post(boardPath() + "/reopen"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("서명 진행"));
        snapshot("reopened");

        perform(get(boardPath() + "/share"), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.shareToken").value(oldToken));
        var reissued = perform(post(boardPath() + "/share/reissue"), session)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andReturn();
        String newToken = json(reissued, "/shareToken");
        assertThat(graph.boardService().findPublic(oldToken)).isEmpty();
        assertThat(graph.boardService().findPublic(newToken)).isPresent();
        System.out.println("QA_LINK resolver=BoardService.findPublic repository=InMemoryBoardRepository "
                + "oldValid=false newValid=true shareVersion=2");
        snapshot("reissued-old-link-invalid");
        assertThat(graph.transactionTrace()).containsSubsequence(
                "db:OPEN", "commit", "event:OPEN",
                "db:CLOSED", "commit", "event:CLOSED",
                "db:OPEN", "commit", "event:OPEN");
        System.out.println("QA_TRANSACTION trace=" + graph.transactionTrace());
        assertThat(firstEntry).isNotNull();
    }

    @Test
    void canonicalGraphDetectsIdentityMutationWithoutCountChange() throws Exception {
        var session = session(OWNER, NOW);
        perform(post("/api/v1/admin/boards").contentType(APPLICATION_JSON)
                .content("{\"title\":\"행사\"}"), session).andExpect(status().isOk());
        var roster = perform(post(rosterPath()).contentType(APPLICATION_JSON).content(identity("Before")), session)
                .andExpect(status().isOk()).andReturn();
        UUID entryId = UUID.fromString(json(roster, "/id"));
        String before = graph.canonicalGraph();

        perform(patch(rosterPath() + "/" + entryId).contentType(APPLICATION_JSON)
                        .content(identity("After")), session)
                .andExpect(status().isOk());
        assertThat(graph.canonicalGraph()).isNotEqualTo(before);
    }

    @Test
    void publicLookupAfterReissueUsesRealBoardCoreServiceAndRepository() throws Exception {
        var session = session(OWNER, NOW);
        var created = perform(post("/api/v1/admin/boards").contentType(APPLICATION_JSON)
                .content("{\"title\":\"행사\"}"), session).andExpect(status().isOk()).andReturn();
        String oldToken = json(created, "/shareToken");
        assertThat(org.mockito.Mockito.mockingDetails(graph.boardService()).isMock()).isFalse();

        var reissued = perform(post(boardPath() + "/share/reissue"), session)
                .andExpect(status().isOk()).andReturn();
        String newToken = json(reissued, "/shareToken");
        assertThat(graph.boardService().findPublic(oldToken)).isEmpty();
        assertThat(graph.boardService().findPublic(newToken)).isPresent();
    }

    @Test
    void rejectedRequestsPreserveExactStateAndReturnStableSafeErrors() throws Exception {
        var ownerSession = session(OWNER, NOW);
        var created = perform(post("/api/v1/admin/boards").contentType(APPLICATION_JSON)
                .content("{\"title\":\"행사\"}"), ownerSession).andExpect(status().isOk()).andReturn();
        String protectedToken = json(created, "/shareToken");
        var roster = perform(post(rosterPath()).contentType(APPLICATION_JSON)
                        .content(identity("ProtectedName")), ownerSession)
                .andExpect(status().isOk()).andReturn();
        UUID entryId = UUID.fromString(json(roster, "/id"));
        UUID slotId = UUID.fromString(json(roster, "/slot/id"));

        assertRejectedUnchanged("forged-owner", get(boardPath()).session(session(OTHER_OWNER, NOW)).secure(true),
                404, "BOARD_UNAVAILABLE", protectedToken, "ProtectedName");

        String beforeCsrf = graph.canonicalGraph();
        var missingCsrf = mvc.perform(post(boardPath() + "/open").session(ownerSession).secure(true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn();
        assertSafeUnchanged("missing-csrf code=csrf_invalid", beforeCsrf, missingCsrf,
                protectedToken, "ProtectedName");

        String auditBeforeExpiry = graph.securityAudit();
        assertRejectedUnchanged("expired-session", get(boardPath())
                        .session(session(OWNER, NOW.minus(AdminSessionContract.ABSOLUTE_LIFETIME))).secure(true),
                401, "UNAUTHORIZED", protectedToken, "ProtectedName");
        assertThat(graph.securityAudit()).isEqualTo(auditBeforeExpiry);
        System.out.println("QA_ORDER expired-session before={" + auditBeforeExpiry
                + "} after={" + graph.securityAudit() + "}");

        var stale = session(OWNER, NOW);
        stale.setAttribute(AdminSessionContract.ADMIN_USER_ID, "forged-principal");
        String auditBeforeStale = graph.securityAudit();
        assertRejectedUnchanged("stale-session", get(boardPath()).session(stale).secure(true),
                401, "UNAUTHORIZED", protectedToken, "ProtectedName");
        assertThat(graph.securityAudit()).isEqualTo(auditBeforeStale);
        System.out.println("QA_ORDER stale-session before={" + auditBeforeStale
                + "} after={" + graph.securityAudit() + "}");

        assertRejectedUnchanged("incomplete-open", authenticated(post(boardPath() + "/open"), ownerSession),
                409, "BOARD_OPEN_INCOMPLETE", protectedToken, "ProtectedName");
        perform(patch(boardPath() + "/slots/" + slotId).contentType(APPLICATION_JSON).content(slotBody()), ownerSession)
                .andExpect(status().isOk());
        graph.failNextPostTransitionDetail();
        assertRejectedUnchanged("rolled-back-open", authenticated(post(boardPath() + "/open"), ownerSession),
                404, "BOARD_UNAVAILABLE", protectedToken, "ProtectedName");
        assertThat(graph.transactionTrace()).endsWith("rollback").doesNotContain("event:OPEN");
        perform(post(boardPath() + "/open"), ownerSession).andExpect(status().isOk());

        assertRejectedUnchanged("bulk-open", authenticated(put(rosterPath()).contentType(APPLICATION_JSON)
                        .content("{\"rows\":[]}"), ownerSession),
                409, "ROSTER_UNAVAILABLE", protectedToken, "ProtectedName");
        var file = new MockMultipartFile("file", "roster.csv", "text/csv",
                "소속사,직책,이름\nOrg,Role,ProtectedName".getBytes(StandardCharsets.UTF_8));
        assertRejectedUnchanged("import-open", authenticated(multipart(rosterPath() + "/import").file(file), ownerSession),
                409, "ROSTER_UNAVAILABLE", protectedToken, "ProtectedName");

        graph.markSubmitted(entryId);
        assertRejectedUnchanged("submitted-edit", authenticated(patch(rosterPath() + "/" + entryId)
                        .contentType(APPLICATION_JSON).content(identity("ChangedProtectedName")), ownerSession),
                409, "ROSTER_UNAVAILABLE", protectedToken, "ProtectedName");
        assertRejectedUnchanged("submitted-delete", authenticated(delete(rosterPath() + "/" + entryId), ownerSession),
                409, "ROSTER_UNAVAILABLE", protectedToken, "ProtectedName");
        perform(post(boardPath() + "/slots/" + slotId + "/reset-signature"), ownerSession)
                .andExpect(status().isOk());
        perform(patch(rosterPath() + "/" + entryId).contentType(APPLICATION_JSON)
                        .content(identity("ChangedAfterReset")), ownerSession)
                .andExpect(status().isOk());

        assertRejectedUnchanged("client-owner-status", authenticated(post("/api/v1/admin/boards")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"ownerId\":\"" + OTHER_OWNER + "\",\"status\":\"OPEN\"}"),
                        ownerSession), 400, "INVALID_REQUEST", protectedToken, "ProtectedName");
    }

    private void assertRejectedUnchanged(String scenario, MockHttpServletRequestBuilder request,
            int expectedStatus, String expectedCode, String... protectedValues) throws Exception {
        String before = graph.canonicalGraph();
        var result = mvc.perform(request).andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn();
        assertSafeUnchanged(scenario + " code=" + expectedCode, before, result, protectedValues);
    }

    private void assertSafeUnchanged(String scenario, String before, MvcResult result, String... protectedValues)
            throws Exception {
        String after = graph.canonicalGraph();
        assertThat(after).isEqualTo(before);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(protectedValues);
        System.out.println("QA_REJECT " + scenario + " beforeDigest="
                + StatefulBoardApiGraph.digestCanonical(before) + " afterDigest="
                + StatefulBoardApiGraph.digestCanonical(after));
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            MockHttpServletRequestBuilder request, MockHttpSession session) throws Exception {
        return mvc.perform(authenticated(request, session))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    private static MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request, MockHttpSession session) {
        return request.session(session).secure(true)
                .cookie(new Cookie(CsrfTokenContract.COOKIE_NAME, CSRF))
                .header(CsrfTokenContract.HEADER_NAME, CSRF);
    }

    private void snapshot(String phase) {
        System.out.println("QA_STATE phase=" + phase + " canonicalGraphDigest=" + graph.canonicalDigest());
    }

    private String json(MvcResult result, String pointer) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray()).at(pointer).asText();
    }

    private static MockHttpSession session(UUID owner, Instant issuedAt) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, owner, issuedAt);
        return session;
    }

    private String boardPath() { return "/api/v1/admin/boards/" + graph.boardId(); }
    private String rosterPath() { return boardPath() + "/roster"; }
    private static String identity(String name) {
        return "{\"organization\":\"Org\",\"job\":\"Role\",\"name\":\"" + name + "\"}";
    }
    private static String slotBody() {
        return "{\"x\":0.1,\"y\":0.1,\"width\":0.2,\"height\":0.2,\"background\":\"white\"}";
    }
}
