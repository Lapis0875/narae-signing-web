package com.naraesigning.signature;

import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.session.SignerSessionContract;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/signing-session")
@ConditionalOnProperty("spring.datasource.url")
final class SignatureDraftController {
    private final LiveSignatureService drafts;
    private final SignatureWireParser parser;
    private final Clock clock;

    SignatureDraftController(LiveSignatureService drafts, SignatureWireParser parser, Clock clock) {
        this.drafts = drafts;
        this.parser = parser;
        this.clock = clock;
    }

    @PutMapping(value = "/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LiveSignatureRegistry.Version> update(HttpServletRequest request) throws IOException {
        var payload = parser.parse(request.getInputStream());
        var httpSession = request.getSession(false);
        try {
            var session = SignatureSession.from(httpSession, clock.instant());
            var version = drafts.update(
                    session, SignerSessionContract.ensureDraftClaimId(httpSession), payload, clock.instant());
            return ResponseEntity.ok(version);
        } catch (SignatureSubmitException exception) {
            if (exception.clearsSession() && httpSession != null) httpSession.invalidate();
            throw exception;
        }
    }

    @PostMapping(value = "/draft/delta", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LiveSignatureRegistry.DeltaResult> delta(HttpServletRequest request) throws IOException {
        var delta = parser.parseDelta(request.getInputStream());
        var httpSession = request.getSession(false);
        try {
            var result = drafts.delta(SignatureSession.from(httpSession, clock.instant()),
                    SignerSessionContract.ensureDraftClaimId(httpSession), delta, clock.instant());
            return ResponseEntity.ok(result);
        } catch (SignatureSubmitException exception) {
            if (exception.clearsSession() && httpSession != null) httpSession.invalidate();
            throw exception;
        }
    }

    @PostMapping("/draft/clear")
    ResponseEntity<Void> clear(HttpServletRequest request) {
        var httpSession = request.getSession(false);
        try {
            drafts.clearDraft(SignatureSession.from(httpSession, clock.instant()),
                    SignerSessionContract.draftClaimId(httpSession), clock.instant());
            return ResponseEntity.noContent().build();
        } catch (SignatureSubmitException exception) {
            if (exception.clearsSession() && httpSession != null) httpSession.invalidate();
            throw exception;
        }
    }

    @PostMapping("/cancel")
    ResponseEntity<Void> cancel(HttpServletRequest request) {
        var httpSession = request.getSession(false);
        try {
            drafts.cancel(SignatureSession.from(httpSession, clock.instant()),
                    SignerSessionContract.draftClaimId(httpSession));
            SignerSessionContract.clearDraftClaim(httpSession);
            return ResponseEntity.noContent().build();
        } catch (SignatureSubmitException exception) {
            if (exception.clearsSession() && httpSession != null) httpSession.invalidate();
            throw exception;
        }
    }
}
