package com.naraesigning.board.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.background.BackgroundAssetService;
import com.naraesigning.background.CanvasChange;
import com.naraesigning.board.core.BoardOwner;
import com.naraesigning.board.core.BoardService;
import com.naraesigning.board.core.BoardShare;
import com.naraesigning.board.core.BoardView;
import com.naraesigning.realtime.DraftDelta;
import com.naraesigning.realtime.LiveSignatureRegistry;
import com.naraesigning.slot.SlotBackground;
import com.naraesigning.slot.SlotBounds;
import com.naraesigning.slot.SlotService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private static final UUID CLAIM = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_BOARD = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHER_SLOT = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID OTHER_CLAIM = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private BoardService boards;
    private SlotService slots;
    private BackgroundAssetService backgrounds;
    private JdbcOperations jdbc;
    private LiveSignatureRegistry drafts;
    private DeferredTransactions transactions;
    private List<Object> published;
    private JdbcBoardAdminFacade facade;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        boards = mock(BoardService.class);
        slots = mock(SlotService.class);
        backgrounds = mock(BackgroundAssetService.class);
        jdbc = mock(JdbcOperations.class);
        drafts = mock(LiveSignatureRegistry.class);
        transactions = new DeferredTransactions();
        published = new ArrayList<>();
        var publisher = mock(ApplicationEventPublisher.class, invocation -> {
            published.add(invocation.getArgument(0));
            return null;
        });
        doReturn("DRAFT").when(jdbc).query(anyString(), any(ResultSetExtractor.class), eq(BOARD));
        when(boards.detail(any(), eq(BOARD))).thenReturn(new BoardView(
                BOARD, "Board", "설정 중", 800, 600, 1, Instant.EPOCH, Instant.EPOCH));
        facade = new JdbcBoardAdminFacade(boards, slots, backgrounds, jdbc, transactions, publisher,
                drafts);
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

    @Test
    void committedShareReissueInvalidatesWarmAndPendingDraftsForOnlyThatBoard() throws Exception {
        // Given
        var realDrafts = registry();
        var version = realDrafts.update(BOARD, SLOT, CLAIM, payload(1), NOW.plusSeconds(90));
        realDrafts.update(OTHER_BOARD, OTHER_SLOT, OTHER_CLAIM, payload(2), NOW.plusSeconds(90));
        when(boards.reissueShare(any(), eq(BOARD), any(Runnable.class))).thenAnswer(invocation ->
                transactions.execute(status -> {
                    invocation.<Runnable>getArgument(2).run();
                    return new BoardShare(2, "new-share-token");
                }));
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof com.naraesigning.realtime.BoardMutationEvent mutation) {
                realDrafts.invalidateBoard(mutation);
            }
        };
        var reissueFacade = new JdbcBoardAdminFacade(boards, slots, backgrounds, jdbc, transactions,
                publisher, realDrafts);

        try (var pending = realDrafts.beginFullUpdate(BOARD, SLOT)) {
            // When
            reissueFacade.reissueShare(owner(), BOARD);

            // Then: the database operation alone does not revoke state before commit.
            assertThat(realDrafts.snapshot(BOARD, SLOT).signature()).isNotNull();
            transactions.commit();
            assertThatThrownBy(() -> realDrafts.apply(BOARD, SLOT, CLAIM,
                    new DraftDelta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, 1,
                            List.of(new DraftDelta.Point(3, 4))),
                    () -> NOW.plusSeconds(90)))
                    .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
            assertThat(realDrafts.snapshot(BOARD, SLOT).signature()).isNull();
            assertThat(realDrafts.snapshot(OTHER_BOARD, OTHER_SLOT).signature()).isNotNull();
            assertThatThrownBy(() -> realDrafts.update(
                    BOARD, SLOT, CLAIM, payload(3), NOW.plusSeconds(90), pending))
                    .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
            System.out.println("QA_SHARE_REISSUE facade=completed oldCachedDelta=out_of_sync "
                    + "postCommitDraft=absent unrelatedDraft=visible pendingPut=out_of_sync");
        }
    }

    @Test
    void rolledBackShareReissuePreservesWarmDraftAndPendingPublication() throws Exception {
        // Given
        var realDrafts = registry();
        realDrafts.update(BOARD, SLOT, CLAIM, payload(1), NOW.plusSeconds(90));
        when(boards.reissueShare(any(), eq(BOARD), any(Runnable.class))).thenAnswer(invocation ->
                transactions.execute(status -> {
                    invocation.<Runnable>getArgument(2).run();
                    return new BoardShare(2, "new-share-token");
                }));
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof com.naraesigning.realtime.BoardMutationEvent mutation) {
                realDrafts.invalidateBoard(mutation);
            }
        };
        var reissueFacade = new JdbcBoardAdminFacade(boards, slots, backgrounds, jdbc, transactions,
                publisher, realDrafts);

        try (var pending = realDrafts.beginFullUpdate(BOARD, SLOT)) {
            // When
            reissueFacade.reissueShare(owner(), BOARD);
            transactions.rollback();

            // Then
            assertThat(realDrafts.snapshot(BOARD, SLOT).signature()).isNotNull();
            assertThat(realDrafts.update(BOARD, SLOT, CLAIM, payload(3), NOW.plusSeconds(90), pending))
                    .isNotNull();
            System.out.println("QA_SHARE_REISSUE rollback cachedDraft=visible pendingPut=accepted");
        }
    }

    @Test
    void closeFencesCachedDraftsBeforeWritingTheTerminalStatus() {
        // Given
        doReturn("OPEN").when(jdbc).query(anyString(), any(ResultSetExtractor.class), eq(BOARD));
        doReturn(1).when(jdbc).update(anyString(), eq("CLOSED"), eq(BOARD), eq("OPEN"));

        // When
        facade.close(owner(), BOARD);

        // Then
        var order = inOrder(drafts, jdbc);
        order.verify(drafts).fenceBoard(BOARD);
        order.verify(jdbc).update(anyString(), eq("CLOSED"), eq(BOARD), eq("OPEN"));
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

    private static LiveSignatureRegistry registry() throws Exception {
        var constructor = LiveSignatureRegistry.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var parameterTypes = constructor.getParameterTypes();
        return (LiveSignatureRegistry) constructor.newInstance(new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), mock(parameterTypes[2]), mock(parameterTypes[3]));
    }

    private static byte[] payload(int x) {
        return ("{\"version\":1,\"strokes\":[{\"points\":[{\"x\":" + x + ",\"y\":2}]}]}")
                .getBytes(StandardCharsets.UTF_8);
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

        void commit() {
            if (!pending.isEmpty()) pending.removeFirst().forEach(TransactionSynchronization::afterCommit);
        }
        void rollback() {
            if (!pending.isEmpty()) pending.removeFirst()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        }
        int pendingCount() { return pending.size(); }
    }
}
