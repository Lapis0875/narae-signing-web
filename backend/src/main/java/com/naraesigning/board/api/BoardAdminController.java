package com.naraesigning.board.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.naraesigning.background.CanvasChange;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.InvalidBoardTitleException;
import com.naraesigning.board.core.SignatureInkColor;
import com.naraesigning.realtime.PublicBoardRealtimeRegistry;
import com.naraesigning.realtime.PublicDisplayLeaseRegistry;
import com.naraesigning.slot.SlotBounds;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/boards")
@ConditionalOnProperty("spring.datasource.url")
final class BoardAdminController {
    private final BoardAdminFacade facade;
    private final PublicDisplayLeaseRegistry displayLeases;
    private final PublicBoardRealtimeRegistry publicRealtime;

    BoardAdminController(BoardAdminFacade facade) { this(facade, null, null); }

    @Autowired
    BoardAdminController(BoardAdminFacade facade, PublicDisplayLeaseRegistry displayLeases,
            PublicBoardRealtimeRegistry publicRealtime) {
        this.facade = facade;
        this.displayLeases = displayLeases;
        this.publicRealtime = publicRealtime;
    }

    @GetMapping Object list(HttpServletRequest request) { return facade.list(owner(request)); }

    @PostMapping Object create(@RequestBody JsonNode body, HttpServletRequest request) {
        if (!body.isObject() || body.size() != 1 || !body.has("title")) {
            throw new IllegalArgumentException("invalid create body");
        }
        var title = body.get("title");
        return facade.create(owner(request), title.isTextual() ? title.textValue() : null);
    }

    @GetMapping("/{boardId}") Object detail(@PathVariable UUID boardId, HttpServletRequest request) {
        return facade.detail(owner(request), boardId);
    }

    @PatchMapping("/{boardId}") Object patch(@PathVariable UUID boardId, @RequestBody JsonNode body,
            HttpServletRequest request) {
        return facade.patch(owner(request), boardId, boardPatch(body));
    }

    @PatchMapping("/{boardId}/slots/{slotId}") Object updateSlot(@PathVariable UUID boardId,
            @PathVariable UUID slotId, @RequestBody SlotBody body, HttpServletRequest request) {
        return facade.updateSlot(owner(request), boardId, slotId,
                SlotBounds.of(body.x(), body.y(), body.width(), body.height()));
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

    @PostMapping("/{boardId}/display/force-replace")
    ResponseEntity<Void> forceReplaceDisplay(@PathVariable UUID boardId) {
        displayLeases.forceReplace(boardId, () -> publicRealtime.replace(boardId));
        return ResponseEntity.noContent().build();
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

    private static BoardPatch boardPatch(JsonNode body) {
        if (!body.isObject()) throw new IllegalArgumentException("invalid board patch body");
        if (body.isEmpty()) throw new BoardPatchInputException("BOARD_PATCH_EMPTY");
        for (var field : body.properties()) {
            if (!Set.of("title", "signatureInkColor").contains(field.getKey())) {
                throw new IllegalArgumentException("unknown board patch field");
            }
        }
        boolean titlePresent = body.has("title");
        String title = titlePresent && body.get("title").isTextual() ? body.get("title").textValue() : null;
        boolean colorPresent = body.has("signatureInkColor");
        SignatureInkColor color = colorPresent ? signatureInkColor(body.get("signatureInkColor")) : null;
        if (titlePresent && (title == null || title.isBlank()
                || title.codePointCount(0, title.length()) > 120
                || title.codePoints().anyMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff))) {
            throw new InvalidBoardTitleException();
        }
        return new BoardPatch(title, titlePresent, color, colorPresent);
    }

    private static SignatureInkColor signatureInkColor(JsonNode value) {
        if (value != null && value.isTextual()) {
            if ("black".equals(value.textValue())) return SignatureInkColor.BLACK;
            if ("white".equals(value.textValue())) return SignatureInkColor.WHITE;
        }
        throw new BoardPatchInputException("SIGNATURE_INK_COLOR_INVALID");
    }
    record SlotBody(BigDecimal x, BigDecimal y, BigDecimal width, BigDecimal height) {
        SlotBody {
            if (x == null || y == null || width == null || height == null) {
                throw new IllegalArgumentException("missing slot geometry");
            }
        }

        @JsonAnySetter
        void rejectUnknownField(String field, JsonNode value) {
            throw new IllegalArgumentException("unknown slot patch field");
        }
    }
}
