package com.naraesigning.signer.identify;

import com.google.common.net.InetAddresses;
import com.naraesigning.session.SessionCookieActions;
import com.naraesigning.session.SignerSessionContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/links")
@ConditionalOnProperty("spring.datasource.url")
final class PublicIdentifyController {
    private final PublicIdentifyService identify;
    private final SessionCookieActions cookies;

    PublicIdentifyController(PublicIdentifyService identify, SessionCookieActions cookies) {
        this.identify = identify;
        this.cookies = cookies;
    }

    @GetMapping("/{shareToken}")
    PublicLinkResponse link(@PathVariable String shareToken) {
        return identify.link(shareToken);
    }

    @PostMapping("/{shareToken}/identify")
    IdentifyResponse identify(
            @PathVariable String shareToken,
            @RequestBody IdentifyRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var value = identify.identify(shareToken, clientIp(request), body);
        cookies.signerIdentified(request, response);
        SignerSessionContract.issue(request.getSession(false), value);
        return new IdentifyResponse(true);
    }

    private static String clientIp(HttpServletRequest request) {
        var trusted = request.getHeader("X-Narae-Client-IP");
        var value = trusted == null ? request.getRemoteAddr() : trusted;
        if (value == null || !InetAddresses.isInetAddress(value)) {
            throw PublicIdentifyException.invalidClientIp();
        }
        return InetAddresses.forString(value).getHostAddress();
    }
}

record IdentifyResponse(boolean identified) {}
