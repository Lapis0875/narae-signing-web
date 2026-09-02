package com.naraesigning.signature;

import com.naraesigning.session.SignerSessionContract;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/signing-session")
@ConditionalOnProperty("spring.datasource.url")
final class SignatureSubmitController {
    private final SignatureSubmitService signatures;
    private final SignatureWireParser parser;
    private final Clock clock;

    SignatureSubmitController(SignatureSubmitService signatures, SignatureWireParser parser, Clock clock) {
        this.signatures = signatures;
        this.parser = parser;
        this.clock = clock;
    }

    @PostMapping(value = "/signature", consumes = MediaType.APPLICATION_JSON_VALUE)
    SignatureSubmitResponse submit(HttpServletRequest request) throws IOException {
        var payload = parser.parse(request.getInputStream());
        var session = request.getSession(false);
        try {
            signatures.submit(SignatureSession.from(session, clock.instant()), payload,
                    SignerSessionContract.ensureDraftClaimId(session), clock.instant());
            SignerSessionContract.clearDraftClaim(session);
            return new SignatureSubmitResponse(true);
        } catch (SignatureSubmitException exception) {
            if (exception.clearsSession() && session != null) session.invalidate();
            throw exception;
        }
    }
}

record SignatureSubmitResponse(boolean submitted) {}
