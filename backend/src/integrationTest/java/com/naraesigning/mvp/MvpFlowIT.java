package com.naraesigning.mvp;

import static com.naraesigning.mvp.MvpFlowFixture.BOARD;
import static com.naraesigning.mvp.MvpFlowFixture.BUCKET;
import static com.naraesigning.mvp.MvpFlowFixture.FIRST_SLOT;
import static com.naraesigning.mvp.MvpFlowFixture.NOW;
import static com.naraesigning.mvp.MvpFlowFixture.OWNER;
import static com.naraesigning.mvp.MvpFlowFixture.SECOND_SLOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.naraesigning.NaraeSigningApplication;
import com.naraesigning.background.BackgroundCleanupWorker;
import com.naraesigning.crypto.CryptoContext;
import com.naraesigning.crypto.EncryptedValue;
import com.naraesigning.crypto.VersionedCryptoService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(MvpFlowFixture.CryptoConfiguration.class)
@SpringBootTest(classes = NaraeSigningApplication.class, properties = {
        "spring.session.jdbc.initialize-schema=never",
        "spring.main.allow-bean-definition-overriding=true"
})
// allow: SIZE_OK — one required Testcontainers class owns the four integrated MVP invariants.
final class MvpFlowIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "synthetic-task30-user")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-task30-password")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MinioClient minio;
    @Autowired VersionedCryptoService crypto;
    @Autowired ApplicationContext context;
    @Autowired MvpFlowFixture.ControlledClock clock;
    @Autowired MvpFlowFixture.ControlledObjectStore objects;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.minio-endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.minio-bucket", () -> BUCKET);
        registry.add("app.minio-access-key", () -> "synthetic-task30-user");
        registry.add("app.minio-secret-key", () -> "synthetic-task30-password");
    }

    @BeforeAll
    static void createBucket() throws Exception {
        var client = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("synthetic-task30-user", "synthetic-task30-password")
                .build();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @BeforeEach
    void reset() {
        MvpFlowFixture.reset(jdbc);
        clock.reset();
        objects.reset();
        assertThat(context.containsBean("backgroundCleanupScheduler")).isFalse();
        assertThat(context.containsBean("boardDeletionScheduler")).isFalse();
    }

    @AfterAll
    static void removeObjects() throws Exception {
        var client = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("synthetic-task30-user", "synthetic-task30-password")
                .build();
        MvpFlowFixture.clearBucket(client);
    }

    @Test
    void concurrentLayoutConflictAndSignatureSubmissionAreFirstWins() throws Exception {
        // Given: two unplaced slots share one real PostgreSQL board lock.
        var start = new CountDownLatch(1);
        var body = "{\"x\":0.2,\"y\":0.2,\"width\":0.4,\"height\":0.4,\"background\":\"transparent\"}";

        // When: both managers race to occupy the same normalized rectangle.
        List<Integer> layoutStatuses;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var gate = PostgresRaceGate.install(jdbc)) {
            var calls = List.of(FIRST_SLOT, SECOND_SLOT).stream().map(slot -> workers.submit(() -> {
                start.await();
                return mvc.perform(MvpFlowFixture.admin(patch("/api/v1/admin/boards/{board}/slots/{slot}", BOARD, slot)
                        .contentType(APPLICATION_JSON).content(body))).andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            gate.awaitTwoDatabaseWaiters();
            gate.release();
            layoutStatuses = calls.stream().map(call -> {
                try { return call.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).sorted().toList();
        }

        // Then: serialization permits one layout and rejects the overlap.
        assertThat(layoutStatuses).containsExactly(200, 409);
        var placedSlot = jdbc.queryForObject(
                "select id from signature_slot where placement_status='PLACED'", UUID.class);
        jdbc.update("update board set status='OPEN' where id=?", BOARD);
        var submissionStart = new CountDownLatch(1);
        var signature = "{\"version\":1,\"strokes\":[{\"points\":[{\"x\":0,\"y\":500000},{\"x\":1000000,\"y\":500000}]}]}";

        // When: two independent signer sessions submit against that slot revision.
        List<Integer> submissionStatuses;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
                var gate = PostgresRaceGate.install(jdbc)) {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                submissionStart.await();
                return mvc.perform(MvpFlowFixture.signer(
                        post("/api/v1/public/signing-session/signature").contentType(APPLICATION_JSON)
                                .content(signature), placedSlot, 1, 4d / 3d))
                        .andReturn().getResponse().getStatus();
            })).toList();
            submissionStart.countDown();
            gate.awaitTwoDatabaseWaiters();
            gate.release();
            submissionStatuses = calls.stream().map(call -> {
                try { return call.get(); } catch (Exception exception) { throw new AssertionError(exception); }
            }).sorted().toList();
        }

        // Then: encrypted persistence and the roster flag change exactly once.
        assertThat(submissionStatuses).containsExactly(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from signature_slot where encrypted_strokes is not null",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from roster_entry where submitted", Integer.class)).isEqualTo(1);
    }

    @Test
    void backgroundCiphertextCleanupRetryNeverDeletesCurrentObject() throws Exception {
        // Given: the real HTTP upload stores a normalized image through MinIO.
        var upload = new MockMultipartFile("file", "synthetic.png", "image/png",
                MvpFlowFixture.png(new Color(128, 192, 224)));
        var uploadStatus = mvc.perform(MvpFlowFixture.admin(multipart(
                "/api/v1/admin/boards/{board}/background", BOARD).file(upload)))
                .andReturn().getResponse().getStatus();
        assertThat(uploadStatus).as("background-upload-status=%03d", uploadStatus).isEqualTo(200);
        var assetId = jdbc.queryForObject("select background_asset_id from board where id=?", UUID.class, BOARD);
        var encryptedKey = new EncryptedValue(
                jdbc.queryForObject("select encrypted_object_key from background_asset where id=?", byte[].class, assetId),
                jdbc.queryForObject("select object_key_nonce from background_asset where id=?", byte[].class, assetId),
                jdbc.queryForObject("select object_key_key_version from background_asset where id=?", Integer.class, assetId));
        var keyBytes = crypto.decrypt(encryptedKey,
                CryptoContext.field("background-asset", assetId.toString(), "object-key"));
        var objectKey = new String(keyBytes, StandardCharsets.UTF_8);
        var stored = MvpFlowFixture.object(minio, objectKey);

        // Then: neither database nor object storage exposes the PNG plaintext.
        assertThat(encryptedKey.ciphertext()).doesNotContain(keyBytes);
        assertThat(stored).startsWith(new byte[] {0x4e, 0x42, 0x47, 0x31});
        assertThat(Arrays.copyOf(stored, 4))
                .isNotEqualTo(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});

        // When: a retried stale cleanup job points at the now-current object.
        var jobId = UUID.randomUUID();
        var cleanupKey = crypto.encrypt(keyBytes,
                CryptoContext.field("background-cleanup", jobId.toString(), "object-key"));
        jdbc.update("""
                insert into board_deletion_job(id,board_id,encrypted_object_key,object_key_nonce,
                    object_key_key_version,reason,status,attempt_count)
                values (?,?,?,?,?,'BACKGROUND_REPLACED','PENDING',1)
                """, jobId, BOARD, cleanupKey.ciphertext(), cleanupKey.nonce(), cleanupKey.keyVersion());
        context.getBean(BackgroundCleanupWorker.class).runOnce();

        // Then: current-object protection completes the retry without deleting bytes.
        assertThat(jdbc.queryForObject("select status from board_deletion_job where id=?", String.class, jobId))
                .isEqualTo("COMPLETED");
        assertThat(MvpFlowFixture.object(minio, objectKey)).isEqualTo(stored);
    }

    @Test
    void deletionRetriesAfterObjectOutageBackoffAndRecovers() throws Exception {
        // Given: confirmed HTTP deletion creates one durable job for an object stored in real MinIO.
        var upload = new MockMultipartFile("file", "synthetic.png", "image/png",
                MvpFlowFixture.png(Color.LIGHT_GRAY));
        var uploadStatus = mvc.perform(MvpFlowFixture.admin(multipart(
                "/api/v1/admin/boards/{board}/background", BOARD).file(upload)))
                .andReturn().getResponse().getStatus();
        assertThat(uploadStatus).as("background-upload-status=%03d", uploadStatus).isEqualTo(200);
        var assetId = jdbc.queryForObject("select background_asset_id from board where id=?", UUID.class, BOARD);
        var encryptedKey = new EncryptedValue(
                jdbc.queryForObject("select encrypted_object_key from background_asset where id=?", byte[].class, assetId),
                jdbc.queryForObject("select object_key_nonce from background_asset where id=?", byte[].class, assetId),
                jdbc.queryForObject("select object_key_key_version from background_asset where id=?", Integer.class, assetId));
        var objectKey = new String(crypto.decrypt(encryptedKey,
                CryptoContext.field("background-asset", assetId.toString(), "object-key")), StandardCharsets.UTF_8);
        var response = mvc.perform(MvpFlowFixture.admin(delete("/api/v1/admin/boards/{board}", BOARD)
                .contentType(APPLICATION_JSON).content("{\"confirmed\":true}"))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select status from board where id=?", String.class, BOARD))
                .isEqualTo("DELETING");

        // When: object deletion is unavailable, then polled again before the persisted retry boundary.
        objects.failDeletes();
        runBoardDeletionWorker();

        // Then: failure persists a one-minute backoff and the early poll cannot claim or delete it.
        assertThat(jdbc.queryForMap("""
                select status, attempt_count, next_attempt_at, last_error from board_deletion_job
                where board_id=? and reason='BOARD_DELETE'
                """, BOARD))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_error", "OBJECT_DELETE_FAILED");
        assertThat(jdbc.queryForObject("""
                select next_attempt_at from board_deletion_job where board_id=? and reason='BOARD_DELETE'
                """, java.sql.Timestamp.class, BOARD).toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        runBoardDeletionWorker();
        assertThat(jdbc.queryForObject("select attempt_count from board_deletion_job where board_id=?",
                Integer.class, BOARD)).isEqualTo(1);
        assertThat(MvpFlowFixture.object(minio, objectKey)).isNotEmpty();

        // When: the controlled clock reaches backoff and object deletion recovers.
        clock.advance(Duration.ofMinutes(1));
        objects.recoverDeletes();
        runBoardDeletionWorker();

        // Then: the real MinIO object and the deleting board are finalized.
        assertThatThrownBy(() -> MvpFlowFixture.object(minio, objectKey)).isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("select count(*) from board where id=?", Integer.class, BOARD)).isZero();
    }

    @Test
    void closedBoardFinalPngHasExpectedSignatureDimensionsAndPixels() throws Exception {
        // Given: a closed board has a real encrypted background and signature snapshot.
        var background = new MockMultipartFile("file", "synthetic.png", "image/png",
                MvpFlowFixture.png(new Color(128, 192, 224)));
        mvc.perform(MvpFlowFixture.admin(multipart(
                "/api/v1/admin/boards/{board}/background", BOARD).file(background)));
        MvpFlowFixture.place(jdbc, FIRST_SLOT, "0.25", "0.25", "0.50", "0.25");
        var strokes = "{\"version\":1,\"strokes\":[{\"points\":[{\"x\":0,\"y\":500000},{\"x\":1000000,\"y\":500000}]}]}";
        var encrypted = crypto.encrypt(strokes.getBytes(StandardCharsets.UTF_8),
                CryptoContext.field("signature-slot", FIRST_SLOT.toString(), "strokes"));
        jdbc.update("""
                update signature_slot set encrypted_strokes=?,strokes_nonce=?,strokes_key_version=?,submitted_at=?
                where id=?
                """, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), java.sql.Timestamp.from(NOW), FIRST_SLOT);
        jdbc.update("update roster_entry set submitted=true where id=?", MvpFlowFixture.FIRST_ROSTER);
        jdbc.update("update board set status='CLOSED' where id=?", BOARD);

        // When: the admin downloads through the production HTTP controller.
        var response = mvc.perform(MvpFlowFixture.admin(
                get("/api/v1/admin/boards/{board}/final.png", BOARD))).andReturn().getResponse();
        var png = response.getContentAsByteArray();
        var image = ImageIO.read(new ByteArrayInputStream(png));

        // Then: bytes are PNG, dimensions are authoritative, and pixels prove both layers.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(png).startsWith(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        assertThat(image.getWidth()).isEqualTo(800);
        assertThat(image.getHeight()).isEqualTo(600);
        assertThat(image.getRGB(10, 10)).isEqualTo(new Color(128, 192, 224).getRGB());
        assertThat(image.getRGB(400, 225)).isNotEqualTo(new Color(128, 192, 224).getRGB());
    }

    private void runBoardDeletionWorker() throws Exception {
        var worker = context.getBean("boardDeletionWorker");
        var method = worker.getClass().getDeclaredMethod("runOnce");
        method.setAccessible(true);
        method.invoke(worker);
    }
}
