package com.naraesigning.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.background.CanvasChange;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.BoardView;
import com.naraesigning.slot.SlotBackground;
import com.naraesigning.slot.SlotBounds;
import com.naraesigning.slot.SlotService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class JdbcBoardAdminFacadeMutationEventTest {
    private static final UUID BOARD = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SLOT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private BoardService boards;
    private SlotService slots;
    private BackgroundAssetService backgrounds;
    private DeferredTransactions transactions;
    private List<Object> published;
    private JdbcBoardAdminFacade facade;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        boards = mock(BoardService.class);
        slots = mock(SlotService.class);
        backgrounds = mock(BackgroundAssetService.class);
        var jdbc = mock(JdbcOperations.class);
        transactions = new DeferredTransactions();
        published = new ArrayList<>();
        var publisher = mock(ApplicationEventPublisher.class, invocation -> {
            published.add(invocation.getArgument(0));
            return null;
        });
        doReturn("DRAFT").when(jdbc).query(anyString(), any(ResultSetExtractor.class), eq(BOARD));
        when(boards.detail(any(), eq(BOARD))).thenReturn(new BoardView(
                BOARD, "Board", "설정 중", 800, 600, 1, Instant.EPOCH, Instant.EPOCH));
        facade = new JdbcBoardAdminFacade(boards, slots, backgrounds, jdbc, transactions, publisher);
    }

    @Test
    void eachMutationPublishesItsMinimalEventOnlyAfterCommit() throws Exception {
        // Given / When / Then
        assertAfterCommit("layout-updated", () -> facade.updateSlot(owner(), BOARD, SLOT, bounds(), SlotBackground.WHITE));
        assertAfterCommit("layout-updated", () -> facade.deleteSlot(owner(), BOARD, SLOT));
        assertAfterCommit("signature-reset", () -> facade.resetSignature(owner(), BOARD, SLOT));
        assertAfterCommit("background-updated", () -> facade.replaceBackground(
                owner(), BOARD, new byte[] {1}, "image/png", new CanvasChange(false, false)));
        assertAfterCommit("board-updated", () -> facade.rename(owner(), BOARD, "Renamed"));
    }

    @Test
    void failedAndRolledBackMutationsPublishNothing() {
        // Given
        doThrow(new IllegalStateException("write failed")).when(slots)
                .updateVisual(eq(BOARD), eq(SLOT), any(), eq(SlotBackground.WHITE));

        // When / Then
        assertThatThrownBy(() -> facade.updateSlot(owner(), BOARD, SLOT, bounds(), SlotBackground.WHITE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(published).isEmpty();
        assertThat(transactions.pendingCount()).isZero();

        facade.deleteSlot(owner(), BOARD, SLOT);
        transactions.rollback();
        assertThat(published).isEmpty();
    }

    private void assertAfterCommit(String expectedType, Runnable mutation) throws Exception {
        int before = published.size();
        mutation.run();
        assertThat(published).hasSize(before);
        transactions.commit();
        assertThat(published).hasSize(before + 1);
        var event = published.getLast();
        Method boardId = event.getClass().getMethod("boardId");
        Method type = event.getClass().getMethod("type");
        assertThat(boardId.invoke(event)).isEqualTo(BOARD);
        assertThat(type.invoke(event)).isEqualTo(expectedType);
    }

    private static BoardOwner owner() { return mock(BoardOwner.class); }

    private static SlotBounds bounds() {
        return new SlotBounds(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static final class DeferredTransactions implements TransactionOperations {
        private final List<List<TransactionSynchronization>> pending = new ArrayList<>();

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                T result = action.doInTransaction(mock(TransactionStatus.class));
                pending.add(List.copyOf(TransactionSynchronizationManager.getSynchronizations()));
                return result;
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        void commit() { pending.removeFirst().forEach(TransactionSynchronization::afterCommit); }
        void rollback() { pending.removeFirst().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)); }
        int pendingCount() { return pending.size(); }
    }
}
