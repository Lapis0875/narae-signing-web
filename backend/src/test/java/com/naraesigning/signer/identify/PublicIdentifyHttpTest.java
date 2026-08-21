package com.naraesigning.signer.identify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.board.core.PublicBoardLink;
import com.naraesigning.security.CsrfContractFilter;
import com.naraesigning.security.CsrfTokenContract;
import com.naraesigning.session.PathAwareSessionIdResolver;
import com.naraesigning.session.SessionCookieActions;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicIdentifyHttpTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final String TOKEN = token((byte) 7);
    private static final String OLD_TOKEN = token((byte) 8);
    private static final String EXACT_JSON = """
            {"organization":"소속","job":"직책","name":"이름"}
            """;
    static final String NEAR_JSON = """
            {"organization":" 소속","job":"직책","name":"이름"}
            """;

    private HttpRepository repository;
    private MapSessionRepository sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        repository = new HttpRepository(crypto);
        var now = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);
        var service = new PublicIdentifyService(
                new PublicLinkLookup(rawToken -> TOKEN.equals(rawToken)
                        ? Optional.of(new PublicBoardLink(BOARD_ID, "행사 제목", "서명 진행", 3))
                        : Optional.empty(), repository),
                crypto,
                new PublicIdentifyRateLimiter(now));
        var csrf = new CsrfTokenContract();
        var sessionIds = new PathAwareSessionIdResolver();
        sessions = new MapSessionRepository(new ConcurrentHashMap<>());
        var sessionFilter = new SessionRepositoryFilter<>(sessions);
        sessionFilter.setHttpSessionIdResolver(sessionIds);
        mvc = MockMvcBuilders.standaloneSetup(
                        new PublicIdentifyController(service, new SessionCookieActions(sessionIds, csrf)))
                .setControllerAdvice(new PublicIdentifyAdvice())
                .addFilters(new PublicTokenResponseFilter(), sessionFilter, new CsrfContractFilter(csrf))
                .build();
    }

    @Test
    void assignedSignerIdentifiesThroughLinkCsrfAndSafeCookieFlow() throws Exception {
        // Given
        var link = mvc.perform(get("/api/v1/public/links/{token}", TOKEN).secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.title").value("행사 제목"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
        var csrf = csrfCookie(link);

        // When
        var identified = mvc.perform(post("/api/v1/public/links/{token}/identify", TOKEN)
                        .secure(true)
                        .with(request -> { request.setRemoteAddr("198.51.100.10"); return request; })
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EXACT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identified").value(true))
                .andExpect(jsonPath("$.organization").doesNotExist())
                .andExpect(jsonPath("$.job").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(cookie().secure("SIGNER_SESSION", true))
                .andExpect(cookie().httpOnly("SIGNER_SESSION", true))
                .andExpect(cookie().path("SIGNER_SESSION", "/api/v1/public"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();

        // Then
        assertThat(identified.getResponse().getHeaders("Set-Cookie"))
                .anySatisfy(value -> assertThat(value)
                        .startsWith("SIGNER_SESSION=")
                        .contains("Path=/api/v1/public", "Secure", "HttpOnly", "SameSite=Lax")
                        .doesNotContain("Max-Age"));
        var sessionId = cookieValue(identified, "SIGNER_SESSION");
        var session = sessions.findById(sessionId);
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(java.time.Duration.ofMinutes(30));
        assertThat(session.getAttributeNames()).containsExactlyInAnyOrder(
                "signer.boardId", "signer.slotId", "signer.shareLinkVersion",
                "signer.slotRevision", "signer.signatureAspectRatio", "signer.issuedAt");
        assertThat(session.getAttributeNames().stream()
                .map(session::getAttribute)
                .map(String::valueOf)
                .toList()).noneMatch(value -> value.contains("소속") || value.contains("직책") || value.contains("이름"));
    }

    @Test
    void nearSubmittedUnplacedClosedAndOldTokenHaveSameGenericBody() throws Exception {
        // Given
        var csrf = csrfCookie(mvc.perform(get("/api/v1/public/links/{token}", TOKEN).secure(true)).andReturn());
        var near = denied(TOKEN, NEAR_JSON, csrf, "198.51.100.20");
        repository.submitted = true;
        var submitted = denied(TOKEN, EXACT_JSON, csrf, "198.51.100.21");
        repository.submitted = false;
        repository.placement = "UNPLACED";
        var unplaced = denied(TOKEN, EXACT_JSON, csrf, "198.51.100.22");
        repository.placement = "PLACED";
        repository.status = "CLOSED";
        var closed = denied(TOKEN, EXACT_JSON, csrf, "198.51.100.23");

        // When
        var oldToken = denied(OLD_TOKEN, EXACT_JSON, csrf, "198.51.100.24");

        // Then
        assertThat(java.util.List.of(near, submitted, unplaced, closed, oldToken))
                .allSatisfy(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store, private");
                    assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
                    assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .doesNotContain("소속", "직책", "이름", BOARD_ID.toString(), SLOT_ID.toString());
                });
        assertThat(java.util.List.of(near, submitted, unplaced, closed, oldToken))
                .extracting(result -> result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .containsOnly(near.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void csrfErrorKeepsTokenHeadersAndStaticAssetCachingIsUntouched() throws Exception {
        // Given / When / Then
        mvc.perform(post("/api/v1/public/links/{token}/identify", TOKEN)
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EXACT_JSON))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/assets/app.abc123.js"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(header().doesNotExist("Referrer-Policy"));
    }

    private MvcResult denied(String token, String json, Cookie csrf, String clientIp) throws Exception {
        return mvc.perform(post("/api/v1/public/links/{token}/identify", token)
                        .secure(true)
                        .with(request -> { request.setRemoteAddr(clientIp); return request; })
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private static Cookie csrfCookie(MvcResult result) {
        return new Cookie("XSRF-TOKEN", cookieValue(result, "XSRF-TOKEN"));
    }

    private static String cookieValue(MvcResult result, String name) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + '='))
                .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
                .findFirst()
                .orElseThrow();
    }

    static String token(byte value) {
        var bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static final class HttpRepository implements PublicIdentifyRepository {
        private final byte[] lookupHash = new byte[] {7, 7, 7};
        private final byte[] exactHmac;
        private String status = "OPEN";
        private boolean submitted;
        private String placement = "PLACED";

        HttpRepository(VersionedCryptoService crypto) {
            exactHmac = crypto.identityHmac(BOARD_ID.toString(), "소속", "직책", "이름");
        }

        @Override
        public Optional<LinkRecord> findLink(UUID candidateBoardId, int linkVersion) {
            return BOARD_ID.equals(candidateBoardId) && linkVersion == 3
                    ? Optional.of(new LinkRecord(BOARD_ID, "행사 제목", status, 3, lookupHash))
                    : Optional.empty();
        }

        @Override
        public Optional<SignerRecord> findSigner(
                UUID candidateBoardId, int linkVersion, byte[] identityHmac) {
            if (!BOARD_ID.equals(candidateBoardId)
                    || linkVersion != 3
                    || !Arrays.equals(exactHmac, identityHmac)) {
                return Optional.empty();
            }
            return Optional.of(new SignerRecord(
                    BOARD_ID, SLOT_ID, status, 3, submitted, placement, 11,
                    "PLACED".equals(placement) ? new BigDecimal("0.25") : null,
                    "PLACED".equals(placement) ? new BigDecimal("0.50") : null,
                    1920, 1080));
        }

        @Override
        public Optional<SignerRecord> findSigner(UUID boardId, UUID slotId) {
            return Optional.empty();
        }
    }
}
