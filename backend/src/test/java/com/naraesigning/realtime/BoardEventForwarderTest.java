package com.naraesigning.realtime;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BoardEventForwarderTest {
    @Test
    void forwardsEachMinimalMutationTypeWithoutPayloadTranslation() {
        var boardId = UUID.randomUUID();
        var registry = mock(BoardRealtimeRegistry.class);
        var forwarder = new BoardEventForwarder(registry);

        for (var type : new String[] {
                "background-updated", "board-updated", "layout-updated", "signature-reset"
        }) {
            forwarder.forward(new BoardMutationEvent(boardId, type));
            verify(registry).publish(boardId, type);
        }
    }

    @Test
    void rejectsUnapprovedMutationTypes() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new BoardMutationEvent(UUID.randomUUID(), "board-deleted"));
    }

    @Test
    void ignoresWrongClassesThatSpoofLegacySimpleNames() {
        var registry = mock(BoardRealtimeRegistry.class);
        var forwarder = new BoardEventForwarder(registry);

        forwarder.forward(new BoardLifecycleEvent(UUID.randomUUID()));
        forwarder.forward(new SignatureSubmitted(UUID.randomUUID()));

        verifyNoInteractions(registry);
    }

    @Test
    void forwardsOnlyTheExactLegacyRuntimeClasses() throws Exception {
        var boardId = UUID.randomUUID();
        var registry = mock(BoardRealtimeRegistry.class);
        var forwarder = new BoardEventForwarder(registry);
        var signatureType = Class.forName("com.naraesigning.signature.SignatureSubmitted");
        var constructor = signatureType.getDeclaredConstructor(UUID.class, UUID.class, Instant.class);
        constructor.setAccessible(true);

        forwarder.forward(new com.naraesigning.board.api.BoardLifecycleEvent(boardId, "OPEN"));
        forwarder.forward(constructor.newInstance(boardId, UUID.randomUUID(), Instant.EPOCH));

        verify(registry).publish(boardId, "board-state-updated");
        verify(registry).publish(boardId, "signature-submitted");
    }

    private record BoardLifecycleEvent(UUID boardId) {}
    private record SignatureSubmitted(UUID boardId) {}
}
