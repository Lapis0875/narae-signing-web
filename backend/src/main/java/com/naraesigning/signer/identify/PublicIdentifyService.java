package com.naraesigning.signer.identify;

import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.roster.RosterIdentity;
import com.naraesigning.roster.RosterInputException;
import com.naraesigning.session.SignerSessionContract;
import com.naraesigning.slot.CanonicalAspect;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

final class PublicIdentifyService {
    private static final byte[] INVALID_LINK_RATE_KEY =
            "invalid-public-link".getBytes(StandardCharsets.US_ASCII);
    private final PublicLinkLookup links;
    private final VersionedCryptoService crypto;
    private final PublicIdentifyRateLimiter limiter;

    PublicIdentifyService(
            PublicLinkLookup links,
            VersionedCryptoService crypto,
            PublicIdentifyRateLimiter limiter) {
        this.links = links;
        this.crypto = crypto;
        this.limiter = limiter;
    }

    PublicLinkResponse link(String rawToken) {
        return links.resolve(rawToken)
                .map(link -> new PublicLinkResponse(state(link.status()), link.title()))
                .orElseGet(() -> new PublicLinkResponse(PublicLinkState.INVALID, null));
    }

    SignerSessionContract.Value identify(
            String rawToken, String clientIp, IdentifyRequest request) {
        var link = links.resolve(rawToken).orElse(null);
        var rate = limiter.admit(link == null ? INVALID_LINK_RATE_KEY : link.lookupHash(), clientIp);
        if (!rate.allowed()) {
            throw PublicIdentifyException.rateLimited(rate.retryAfterSeconds());
        }
        if (link == null) {
            throw PublicIdentifyException.denied();
        }
        if (!"OPEN".equals(link.status())) {
            throw PublicIdentifyException.denied();
        }
        var identity = identity(request);
        var identityHmac = crypto.identityHmac(
                link.boardId().toString(), identity.organization(), identity.job(), identity.name());
        var signer = links.findSigner(link, identityHmac)
                .orElseThrow(PublicIdentifyException::denied);
        if (!"OPEN".equals(signer.boardStatus())
                || signer.linkVersion() != link.linkVersion()
                || signer.submitted()
                || !"PLACED".equals(signer.placementStatus())
                || signer.slotWidth() == null
                || signer.slotHeight() == null) {
            throw PublicIdentifyException.denied();
        }
        double aspect = CanonicalAspect.from(
                        signer.slotWidth().multiply(BigDecimal.valueOf(signer.canvasWidth())),
                        signer.slotHeight().multiply(BigDecimal.valueOf(signer.canvasHeight())))
                .value().doubleValue();
        return new SignerSessionContract.Value(
                signer.boardId(), signer.slotId(), signer.linkVersion(),
                signer.slotRevision(), aspect, limiter.now());
    }

    private static RosterIdentity identity(IdentifyRequest request) {
        try {
            if (request == null) {
                throw PublicIdentifyException.denied();
            }
            return new RosterIdentity(request.organization(), request.job(), request.name());
        } catch (RosterInputException exception) {
            throw PublicIdentifyException.denied();
        }
    }

    private static PublicLinkState state(String status) {
        return switch (status) {
            case "DRAFT" -> PublicLinkState.SETUP;
            case "OPEN" -> PublicLinkState.OPEN;
            case "CLOSED" -> PublicLinkState.CLOSED;
            default -> PublicLinkState.INVALID;
        };
    }
}

record IdentifyRequest(String organization, String job, String name) {}

record PublicLinkResponse(PublicLinkState state, String title) {}

enum PublicLinkState {
    SETUP,
    OPEN,
    CLOSED,
    INVALID
}
