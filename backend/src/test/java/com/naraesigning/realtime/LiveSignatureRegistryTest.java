package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naraesigning.board.api.BoardLifecycleEvent;
import com.naraesigning.signature.SignatureSubmitted;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LiveSignatureRegistryTest {
    private static final UUID BOARD_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CLAIM_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");

    @Test
    void fullDraftUpdateReplacesTheVisibleCanonicalDraft() {
        // Given
        var registry = registry();
        registry.update(BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(1, 2)), NOW.plusSeconds(90));

        // When
        registry.update(BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(3, 4)), NOW.plusSeconds(90));

        // Then
        assertThat(registry.signature(BOARD_ID, SLOT_ID))
                .isEqualTo(new ObjectMapper().createObjectNode()
                        .put("version", 1)
                        .set("strokes", new ObjectMapper().createArrayNode()
                                .add(new ObjectMapper().createObjectNode().set("points",
                                        new ObjectMapper().createArrayNode().add(
                                                new ObjectMapper().createObjectNode().put("x", 3).put("y", 4))))));
    }

    @Test
    void appliesOrderedBeginAppendAndEndToTheCanonicalDraft() throws Exception {
        // Given
        var registry = registry();
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes("{\"version\":1,\"strokes\":[]}"), NOW.plusSeconds(90));

        // When
        var begun = registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID,
                delta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, 0, point(1, 2)),
                () -> NOW.plusSeconds(90));
        var appended = registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID,
                delta(DraftDelta.Operation.APPEND, 2, version.draftEpoch(), 1, 0, point(3, 4)),
                () -> NOW.plusSeconds(90));
        var ended = registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID,
                delta(DraftDelta.Operation.END, 3, version.draftEpoch(), 2, 0),
                () -> NOW.plusSeconds(90));

        // Then
        assertThat(List.of(begun.revision(), appended.revision(), ended.revision()))
                .containsExactly(1L, 2L, 3L);
        assertThat(registry.snapshot(BOARD_ID, SLOT_ID).signature())
                .isEqualTo(new ObjectMapper().readTree(payloadWithTwoPoints()));
    }

    @Test
    void cachedDeltaDoesNotReauthorizeWhileDraftLeaseIsValid() {
        // Given
        var registry = registry();
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes("{\"version\":1,\"strokes\":[]}"), NOW.plusSeconds(90));
        var authorized = new AtomicBoolean();

        // When
        var result = registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID,
                delta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, 0, point(1, 2)),
                () -> {
                    authorized.set(true);
                    return NOW.plusSeconds(90);
                });

        // Then
        assertThat(result.revision()).isOne();
        assertThat(authorized).isFalse();
    }

    @Test
    void boardCloseRejectsAContiguousCachedDeltaBeforePublicPublish() {
        // Given
        var publicBoards = mock(PublicBoardRealtimeRegistry.class);
        var registry = new LiveSignatureRegistry(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(BoardRealtimeRegistry.class), publicBoards);
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes("{\"version\":1,\"strokes\":[]}"), NOW.plusSeconds(90));
        clearInvocations(publicBoards);

        // When
        try (var events = new AnnotationConfigApplicationContext()) {
            events.getBeanFactory().registerSingleton("liveSignatureRegistry", registry);
            events.refresh();
            events.publishEvent(new BoardLifecycleEvent(BOARD_ID, "CLOSED"));
        }
        clearInvocations(publicBoards);

        // Then
        assertThatThrownBy(() -> registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID,
                delta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, 0, point(1, 2)),
                () -> NOW.plusSeconds(90)))
                .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
        verify(publicBoards, never()).publish(eq(BOARD_ID), eq("signature-draft"), any());
    }

    @Test
    void returnsTheSameVersionForAnIdenticalDuplicate() throws Exception {
        // Given
        var registry = registry();
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes("{\"version\":1,\"strokes\":[]}"), NOW.plusSeconds(90));
        var delta = delta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 0, 0, point(1, 2));
        var first = registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID, delta, () -> NOW.plusSeconds(90));

        // When
        var duplicate = registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID, delta, () -> NOW.plusSeconds(90));

        // Then
        assertThat(duplicate).isEqualTo(new LiveSignatureRegistry.DeltaResult(
                first.draftEpoch(), first.revision(), true));
        assertThat(registry.snapshot(BOARD_ID, SLOT_ID).signature())
                .isEqualTo(new ObjectMapper().readTree(payload(1, 2)));
    }

    @Test
    void rejectsASequenceGapWithoutMutatingTheCanonicalDraft() throws Exception {
        // Given
        var registry = registry();
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes("{\"version\":1,\"strokes\":[]}"), NOW.plusSeconds(90));

        // When / Then
        assertThatThrownBy(() -> registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID,
                delta(DraftDelta.Operation.BEGIN, 2, version.draftEpoch(), 0, 0, point(1, 2)),
                () -> NOW.plusSeconds(90)))
                .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
        assertThat(registry.snapshot(BOARD_ID, SLOT_ID).revision()).isZero();
        assertThat(registry.snapshot(BOARD_ID, SLOT_ID).signature())
                .isEqualTo(new ObjectMapper().readTree("{\"version\":1,\"strokes\":[]}"));
    }

    @Test
    void fullResetAndClearAdvanceEpochAndResetRevision() {
        // Given
        var registry = registry();
        var first = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(1, 2)), NOW.plusSeconds(90));
        registry.apply(BOARD_ID, SLOT_ID, CLAIM_ID,
                delta(DraftDelta.Operation.BEGIN, 1, first.draftEpoch(), 0, 1, point(5, 6)),
                () -> NOW.plusSeconds(90));

        // When
        var reset = registry.update(BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(3, 4)), NOW.plusSeconds(90));
        registry.clear(BOARD_ID, SLOT_ID, CLAIM_ID, NOW.plusSeconds(100));

        // Then
        var cleared = registry.snapshot(BOARD_ID, SLOT_ID);
        assertThat(reset.draftEpoch()).isEqualTo(first.draftEpoch() + 1);
        assertThat(reset.revision()).isZero();
        assertThat(cleared.draftEpoch()).isEqualTo(reset.draftEpoch() + 1);
        assertThat(cleared.revision()).isZero();
        assertThat(cleared.signature()).isEqualTo(new ObjectMapper().createObjectNode()
                .put("version", 1).set("strokes", new ObjectMapper().createArrayNode()));
    }

    @Test
    void staleClaimAndRevisionRequireFullSyncWithoutChangingTheSnapshot() {
        // Given
        var registry = registry();
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes("{\"version\":1,\"strokes\":[]}"), NOW.plusSeconds(90));
        var staleRevision = delta(DraftDelta.Operation.BEGIN, 1, version.draftEpoch(), 7, 0, point(1, 2));
        var staleClaim = UUID.randomUUID();

        // When / Then
        assertThatThrownBy(() -> registry.apply(
                BOARD_ID, SLOT_ID, staleClaim, staleRevision, () -> NOW.plusSeconds(90)))
                .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
        assertThatThrownBy(() -> registry.apply(
                BOARD_ID, SLOT_ID, CLAIM_ID, staleRevision, () -> NOW.plusSeconds(90)))
                .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
        assertThat(registry.snapshot(BOARD_ID, SLOT_ID).revision()).isZero();
    }

    @Test
    void finalSubmitClearsTheDraftAndAdvancesItsSnapshotEpoch() {
        // Given
        var registry = registry();
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(1, 2)), NOW.plusSeconds(90));

        // When
        registry.clearSubmitted(new SignatureSubmitted(BOARD_ID, SLOT_ID, NOW));

        // Then
        var submitted = registry.snapshot(BOARD_ID, SLOT_ID);
        assertThat(submitted.signature()).isNull();
        assertThat(submitted.draftEpoch()).isEqualTo(version.draftEpoch() + 1);
        assertThat(submitted.revision()).isZero();
    }

    @Test
    void delayedFullPutCannotRestoreDraftAfterSubmitCompletes() {
        // Given
        var registry = registry();
        registry.update(BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(1, 2)), NOW.plusSeconds(90));
        var expectedEpoch = registry.draftEpoch(BOARD_ID, SLOT_ID);
        registry.clearSubmitted(new SignatureSubmitted(BOARD_ID, SLOT_ID, NOW));

        // When / Then
        assertThatThrownBy(() -> registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(3, 4)), NOW.plusSeconds(90), expectedEpoch))
                .isInstanceOf(LiveSignatureRegistry.OutOfSyncException.class);
        assertThat(registry.snapshot(BOARD_ID, SLOT_ID).signature()).isNull();
        System.out.println("RACE submit delayedPut=out_of_sync finalDraft=absent");
    }

    @Test
    void cancelClearsTheDraftAndAdvancesItsSnapshotEpoch() {
        // Given
        var registry = registry();
        var version = registry.update(
                BOARD_ID, SLOT_ID, CLAIM_ID, bytes(payload(1, 2)), NOW.plusSeconds(90));

        // When
        registry.cancel(BOARD_ID, SLOT_ID, CLAIM_ID);

        // Then
        var cancelled = registry.snapshot(BOARD_ID, SLOT_ID);
        assertThat(cancelled.signature()).isNull();
        assertThat(cancelled.draftEpoch()).isEqualTo(version.draftEpoch() + 1);
        assertThat(cancelled.revision()).isZero();
    }

    private static LiveSignatureRegistry registry() {
        return new LiveSignatureRegistry(new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(BoardRealtimeRegistry.class), mock(PublicBoardRealtimeRegistry.class));
    }

    private static String payload(int x, int y) {
        return "{\"version\":1,\"strokes\":[{\"points\":[{\"x\":" + x + ",\"y\":" + y + "}]}]}";
    }

    private static String payloadWithTwoPoints() {
        return "{\"version\":1,\"strokes\":[{\"points\":[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]}]}";
    }

    private static DraftDelta delta(
            DraftDelta.Operation operation,
            long sequence,
            long epoch,
            long revision,
            int strokeIndex,
            DraftDelta.Point... points) {
        return new DraftDelta(operation, sequence, epoch, revision, strokeIndex, List.of(points));
    }

    private static DraftDelta.Point point(int x, int y) {
        return new DraftDelta.Point(x, y);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
