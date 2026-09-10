package com.naraesigning.board.api;

import com.naraesigning.background.BackgroundAssetView;
import com.naraesigning.background.BackgroundContent;
import com.naraesigning.background.CanvasChange;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardShare;
import com.naraesigning.board.core.BoardView;
import com.naraesigning.board.core.CreatedBoard;
import com.naraesigning.board.core.SignatureInkColor;
import com.naraesigning.slot.Slot;
import com.naraesigning.slot.SlotBounds;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface BoardAdminFacade {
    void authorize(BoardOwner owner, UUID boardId);
    List<BoardView> list(BoardOwner owner);
    CreatedBoard create(BoardOwner owner, String title);
    BoardView detail(BoardOwner owner, UUID boardId);
    BoardView patch(BoardOwner owner, UUID boardId, BoardPatch patch);
    Slot updateSlot(BoardOwner owner, UUID boardId, UUID slotId, SlotBounds bounds);
    Slot deleteSlot(BoardOwner owner, UUID boardId, UUID slotId);
    Slot resetSignature(BoardOwner owner, UUID boardId, UUID slotId);
    BoardShare share(BoardOwner owner, UUID boardId);
    BoardShare reissueShare(BoardOwner owner, UUID boardId);
    BackgroundAssetView replaceBackground(BoardOwner owner, UUID boardId, byte[] bytes, String mimeType,
            CanvasChange change);
    default Optional<BackgroundContent> currentBackground(BoardOwner owner, UUID boardId) {
        return Optional.empty();
    }
    BoardView open(BoardOwner owner, UUID boardId);
    BoardView close(BoardOwner owner, UUID boardId);
    BoardView reopen(BoardOwner owner, UUID boardId);
}

record BoardPatch(String title, boolean titlePresent, SignatureInkColor signatureInkColor,
        boolean signatureInkColorPresent) {}
