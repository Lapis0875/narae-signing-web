package com.naraesigning.board.api;

import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.background.BackgroundAssetView;
import com.naraesigning.background.BackgroundContent;
import com.naraesigning.background.CanvasChange;
import com.naraesigning.background.CanvasSize;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.BoardShare;
import com.naraesigning.board.core.BoardView;
import com.naraesigning.board.core.CreatedBoard;
import com.naraesigning.realtime.BoardMutationEvent;
import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.slot.Slot;
import com.naraesigning.slot.SlotBackground;
import com.naraesigning.slot.SlotBounds;
import com.naraesigning.slot.SlotService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class JdbcBoardAdminFacade implements BoardAdminFacade {
    private final BoardService boards;
    private final SlotService slots;
    private final BackgroundAssetService backgrounds;
    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final ApplicationEventPublisher events;
    private final LiveSignatureRegistry drafts;

    JdbcBoardAdminFacade(BoardService boards, SlotService slots, BackgroundAssetService backgrounds,
            JdbcOperations jdbc, TransactionOperations transactions, ApplicationEventPublisher events,
            LiveSignatureRegistry drafts) {
        this.boards = boards;
        this.slots = slots;
        this.backgrounds = backgrounds;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.events = events;
        this.drafts = drafts;
    }

    @Override public void authorize(BoardOwner owner, UUID boardId) { boards.detail(owner, boardId); }
    @Override public List<BoardView> list(BoardOwner owner) { return boards.list(owner); }
    @Override public CreatedBoard create(BoardOwner owner, String title) { return boards.create(owner, title); }
    @Override public BoardView detail(BoardOwner owner, UUID boardId) { return boards.detail(owner, boardId); }
    @Override public BoardView rename(BoardOwner owner, UUID boardId, String title) {
        return transactions.execute(status -> {
            var renamed = boards.rename(owner, boardId, title);
            publishAfterCommit(new BoardMutationEvent(boardId, "board-updated"));
            return renamed;
        });
    }

    @Override
    public Slot updateSlot(BoardOwner owner, UUID boardId, UUID slotId,
            SlotBounds bounds, SlotBackground background) {
        return transactions.execute(status -> {
            requireEditable(owner, boardId);
            var updated = slots.updateVisual(boardId, slotId, bounds, background);
            publishAfterCommit(new BoardMutationEvent(boardId, "layout-updated"));
            return updated;
        });
    }

    @Override
    public Slot deleteSlot(BoardOwner owner, UUID boardId, UUID slotId) {
        return transactions.execute(status -> {
            requireEditable(owner, boardId);
            var deleted = slots.delete(boardId, slotId);
            publishAfterCommit(new BoardMutationEvent(boardId, "layout-updated"));
            return deleted;
        });
    }

    @Override
    public Slot resetSignature(BoardOwner owner, UUID boardId, UUID slotId) {
        return transactions.execute(status -> {
            boards.detail(owner, boardId);
            var reset = slots.resetSignature(boardId, slotId);
            publishAfterCommit(new BoardMutationEvent(boardId, "signature-reset"));
            return reset;
        });
    }

    @Override
    public BoardShare share(BoardOwner owner, UUID boardId) {
        var board = boards.detail(owner, boardId);
        return new BoardShare(board.shareLinkVersion(), boards.currentShare(owner, boardId));
    }

    @Override public BoardShare reissueShare(BoardOwner owner, UUID boardId) {
        return boards.reissueShare(owner, boardId,
                () -> publishAfterCommit(new BoardMutationEvent(boardId, "share-reissued")));
    }

    @Override
    public BackgroundAssetView replaceBackground(BoardOwner owner, UUID boardId, byte[] bytes,
            String mimeType, CanvasChange change) {
        return transactions.execute(status -> {
            var board = boards.detail(owner, boardId);
            if (!"설정 중".equals(board.status())) throw new BoardLifecycleException("BOARD_NOT_DRAFT");
            var replaced = backgrounds.replace(boardId, bytes, mimeType,
                    new CanvasSize(board.canvasWidth(), board.canvasHeight()), change);
            publishAfterCommit(new BoardMutationEvent(boardId, "background-updated"));
            return replaced;
        });
    }

    @Override public Optional<BackgroundContent> currentBackground(BoardOwner owner, UUID boardId) {
        boards.detail(owner, boardId);
        return backgrounds.current(boardId);
    }

    @Override public BoardView open(BoardOwner owner, UUID boardId) {
        return transition(owner, boardId, "DRAFT", "OPEN", true);
    }

    @Override public BoardView close(BoardOwner owner, UUID boardId) {
        return transition(owner, boardId, "OPEN", "CLOSED", false);
    }

    @Override public BoardView reopen(BoardOwner owner, UUID boardId) {
        return transition(owner, boardId, "CLOSED", "OPEN", true);
    }

    private void requireEditable(BoardOwner owner, UUID boardId) {
        boards.detail(owner, boardId);
        var status = jdbc.query("select status from board where id = ? for update",
                result -> result.next() ? result.getString(1) : null, boardId);
        if (!"DRAFT".equals(status) && !"OPEN".equals(status)) {
            throw new BoardLifecycleException("BOARD_NOT_EDITABLE");
        }
    }

    private BoardView transition(BoardOwner owner, UUID boardId, String expected, String changed,
            boolean requireCompleteLayout) {
        return transactions.execute(transaction -> {
            var board = boards.detail(owner, boardId);
            var current = jdbc.query("select status from board where id = ? for update",
                    result -> result.next() ? result.getString(1) : null, boardId);
            if (!expected.equals(current)) throw new BoardLifecycleException("BOARD_STATE_CONFLICT");
            if (requireCompleteLayout) requireOpenPrerequisites(boardId, board.title());
            if ("CLOSED".equals(changed)) fenceDraftsUntilCompletion(boardId);
            if (jdbc.update("update board set status = ?, updated_at = current_timestamp where id = ? and status = ?",
                    changed, boardId, expected) != 1) {
                throw new BoardLifecycleException("BOARD_STATE_CONFLICT");
            }
            publishAfterCommit(new BoardLifecycleEvent(boardId, changed));
            return boards.detail(owner, boardId);
        });
    }

    private void requireOpenPrerequisites(UUID boardId, String title) {
        var counts = jdbc.queryForMap("""
                select count(*) roster_count,
                    count(*) filter (where s.placement_status <> 'PLACED') unplaced_count
                from roster_entry r join signature_slot s on s.roster_entry_id = r.id
                where r.board_id = ?
                """, boardId);
        if (title == null || title.isBlank() || ((Number) counts.get("roster_count")).intValue() == 0
                || ((Number) counts.get("unplaced_count")).intValue() != 0) {
            throw new BoardLifecycleException("BOARD_OPEN_INCOMPLETE");
        }
    }

    private void publishAfterCommit(Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Board event requires transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { events.publishEvent(event); }
        });
    }

    private void fenceDraftsUntilCompletion(UUID boardId) {
        drafts.fenceBoard(boardId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { drafts.invalidateBoard(boardId); }
            @Override public void afterCompletion(int status) { drafts.unfenceBoard(boardId); }
        });
    }
}
