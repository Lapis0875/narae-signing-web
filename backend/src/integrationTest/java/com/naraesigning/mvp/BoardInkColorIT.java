package com.naraesigning.mvp;

import static com.naraesigning.mvp.MvpFlowFixture.BOARD;
import static com.naraesigning.mvp.MvpFlowFixture.BUCKET;
import static com.naraesigning.mvp.MvpFlowFixture.FIRST_SLOT;
import static com.naraesigning.mvp.MvpFlowFixture.SECOND_SLOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.naraesigning.NaraeSigningApplication;
import com.naraesigning.realtime.BoardMutationEvent;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.awt.Color;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@Import({MvpFlowFixture.CryptoConfiguration.class, BoardInkColorIT.EventConfiguration.class})
@SpringBootTest(classes = NaraeSigningApplication.class, properties = {
        "spring.session.jdbc.initialize-schema=never",
        "spring.main.allow-bean-definition-overriding=true"
})
final class BoardInkColorIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "synthetic-task4-user")
            .withEnv("MINIO_ROOT_PASSWORD", "synthetic-task4-password")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200));

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired EventCapture events;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.minio-endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("app.minio-bucket", () -> BUCKET);
        registry.add("app.minio-access-key", () -> "synthetic-task4-user");
        registry.add("app.minio-secret-key", () -> "synthetic-task4-password");
    }

    @BeforeAll
    static void createBucket() throws Exception {
        var client = client();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
    }

    @BeforeEach
    void reset() throws Exception {
        MvpFlowFixture.clearBucket(client());
        MvpFlowFixture.reset(jdbc);
        events.clear();
    }

    @AfterAll
    static void removeObjects() throws Exception {
        MvpFlowFixture.clearBucket(client());
    }

    @Test
    void persistsWhiteOnlyWithBackgroundAndEmitsOneEvent() throws Exception {
        assertThat(patchColor("white")).isEqualTo(409);
        assertThat(color()).isEqualTo("black");
        assertThat(events.boardUpdatedCount()).isZero();

        assertThat(uploadBackground()).isEqualTo(200);
        events.clear();
        assertThat(patchColor("white")).isEqualTo(200);
        assertThat(color()).isEqualTo("white");
        assertThat(events.boardUpdatedCount()).isOne();
        assertThat(patchColor("white")).isEqualTo(200);
        assertThat(events.boardUpdatedCount()).isEqualTo(2);

        prepareOpenLayout();
        assertThat(open()).isEqualTo(200);
        int before = events.boardUpdatedCount();
        assertThat(patchColor("white")).isEqualTo(409);
        assertThat(color()).isEqualTo("white");
        assertThat(events.boardUpdatedCount()).isEqualTo(before);
        System.out.println("QA_DB decision=background-required:black/no-event white:white/one-event "
                + "same-white:success/one-event open-white:409/no-event");
    }

    @Test
    void whiteWithoutBackgroundCannotOpenOrReopenAndEmitsNoLifecycleEvent() throws Exception {
        prepareOpenLayout();
        jdbc.update("update board set signature_ink_color='white' where id=?", BOARD);
        events.clear();

        var open = lifecycle("open");
        assertThat(open.status()).isEqualTo(409);
        assertThat(open.body()).contains("\"code\":\"SIGNATURE_INK_BACKGROUND_REQUIRED\"");
        assertThat(statusAndColor()).isEqualTo("DRAFT:white");
        assertThat(events.lifecycleCount()).isZero();

        jdbc.update("update board set status='CLOSED' where id=?", BOARD);
        events.clear();
        var reopen = lifecycle("reopen");
        assertThat(reopen.status()).isEqualTo(409);
        assertThat(reopen.body()).contains("\"code\":\"SIGNATURE_INK_BACKGROUND_REQUIRED\"");
        assertThat(statusAndColor()).isEqualTo("CLOSED:white");
        assertThat(events.lifecycleCount()).isZero();
        System.out.println("QA_LIFECYCLE white-no-background open=409/DRAFT/no-event "
                + "reopen=409/CLOSED/no-event code=SIGNATURE_INK_BACKGROUND_REQUIRED");
    }

    @Test
    void patchAndOpenSerializeInBothOrders() throws Exception {
        assertThat(uploadBackground()).isEqualTo(200);
        prepareOpenLayout();

        var patchFirst = race(this::patchWhite, this::open);
        assertThat(patchFirst).containsExactly(200, 200);
        assertThat(statusAndColor()).isEqualTo("OPEN:white");

        reset();
        assertThat(uploadBackground()).isEqualTo(200);
        prepareOpenLayout();
        var openFirst = race(this::open, this::patchWhite);
        assertThat(openFirst).containsExactly(200, 409);
        assertThat(statusAndColor()).isEqualTo("OPEN:black");
        System.out.println("QA_RACE patch-first=200,200/OPEN:white open-first=200,409/OPEN:black");
    }

    @Test
    void backgroundAndOpenSerializeInBothOrders() throws Exception {
        prepareOpenLayout();
        var uploadFirst = race(this::upload, this::open);
        assertThat(uploadFirst).containsExactly(200, 200);
        assertThat(backgroundAndStatus()).isEqualTo("true:OPEN");

        reset();
        prepareOpenLayout();
        var openFirst = race(this::open, this::upload);
        assertThat(openFirst).containsExactly(200, 409);
        assertThat(backgroundAndStatus()).isEqualTo("false:OPEN");
        System.out.println("QA_RACE upload-first=200,200/background:true/OPEN "
                + "open-first=200,409/background:false/OPEN");
    }

    private List<Integer> race(CheckedCall first, CheckedCall second) throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor(); var gate = BoardUpdateGate.install(jdbc)) {
            Future<Integer> firstCall = workers.submit(first::call);
            gate.awaitWaiters(1);
            Future<Integer> secondCall = workers.submit(second::call);
            gate.awaitWaiters(2);
            gate.release();
            return List.of(firstCall.get(), secondCall.get());
        }
    }

    private void prepareOpenLayout() {
        MvpFlowFixture.place(jdbc, FIRST_SLOT, "0.1", "0.1", "0.2", "0.2");
        MvpFlowFixture.place(jdbc, SECOND_SLOT, "0.5", "0.5", "0.2", "0.2");
    }

    private int patchColor(String color) throws Exception {
        return mvc.perform(MvpFlowFixture.admin(patch("/api/v1/admin/boards/{board}", BOARD)
                        .contentType(APPLICATION_JSON)
                        .content("{\"signatureInkColor\":\"" + color + "\"}"), sessions))
                .andReturn().getResponse().getStatus();
    }

    private int uploadBackground() throws Exception {
        var file = new MockMultipartFile("file", "background.png", "image/png",
                MvpFlowFixture.png(Color.WHITE));
        return mvc.perform(MvpFlowFixture.admin(multipart("/api/v1/admin/boards/{board}/background", BOARD)
                        .file(file), sessions)).andReturn().getResponse().getStatus();
    }

    private int open() throws Exception {
        return mvc.perform(MvpFlowFixture.admin(post("/api/v1/admin/boards/{board}/open", BOARD), sessions))
                .andReturn().getResponse().getStatus();
    }

    private HttpResult lifecycle(String action) throws Exception {
        var response = mvc.perform(MvpFlowFixture.admin(
                        post("/api/v1/admin/boards/{board}/{action}", BOARD, action), sessions))
                .andReturn().getResponse();
        return new HttpResult(response.getStatus(), response.getContentAsString());
    }

    private int patchWhite() throws Exception { return patchColor("white"); }
    private int upload() throws Exception { return uploadBackground(); }
    private String color() { return jdbc.queryForObject("select signature_ink_color from board where id=?", String.class, BOARD); }
    private String statusAndColor() {
        return jdbc.queryForObject("select status || ':' || signature_ink_color from board where id=?",
                String.class, BOARD);
    }
    private String backgroundAndStatus() {
        return jdbc.queryForObject("select (background_asset_id is not null)::text || ':' || status from board where id=?",
                String.class, BOARD);
    }

    private static MinioClient client() {
        return MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("synthetic-task4-user", "synthetic-task4-password").build();
    }

    @FunctionalInterface
    private interface CheckedCall { int call() throws Exception; }

    private record HttpResult(int status, String body) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class EventConfiguration {
        @Bean EventCapture eventCapture() { return new EventCapture(); }
    }

    static final class EventCapture {
        private final List<Object> received = new CopyOnWriteArrayList<>();
        @EventListener public void onApplicationEvent(Object event) { received.add(event); }
        int boardUpdatedCount() {
            return (int) received.stream().filter(BoardMutationEvent.class::isInstance)
                    .map(BoardMutationEvent.class::cast)
                    .filter(event -> "board-updated".equals(event.type())).count();
        }
        int lifecycleCount() {
            return (int) received.stream()
                    .filter(event -> "BoardLifecycleEvent".equals(event.getClass().getSimpleName())).count();
        }
        void clear() { received.clear(); }
    }

    private static final class BoardUpdateGate implements AutoCloseable {
        private static final long KEY = 40_004L;
        private final JdbcTemplate observer;
        private final Connection owner;

        private BoardUpdateGate(JdbcTemplate observer, Connection owner) {
            this.observer = observer;
            this.owner = owner;
        }

        static BoardUpdateGate install(JdbcTemplate jdbc) throws Exception {
            var owner = jdbc.getDataSource().getConnection();
            owner.createStatement().execute("select pg_advisory_lock(" + KEY + ")");
            jdbc.execute("""
                    create or replace function task4_hold_board_update() returns trigger language plpgsql as $$
                    begin
                        perform pg_advisory_lock(40004);
                        perform pg_advisory_unlock(40004);
                        return new;
                    end $$
                    """);
            jdbc.execute("""
                    create trigger task4_hold_board_update before update on board
                    for each row execute function task4_hold_board_update()
                    """);
            return new BoardUpdateGate(jdbc, owner);
        }

        void awaitWaiters(int expected) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                Integer waiters = observer.queryForObject("""
                        select count(*)::integer from pg_stat_activity
                        where datname=current_database() and wait_event_type='Lock'
                          and wait_event in ('advisory','transactionid','tuple')
                        """, Integer.class);
                if (waiters != null && waiters >= expected) return;
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            throw new AssertionError("task4 board race waiters=" + expected + " not observed");
        }

        void release() throws Exception {
            owner.createStatement().execute("select pg_advisory_unlock(" + KEY + ")");
        }

        @Override public void close() throws Exception {
            try {
                owner.createStatement().execute("select pg_advisory_unlock_all()");
                observer.execute("drop trigger if exists task4_hold_board_update on board");
                observer.execute("drop function if exists task4_hold_board_update()");
            } finally {
                owner.close();
            }
        }
    }
}
