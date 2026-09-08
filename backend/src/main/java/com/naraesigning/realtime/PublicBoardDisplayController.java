package com.naraesigning.realtime;

import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.PublicBoardLink;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/public/links/{shareToken}/display")
@ConditionalOnProperty("spring.datasource.url")
final class PublicBoardDisplayController {
    static final String COOKIE = "public_display";
    static final String BLOCKED_MESSAGE = "다른 화면에서 이미 보드를 표시하고 있습니다.";
    private final BoardService boards;
    private final BoardSnapshotService snapshots;
    private final BackgroundAssetService backgrounds;
    private final PublicBoardRealtimeRegistry realtime;
    private final PublicDisplayLeaseRegistry leases;

    PublicBoardDisplayController(
            BoardService boards,
            BoardSnapshotService snapshots,
            BackgroundAssetService backgrounds,
            PublicBoardRealtimeRegistry realtime,
            PublicDisplayLeaseRegistry leases) {
        this.boards = boards;
        this.snapshots = snapshots;
        this.backgrounds = backgrounds;
        this.realtime = realtime;
        this.leases = leases;
    }

    @PostMapping("/claim")
    ResponseEntity<?> claim(@PathVariable String shareToken,
            @CookieValue(name = COOKIE, required = false) String owner) {
        var result = leases.claim(visibleLink(shareToken).boardId(), owner);
        if (!result.acquired()) throw blocked();
        return ResponseEntity.noContent().header("Set-Cookie", COOKIE + "=" + result.owner()
                + "; Path=/api/v1/public/links; HttpOnly; Secure; SameSite=Lax").build();
    }

    @PostMapping("/heartbeat")
    ResponseEntity<Void> heartbeat(@PathVariable String shareToken,
            @CookieValue(name = COOKIE, required = false) String owner) {
        if (!leases.heartbeat(visibleLink(shareToken).boardId(), owner)) throw blocked();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/release")
    ResponseEntity<Void> release(@PathVariable String shareToken,
            @CookieValue(name = COOKIE, required = false) String owner) {
        leases.release(link(shareToken).boardId(), owner);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/title")
    Map<String, String> title(@PathVariable String shareToken,
            @CookieValue(name = COOKIE, required = false) String owner) {
        return Map.of("title", ownedLink(shareToken, owner).title());
    }

    @GetMapping("/snapshot")
    BoardSnapshot snapshot(@PathVariable String shareToken,
            @CookieValue(name = COOKIE, required = false) String owner) {
        try {
            return snapshots.readPublic(ownedLink(shareToken, owner).boardId());
        } catch (SnapshotUnavailableException exception) {
            throw unavailable();
        }
    }

    @GetMapping("/background")
    ResponseEntity<byte[]> background(@PathVariable String shareToken,
            @CookieValue(name = COOKIE, required = false) String owner) {
        var content = backgrounds.current(ownedLink(shareToken, owner).boardId());
        if (content.isEmpty()) return ResponseEntity.noContent().build();
        var value = content.orElseThrow();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.mimeType())).body(value.bytes());
    }

    @GetMapping("/events")
    SseEmitter events(@PathVariable String shareToken,
            @CookieValue(name = COOKIE, required = false) String owner, HttpServletResponse response) {
        var link = ownedLink(shareToken, owner);
        var emitter = new SseEmitter(0L);
        if (!leases.registerIfOwned(link.boardId(), owner,
                () -> realtime.register(link.boardId(),
                        () -> current(shareToken, link) && leases.owns(link.boardId(), owner), emitter))) {
            emitter.complete();
        }
        response.setHeader("X-Accel-Buffering", "no");
        return emitter;
    }

    private PublicBoardLink link(String shareToken) {
        return boards.findPublic(shareToken).orElseThrow(this::unavailable);
    }

    private PublicBoardLink visibleLink(String shareToken) {
        var link = link(shareToken);
        if (!snapshots.publiclyVisible(link.boardId())) throw unavailable();
        return link;
    }

    private PublicBoardLink ownedLink(String shareToken, String owner) {
        var link = visibleLink(shareToken);
        if (!leases.owns(link.boardId(), owner)) throw blocked();
        return link;
    }

    private boolean current(String shareToken, PublicBoardLink expected) {
        return boards.findPublic(shareToken)
                .map(link -> link.boardId().equals(expected.boardId())
                        && link.shareLinkVersion() == expected.shareLinkVersion()
                        && snapshots.publiclyVisible(link.boardId()))
                .orElse(false);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private DisplayBlockedException blocked() { return new DisplayBlockedException(); }

    @ExceptionHandler(DisplayBlockedException.class)
    ResponseEntity<Map<String, String>> blockedResponse() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "DISPLAY_ALREADY_CONNECTED", "message", BLOCKED_MESSAGE));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> unavailableResponse() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", "PUBLIC_DISPLAY_UNAVAILABLE"));
    }

    private static final class DisplayBlockedException extends RuntimeException {}
}
