package com.naraesigning.board.api;

import com.naraesigning.background.CanvasChange;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.slot.SlotBackground;
import com.naraesigning.slot.SlotBounds;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/boards")
@ConditionalOnProperty("spring.datasource.url")
final class BoardAdminController {
    private final BoardAdminFacade facade;

    BoardAdminController(BoardAdminFacade facade) { this.facade = facade; }

    @GetMapping Object list(HttpServletRequest request) { return facade.list(owner(request)); }

    @PostMapping Object create(@RequestBody TitleBody body, HttpServletRequest request) {
        return facade.create(owner(request), body.validTitle());
    }

    @GetMapping("/{boardId}") Object detail(@PathVariable UUID boardId, HttpServletRequest request) {
        return facade.detail(owner(request), boardId);
    }

    @PatchMapping("/{boardId}") Object rename(@PathVariable UUID boardId, @RequestBody TitleBody body,
            HttpServletRequest request) {
        return facade.rename(owner(request), boardId, body.validTitle());
    }

    @PatchMapping("/{boardId}/slots/{slotId}") Object updateSlot(@PathVariable UUID boardId,
            @PathVariable UUID slotId, @RequestBody SlotBody body, HttpServletRequest request) {
        return facade.updateSlot(owner(request), boardId, slotId,
                SlotBounds.of(body.x(), body.y(), body.width(), body.height()), background(body.background()));
    }

    @DeleteMapping("/{boardId}/slots/{slotId}") ResponseEntity<Void> deleteSlot(@PathVariable UUID boardId,
            @PathVariable UUID slotId, HttpServletRequest request) {
        facade.deleteSlot(owner(request), boardId, slotId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{boardId}/slots/{slotId}/reset-signature") Object resetSignature(
            @PathVariable UUID boardId, @PathVariable UUID slotId, HttpServletRequest request) {
        return facade.resetSignature(owner(request), boardId, slotId);
    }

    @GetMapping("/{boardId}/share") Object share(@PathVariable UUID boardId, HttpServletRequest request) {
        return facade.share(owner(request), boardId);
    }

    @PostMapping("/{boardId}/share/reissue") Object reissueShare(
            @PathVariable UUID boardId, HttpServletRequest request) {
        return facade.reissueShare(owner(request), boardId);
    }

    @PostMapping(path = "/{boardId}/background", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Object replaceBackground(@PathVariable UUID boardId, @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean adoptSourceRatio,
            @RequestParam(defaultValue = "false") boolean confirmed, HttpServletRequest request) {
        try {
            var change = adoptSourceRatio ? CanvasChange.adoptSourceRatio(confirmed) : CanvasChange.keep();
            return facade.replaceBackground(owner(request), boardId, file.getBytes(), file.getContentType(), change);
        } catch (IOException exception) {
            throw new BoardLifecycleException("BACKGROUND_INVALID");
        }
    }

    @GetMapping("/{boardId}/background")
    ResponseEntity<byte[]> background(@PathVariable UUID boardId, HttpServletRequest request) {
        var current = facade.currentBackground(owner(request), boardId);
        if (current.isEmpty()) return ResponseEntity.noContent().build();
        var content = current.orElseThrow();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.mimeType())).body(content.bytes());
    }

    @PostMapping("/{boardId}/open") Object open(@PathVariable UUID boardId, HttpServletRequest request) {
        return facade.open(owner(request), boardId);
    }

    @PostMapping("/{boardId}/close") Object close(@PathVariable UUID boardId, HttpServletRequest request) {
        return facade.close(owner(request), boardId);
    }

    @PostMapping("/{boardId}/reopen") Object reopen(@PathVariable UUID boardId, HttpServletRequest request) {
        return facade.reopen(owner(request), boardId);
    }

    private static BoardOwner owner(HttpServletRequest request) {
        return (BoardOwner) request.getAttribute(AdminBoardFilter.OWNER_ATTRIBUTE);
    }

    private static SlotBackground background(String value) {
        try {
            return SlotBackground.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new BoardLifecycleException("SLOT_BACKGROUND_INVALID");
        }
    }

    record TitleBody(String title, UUID ownerId, String status) {
        String validTitle() {
            if (ownerId != null || status != null) throw new IllegalArgumentException("client-owned board fields");
            return title;
        }
    }
    record SlotBody(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height, String background) {}
}
