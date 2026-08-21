package com.naraesigning.background;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.VersionedCryptoService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

final class BackgroundAssetLifecycleTest {
    private final UUID boardId = UUID.randomUUID();
    private VersionedCryptoService crypto;
    private InMemoryBackgroundObjectStore objects;
    private TransactionalRepository repository;
    private BackgroundAssetService service;

    @BeforeEach
    void setUp() {
        crypto = new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        objects = new InMemoryBackgroundObjectStore();
        repository = new TransactionalRepository();
        service = new BackgroundAssetService(new BackgroundImageProcessor(), objects, repository,
                crypto, repository.transactions());
    }

    @Test
    void storesOnlyCiphertextAndKeepsObjectKeyOutOfResult() throws Exception {
        // Given
        var png = png(4, 2);

        // When
        var result = service.replace(boardId, png, "image/png", new CanvasSize(16, 9),
                CanvasChange.keep());

        // Then
        var asset = repository.current(boardId).orElseThrow();
        var objectKey = new String(crypto.decrypt(asset.encryptedObjectKey(),
                CryptoContext.field("background-asset", asset.id().toString(), "object-key")));
        var stored = objects.get(objectKey);
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNull();
        var normalized = BackgroundCipherEnvelope.decrypt(stored, crypto, asset.id());
        assertThat(ImageIO.read(new ByteArrayInputStream(normalized)).getWidth()).isEqualTo(16);
        assertThat(new String(asset.encryptedObjectKey().ciphertext())).doesNotContain(objectKey);
        assertThat(result).isEqualTo(new BackgroundAssetView(asset.id(), 16, 9, "image/png"));
        assertThat(result.toString()).doesNotContain(objectKey);
    }

    @Test
    void rollsBackPointerAndCleanupTogetherAndRemovesNewOrphan() throws Exception {
        // Given
        service.replace(boardId, png(4, 2), "image/png", new CanvasSize(16, 9), CanvasChange.keep());
        var prior = repository.current(boardId).orElseThrow();
        repository.failCleanupInsert = true;

        // When / Then
        assertThatThrownBy(() -> service.replace(boardId, png(3, 2), "image/png",
                new CanvasSize(16, 9), CanvasChange.keep())).isInstanceOf(BackgroundStoreException.class);
        assertThat(repository.current(boardId)).contains(prior);
        assertThat(repository.jobs).isEmpty();
        assertThat(objects.keys()).hasSize(1);
    }

    @Test
    void storeFailureLeavesPriorBackgroundAndDatabaseUntouched() throws Exception {
        // Given
        service.replace(boardId, png(4, 2), "image/png", new CanvasSize(16, 9), CanvasChange.keep());
        var prior = repository.current(boardId).orElseThrow();
        objects.failPut = true;

        // When / Then
        assertThatThrownBy(() -> service.replace(boardId, png(3, 2), "image/png",
                new CanvasSize(16, 9), CanvasChange.keep())).isInstanceOf(BackgroundStoreException.class);
        assertThat(repository.current(boardId)).contains(prior);
        assertThat(repository.jobs).isEmpty();
        assertThat(objects.keys()).hasSize(1);
    }

    @Test
    void failedTransactionAndImmediateDeletePersistCleanupForRecovery() throws Exception {
        // Given
        service.replace(boardId, png(4, 2), "image/png", new CanvasSize(16, 9), CanvasChange.keep());
        var prior = repository.current(boardId).orElseThrow();
        var priorKey = currentKey();
        repository.failPointOnce = true;
        objects.failDeleteOnce = true;

        // When
        assertThatThrownBy(() -> service.replace(boardId, png(3, 2), "image/png",
                new CanvasSize(16, 9), CanvasChange.keep())).isInstanceOf(BackgroundStoreException.class);

        // Then
        assertThat(repository.current(boardId)).contains(prior);
        assertThat(repository.jobs).singleElement().satisfies(job -> {
            assertThat(job.reason()).isEqualTo(CleanupReason.ORPHAN_CLEANUP);
            assertThat(job.status()).isEqualTo(CleanupStatus.PENDING);
            assertThat(job.attemptCount()).isZero();
        });
        assertThat(objects.keys()).hasSize(2).contains(priorKey);

        // When
        new BackgroundCleanupWorker(repository, objects, crypto).runOnce();

        // Then
        assertThat(objects.keys()).containsExactly(priorKey);
        assertThat(repository.jobs.getFirst().status()).isEqualTo(CleanupStatus.COMPLETED);
    }

