package com.naraesigning.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignatureSubmitSessionTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:30:00Z");

    @Test
    void requiresBothAbsoluteAndIdleSessionLifetimes() {
        // Given
        var absoluteExpired = session(NOW.minusSeconds(1_801), NOW.minusSeconds(60));
        var idleExpired = session(NOW.minusSeconds(60), NOW.minusSeconds(1_801));
        var current = session(NOW.minusSeconds(60), NOW.minusSeconds(60));

        // When / Then
        assertThat(SignatureSession.from(absoluteExpired, NOW).active()).isFalse();
        assertThat(SignatureSession.from(idleExpired, NOW).active()).isFalse();
        assertThat(SignatureSession.from(current, NOW).active()).isTrue();
    }

    private static HttpSession session(Instant issuedAt, Instant accessedAt) {
        var session = mock(HttpSession.class);
        when(session.getAttribute("signer.boardId")).thenReturn(UUID.randomUUID());
        when(session.getAttribute("signer.slotId")).thenReturn(UUID.randomUUID());
        when(session.getAttribute("signer.shareLinkVersion")).thenReturn(3);
        when(session.getAttribute("signer.slotRevision")).thenReturn(11L);
        when(session.getAttribute("signer.signatureAspectRatio")).thenReturn(1.5);
        when(session.getAttribute("signer.issuedAt")).thenReturn(issuedAt);
        when(session.getLastAccessedTime()).thenReturn(accessedAt.toEpochMilli());
        when(session.getMaxInactiveInterval()).thenReturn(1_800);
        return session;
    }
}
