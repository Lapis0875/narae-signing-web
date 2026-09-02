package com.naraesigning.realtime;

import com.naraesigning.board.api.BoardLifecycleEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
final class BoardEventForwarder {
    private static final Map<Class<?>, String> TYPES = Map.of(
            BoardLifecycleEvent.class, "board-state-updated",
            eventClass("com.naraesigning.signature.SignatureSubmitted"), "signature-submitted");
    private final BoardRealtimeRegistry registry;
    private final PublicBoardRealtimeRegistry publicRegistry;

    BoardEventForwarder(BoardRealtimeRegistry registry) {
        this(registry, null);
    }

    @Autowired
    BoardEventForwarder(BoardRealtimeRegistry registry, PublicBoardRealtimeRegistry publicRegistry) {
        this.registry = registry;
        this.publicRegistry = publicRegistry;
    }

    @EventListener
    public void forward(Object event) {
        if (event instanceof BoardMutationEvent mutation) {
            publish(mutation.boardId(), mutation.type());
            return;
        }
        var type = TYPES.get(event.getClass());
        if (type == null) return;
        try {
            var accessor = event.getClass().getDeclaredMethod("boardId");
            if (!accessor.trySetAccessible()) throw new IllegalStateException("Board event is inaccessible");
            var boardId = (UUID) accessor.invoke(event);
            publish(boardId, type);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException exception) {
            throw new IllegalStateException("Board event contract changed", exception);
        }
    }

    private void publish(UUID boardId, String type) {
        registry.publish(boardId, type);
        if (publicRegistry != null) publicRegistry.publish(boardId, type);
    }

    private static Class<?> eventClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