    @Test
    void cleanupRunsAfterCommitAndRetriesAcrossWorkerRestart() throws Exception {
        // Given
        service.replace(boardId, png(4, 2), "image/png", new CanvasSize(16, 9), CanvasChange.keep());
        var oldKey = currentKey();
        service.replace(boardId, png(3, 2), "image/png", new CanvasSize(16, 9), CanvasChange.keep());
        var currentKey = currentKey();
        assertThat(objects.keys()).containsExactlyInAnyOrder(oldKey, currentKey);
        objects.failDeleteOnce = true;

        // When
        new BackgroundCleanupWorker(repository, objects, crypto).runOnce();
        new BackgroundCleanupWorker(repository, objects, crypto).runOnce();

        // Then
        assertThat(objects.keys()).containsExactly(currentKey);
        assertThat(repository.jobs.getFirst().status()).isEqualTo(CleanupStatus.COMPLETED);
        assertThat(repository.jobs.getFirst().attemptCount()).isEqualTo(2);
    }

    @Test
    void cleanupNeverDeletesObjectThatBecameCurrent() throws Exception {
        // Given
        service.replace(boardId, png(4, 2), "image/png", new CanvasSize(16, 9), CanvasChange.keep());
        var asset = repository.current(boardId).orElseThrow();
        var key = currentKey();
        var jobId = UUID.randomUUID();
        repository.jobs.add(new CleanupJob(jobId, boardId,
                crypto.encrypt(key.getBytes(), CryptoContext.field("background-cleanup", jobId.toString(), "object-key")),
                CleanupReason.BACKGROUND_REPLACED, CleanupStatus.PENDING, 0));

        // When
        new BackgroundCleanupWorker(repository, objects, crypto).runOnce();

        // Then
        assertThat(repository.current(boardId)).contains(asset);
        assertThat(objects.keys()).containsExactly(key);
        assertThat(repository.jobs.getFirst().status()).isEqualTo(CleanupStatus.COMPLETED);
    }

    private String currentKey() {
        var asset = repository.current(boardId).orElseThrow();
        return new String(crypto.decrypt(asset.encryptedObjectKey(),
                CryptoContext.field("background-asset", asset.id().toString(), "object-key")));
    }

    private static byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }

    private static final class TransactionalRepository implements BackgroundAssetRepository {
        private Map<UUID, StoredBackgroundAsset> current = new HashMap<>();
        private List<CleanupJob> jobs = new ArrayList<>();
        private boolean failCleanupInsert;
        private boolean failPointOnce;

        TransactionOperations transactions() {
            return new TransactionOperations() {
                @Override
                public <T> T execute(TransactionCallback<T> action) {
                    var currentSnapshot = new HashMap<>(current);
                    var jobsSnapshot = new ArrayList<>(jobs);
                    try {
                        return action.doInTransaction(null);
                    } catch (RuntimeException exception) {
                        current = currentSnapshot;
                        jobs = jobsSnapshot;
                        throw exception;
                    }
                }
            };
        }

        @Override public Optional<StoredBackgroundAsset> current(UUID boardId) {
            return Optional.ofNullable(current.get(boardId));
        }
        @Override public void insertAndPoint(StoredBackgroundAsset asset) {
            if (failPointOnce) {
                failPointOnce = false;
                throw new BackgroundStoreException();
            }
            current.put(asset.boardId(), asset);
        }
        @Override public void insertCleanup(CleanupJob job) {
            if (failCleanupInsert) throw new BackgroundStoreException();
            jobs.add(job);
        }
        @Override public List<CleanupJob> claimCleanupJobs() {
            jobs.replaceAll(job -> job.status() == CleanupStatus.COMPLETED ? job
                    : new CleanupJob(job.id(), job.boardId(), job.encryptedObjectKey(),
                            job.reason(), CleanupStatus.PROCESSING, job.attemptCount() + 1));
            return jobs.stream().filter(job -> job.status() == CleanupStatus.PROCESSING).toList();
        }
        @Override public void retryCleanup(UUID id) { update(id, CleanupStatus.PENDING); }
        @Override public void completeCleanup(UUID id) { update(id, CleanupStatus.COMPLETED); }
        private void update(UUID id, CleanupStatus status) {
            jobs.replaceAll(job -> job.id().equals(id)
                    ? new CleanupJob(job.id(), job.boardId(), job.encryptedObjectKey(),
                            job.reason(), status, job.attemptCount())
                    : job);
        }
    }
}
