package com.naraesigning.signer.identify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.naraesigning.board.core.PublicBoardLink;
import com.naraesigning.config.PlatformProperties;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.security.CsrfContractFilter;
import com.naraesigning.security.CsrfTokenContract;
import com.naraesigning.session.PathAwareSessionIdResolver;
import com.naraesigning.session.SessionCookieActions;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicIdentifyTrustedProxyHttpTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var repository = new PublicIdentifyHttpTest.HttpRepository(crypto);
        var boardId = java.util.UUID.fromString("10000000-0000-0000-0000-000000000001");
        var clock = Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC);
        var service = new PublicIdentifyService(
                new PublicLinkLookup(rawToken -> PublicIdentifyHttpTest.TOKEN.equals(rawToken)
                        ? Optional.of(new PublicBoardLink(boardId, "행사 제목", "서명 진행", 3))
                        : Optional.empty(), repository),
                crypto,
                new PublicIdentifyRateLimiter(clock));
        var csrf = new CsrfTokenContract();
        var sessionIds = new PathAwareSessionIdResolver();
        mvc = MockMvcBuilders.standaloneSetup(
                        new PublicIdentifyController(service, new SessionCookieActions(sessionIds, csrf)))
                .setControllerAdvice(new PublicIdentifyAdvice())
                .addFilters(new PublicTokenResponseFilter(), trustedProxyFilter(), new CsrfContractFilter(csrf))
                .build();
    }

    @Test
    void forgedForwardedIpCannotBypassTrustedClientRateKey() throws Exception {
        // Given
        var csrf = csrfCookie(mvc.perform(get("/api/v1/public/links/{token}",
                PublicIdentifyHttpTest.TOKEN).secure(true)).andReturn());
        for (int attempt = 0; attempt < 60; attempt++) {
            identify(csrf, "198.51.100.30", "203.0.113." + attempt)
                    .andExpect(status().isForbidden());
        }

        // When / Then
        identify(csrf, "198.51.100.30", "192.0.2.200")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        identify(csrf, "198.51.100.31", "192.0.2.201")
                .andExpect(status().isForbidden());
    }

    @Test
    void untrustedPeerCannotForgeTrustedClientIpAndKeepsPrivateHeaders() throws Exception {
        // Given / When / Then
        mvc.perform(post("/api/v1/public/links/{token}/identify", PublicIdentifyHttpTest.TOKEN)
                        .secure(true)
                        .with(request -> { request.setRemoteAddr("203.0.113.8"); return request; })
                        .header("X-Narae-Client-IP", "198.51.100.40")
                        .header("X-Forwarded-For", "192.0.2.40")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PublicIdentifyHttpTest.NEAR_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    private org.springframework.test.web.servlet.ResultActions identify(
            Cookie csrf, String clientIp, String forwardedIp) throws Exception {
        return mvc.perform(post("/api/v1/public/links/{token}/identify", PublicIdentifyHttpTest.TOKEN)
                .secure(true)
                .with(request -> { request.setRemoteAddr("172.30.0.10"); return request; })
                .header("X-Narae-Client-IP", clientIp)
                .header("X-Forwarded-For", forwardedIp)
                .cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(PublicIdentifyHttpTest.NEAR_JSON));
    }

    private static Cookie csrfCookie(MvcResult result) {
        var value = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith("XSRF-TOKEN="))
                .map(header -> header.substring("XSRF-TOKEN=".length(), header.indexOf(';')))
                .findFirst()
                .orElseThrow();
        return new Cookie("XSRF-TOKEN", value);
    }

    private static Filter trustedProxyFilter() throws Exception {
        var type = Class.forName("com.naraesigning.web.TrustedProxyFilter");
        var constructor = type.getDeclaredConstructor(PlatformProperties.class);
        constructor.setAccessible(true);
        return (Filter) constructor.newInstance(new PlatformProperties(
                "http://localhost", "http://minio", "bucket", "access", "secret",
                "/key", 1, "172.30.0.10"));
    }
}
