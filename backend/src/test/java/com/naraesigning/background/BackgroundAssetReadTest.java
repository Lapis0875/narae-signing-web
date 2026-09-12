package com.naraesigning.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.naraesigning.crypto.VersionedCryptoService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

final class BackgroundAssetReadTest {
    @Test
    void metadataPresenceDoesNotDecryptOrReadObjectContent() {
        var boardId = UUID.randomUUID();
        var repository = mock(BackgroundAssetRepository.class);
        var objects = mock(BackgroundObjectStore.class);
        var crypto = mock(VersionedCryptoService.class);
        var service = new BackgroundAssetService(new BackgroundImageProcessor(), objects, repository,
                crypto, transactions());
        when(repository.current(boardId)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new StoredBackgroundAsset(UUID.randomUUID(), boardId,
                        null, 16, 9, "image/png")));

        assertThat(service.hasCurrent(boardId)).isFalse();
        assertThat(service.hasCurrent(boardId)).isTrue();
        verifyNoInteractions(objects, crypto);
    }

    @Test
    void readsCurrentEncryptedAssetAsPrivateImageAndMissingIsEmpty() throws Exception {
        var boardId = UUID.randomUUID();
        var objects = new InMemoryBackgroundObjectStore();
        var repository = new ReadRepository();
        var crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        var service = new BackgroundAssetService(new BackgroundImageProcessor(), objects, repository,
                crypto, transactions());

        assertThat(service.current(boardId)).isEmpty();
        var source = png();
        service.replace(boardId, source, "image/png", new CanvasSize(16, 9), CanvasChange.keep());
        assertThat(objects.get(objects.keys().iterator().next())).isNotEqualTo(source);

        var current = service.current(boardId).orElseThrow();
        assertThat(current.mimeType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new ByteArrayInputStream(current.bytes()))).isNotNull();
        var callerCopy = current.bytes();
        callerCopy[0] = 0;
        assertThat(current.bytes()[0]).isNotZero();
        assertThat(objects.keys()).hasSize(1);
    }

    private static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static TransactionOperations transactions() {
        return new TransactionOperations() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private static final class ReadRepository implements BackgroundAssetRepository {
        private final Map<UUID, StoredBackgroundAsset> current = new HashMap<>();
        @Override public Optional<StoredBackgroundAsset> current(UUID boardId) {
            return Optional.ofNullable(current.get(boardId));
        }
        @Override public void insertAndPoint(StoredBackgroundAsset asset) { current.put(asset.boardId(), asset); }
        @Override public void insertCleanup(CleanupJob job) {}
        @Override public List<CleanupJob> claimCleanupJobs() { return List.of(); }
        @Override public void retryCleanup(UUID id) {}
        @Override public void completeCleanup(UUID id) {}
    }
}
