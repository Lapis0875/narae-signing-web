package com.naraesigning.signer.identify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.security.CsrfTokenContract;
import com.naraesigning.session.PathAwareSessionIdResolver;
import com.naraesigning.session.SessionCookieActions;
import com.naraesigning.session.SignerSessionContract;
import java.math.BigDecimal;
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

class PublicSigningSessionHttpTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-21T00:10:00Z");
    private HttpSessionRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new HttpSessionRepository();
        var sessionIds = new PathAwareSessionIdResolver();
        mvc = MockMvcBuilders.standaloneSetup(new PublicSigningSessionController(
                        new PublicSigningSessionService(repository),
                        new SessionCookieActions(sessionIds, new CsrfTokenContract()),
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .setControllerAdvice(new PublicIdentifyAdvice())
                .addFilters(new PublicTokenResponseFilter())
                .build();
    }

    @Test
    void readyResponseReturnsOnlyStateAndExactAspectWithPrivateHeaders() throws Exception {
        mvc.perform(get("/api/v1/public/signing-session").session(currentSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.signatureAspectRatio").value(1.5))
                .andExpect(jsonPath("$.boardId").doesNotExist())
                .andExpect(jsonPath("$.slotId").doesNotExist())
                .andExpect(jsonPath("$.identity").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void missingMalformedAndExpiredSessionsReturnTheSameGenericUnauthorizedShape() throws Exception {
        var missing = mvc.perform(get("/api/v1/public/signing-session"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        var malformed = mvc.perform(get("/api/v1/public/signing-session").session(malformedSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge("SIGNER_SESSION", 0))
                .andReturn();
        var expired = mvc.perform(get("/api/v1/public/signing-session").session(expiredSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge("SIGNER_SESSION", 0))
                .andReturn();

        assertThat(expired.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .isEqualTo(malformed.getResponse().getContentAsString())
                .doesNotContain(BOARD_ID.toString(), SLOT_ID.toString());
        assertThat(missing.getResponse().getHeader("Cache-Control")).isEqualTo("no-store, private");
        assertThat(expired.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void staleSessionReturnsGenericStateAndClearsOnlyTheSignerCookie() throws Exception {
        repository.revision = 12;

        var result = mvc.perform(get("/api/v1/public/signing-session").session(currentSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STALE"))
                .andExpect(jsonPath("$.signatureAspectRatio").doesNotExist())
                .andExpect(cookie().maxAge("SIGNER_SESSION", 0))
                .andExpect(cookie().doesNotExist("ADMIN_SESSION"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(BOARD_ID.toString(), SLOT_ID.toString(), "name", "organization", "job");
    }

    private static MockHttpSession currentSession() {
        var session = new MockHttpSession();
        SignerSessionContract.issue(session, new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 1.5, NOW.minusSeconds(60)));
        return session;
    }

    private static MockHttpSession expiredSession() {
        var session = new MockHttpSession();
        SignerSessionContract.issue(session, new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 1.5, NOW.minusSeconds(1_801)));
        return session;
    }

    private static MockHttpSession malformedSession() {
        var session = currentSession();
        session.setAttribute("signer.slotRevision", "private-invalid-value");
        return session;
    }

    private static final class HttpSessionRepository implements PublicIdentifyRepository {
        private long revision = 11;

        @Override
        public Optional<LinkRecord> findLink(UUID boardId, int linkVersion) {
            return Optional.empty();
        }

        @Override
        public Optional<SignerRecord> findSigner(UUID boardId, int linkVersion, byte[] identityHmac) {
            return Optional.empty();
        }

        @Override
        public Optional<SignerRecord> findSigner(UUID boardId, UUID slotId) {
            return Optional.of(new SignerRecord(
                    BOARD_ID, SLOT_ID, "OPEN", 3, false, "PLACED", revision,
                    new BigDecimal("0.50"), new BigDecimal("0.50"), 1620, 1080));
        }
    }
}
