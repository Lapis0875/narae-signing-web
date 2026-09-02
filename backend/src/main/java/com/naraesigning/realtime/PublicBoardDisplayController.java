package com.naraesigning.realtime;

import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.PublicBoardLink;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/public/links/{shareToken}/display")
@ConditionalOnProperty("spring.datasource.url")
final class PublicBoardDisplayController {
    private final BoardService boards;
    private final BoardSnapshotService snapshots;
    private final BackgroundAssetService backgrounds;
    private final PublicBoardRealtimeRegistry realtime;

    PublicBoardDisplayController(
            BoardService boards,
            BoardSnapshotService snapshots,
            BackgroundAssetService backgrounds,
            PublicBoardRealtimeRegistry realtime) {
        this.boards = boards;
        this.snapshots = snapshots;
        this.backgrounds = backgrounds;
        this.realtime = realtime;
    }

    @GetMapping("/snapshot")
    BoardSnapshot snapshot(@PathVariable String shareToken) {
        try {
            return snapshots.readPublic(visibleLink(shareToken).boardId());
        } catch (SnapshotUnavailableException exception) {
            throw unavailable();
        }
    }

    @GetMapping("/background")
    ResponseEntity<byte[]> background(@PathVariable String shareToken) {
        var content = backgrounds.current(visibleLink(shareToken).boardId());
        if (content.isEmpty()) return ResponseEntity.noContent().build();
        var value = content.orElseThrow();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.mimeType())).body(value.bytes());
    }

    @GetMapping("/events")
    SseEmitter events(@PathVariable String shareToken, HttpServletResponse response) {
        var link = visibleLink(shareToken);
        var emitter = new SseEmitter(0L);
        realtime.register(link.boardId(), () -> current(shareToken, link), emitter);
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
}
