package com.naraesigning.signer.identify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.board.core.PublicBoardLink;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.session.SignerSessionContract;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PublicIdentifyServiceTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String TOKEN = token((byte) 7);
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final IdentifyRequest EXACT = new IdentifyRequest("소속", "직책", "이름");

    private VersionedCryptoService crypto;
    private FakeRepository repository;
    private PublicIdentifyService service;

    @BeforeEach
    void setUp() {
        crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        repository = new FakeRepository(crypto);
        service = new PublicIdentifyService(
                new PublicLinkLookup(rawToken -> TOKEN.equals(rawToken)
                        ? Optional.of(new PublicBoardLink(BOARD_ID, "행사 제목", "서명 진행", 3))
                        : Optional.empty(), repository),
                crypto,
                new PublicIdentifyRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void returnsOnlyMinimalSetupOpenClosedAndInvalidLinkStates() {
        // Given / When / Then
        repository.status = "DRAFT";
        assertThat(service.link(TOKEN)).isEqualTo(new PublicLinkResponse(PublicLinkState.SETUP, "행사 제목"));
        repository.status = "OPEN";
        assertThat(service.link(TOKEN)).isEqualTo(new PublicLinkResponse(PublicLinkState.OPEN, "행사 제목"));
        repository.status = "CLOSED";
        assertThat(service.link(TOKEN)).isEqualTo(new PublicLinkResponse(PublicLinkState.CLOSED, "행사 제목"));
        assertThat(service.link(token((byte) 8)))
                .isEqualTo(new PublicLinkResponse(PublicLinkState.INVALID, null));
    }

    @Test
    void issuesBoundSignerSessionOnlyForExactRawIdentity() {
        // Given
        repository.status = "OPEN";

        // When
        var signer = service.identify(TOKEN, "198.51.100.1", EXACT);

        // Then
        assertThat(signer).isEqualTo(new SignerSessionContract.Value(
                BOARD_ID, SLOT_ID, 3, 11, 0.888889, NOW));
        assertThatThrownBy(() -> service.identify(
                TOKEN, "198.51.100.1", new IdentifyRequest(" 소속", "직책", "이름")))
                .isInstanceOfSatisfying(PublicIdentifyException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IDENTIFICATION_FAILED"));
        assertThatThrownBy(() -> service.identify(
                TOKEN, "198.51.100.1", new IdentifyRequest("소속", "직책", "이 름")))
                .isInstanceOf(PublicIdentifyException.class);
        assertThatThrownBy(() -> service.identify(
                TOKEN, "198.51.100.1", new IdentifyRequest("소속", "직책", "이름 ")))
                .isInstanceOf(PublicIdentifyException.class);
    }

    @Test
    void deniesClosedInvalidSubmittedAndUnplacedWithoutDistinctErrors() {
        // Given
        repository.status = "CLOSED";
        var closed = failure(() -> service.identify(TOKEN, "198.51.100.2", EXACT));
        repository.status = "OPEN";
        repository.submitted = true;
        var submitted = failure(() -> service.identify(TOKEN, "198.51.100.3", EXACT));
        repository.submitted = false;
        repository.placement = "UNPLACED";
        var unplaced = failure(() -> service.identify(TOKEN, "198.51.100.4", EXACT));

        // When
        var invalid = failure(() -> service.identify(token((byte) 9), "198.51.100.5", EXACT));

        // Then
        assertThat(java.util.List.of(closed, submitted, unplaced, invalid))
                .extracting(PublicIdentifyException::status, PublicIdentifyException::code)
                .containsOnly(org.assertj.core.groups.Tuple.tuple(
                        org.springframework.http.HttpStatus.FORBIDDEN, "IDENTIFICATION_FAILED"));
    }

    @Test
    void issuedSessionFailsClosedForStaleLinkRevisionAndAspect() {
        // Given
        repository.status = "OPEN";
        var signer = service.identify(TOKEN, "198.51.100.6", EXACT);

        // When / Then
        assertThat(SignerSessionContract.validate(signer,
                new SignerSessionContract.CurrentState(4, 11, 0.888889, "OPEN")))
                .isEqualTo(SignerSessionContract.State.STALE_LINK);
        assertThat(SignerSessionContract.validate(signer,
                new SignerSessionContract.CurrentState(3, 12, 0.888889, "OPEN")))
                .isEqualTo(SignerSessionContract.State.STALE_SLOT);
        assertThat(SignerSessionContract.validate(signer,
                new SignerSessionContract.CurrentState(3, 11, 1.0, "OPEN")))
                .isEqualTo(SignerSessionContract.State.STALE_ASPECT);
    }

    @Test
    void rejectedRawIdentityIsAbsentFromLogs(CapturedOutput output) {
        // Given
        repository.status = "OPEN";
        var raw = new IdentifyRequest(
                "SYNTHETIC-ORG-DO-NOT-LOG",
                "SYNTHETIC-JOB-DO-NOT-LOG",
                "SYNTHETIC-NAME-DO-NOT-LOG");

        // When
        failure(() -> service.identify(TOKEN, "198.51.100.7", raw));

        // Then
        assertThat(output.getAll()).doesNotContain(raw.organization(), raw.job(), raw.name(), TOKEN);
    }

    private static PublicIdentifyException failure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected identification denial");
        } catch (PublicIdentifyException exception) {
            return exception;
        }
    }

    private static String token(byte value) {
        var bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class FakeRepository implements PublicIdentifyRepository {
        private final byte[] lookupHash = new byte[] {7, 7, 7};
        private final byte[] exactHmac;
        private String status = "OPEN";
        private boolean submitted;
        private String placement = "PLACED";

        FakeRepository(VersionedCryptoService crypto) {
            exactHmac = crypto.identityHmac(
                    BOARD_ID.toString(), EXACT.organization(), EXACT.job(), EXACT.name());
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
    }
}
