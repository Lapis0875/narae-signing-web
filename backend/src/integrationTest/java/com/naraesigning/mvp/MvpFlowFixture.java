package com.naraesigning.mvp;

import com.naraesigning.background.BackgroundObjectStore;
import com.naraesigning.background.BackgroundStoreException;
import com.naraesigning.crypto.VersionedCryptoService;
import com.naraesigning.security.CsrfTokenContract;
import com.naraesigning.session.AdminSessionContract;
import com.naraesigning.session.SignerSessionContract;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.messages.Item;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

final class MvpFlowFixture {
    static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID BOARD = UUID.fromString("20000000-0000-4000-8000-000000000001");
    static final UUID FIRST_ROSTER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    static final UUID SECOND_ROSTER = UUID.fromString("30000000-0000-4000-8000-000000000002");
    static final UUID FIRST_SLOT = UUID.fromString("40000000-0000-4000-8000-000000000001");
    static final UUID SECOND_SLOT = UUID.fromString("40000000-0000-4000-8000-000000000002");
    static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    static final String BUCKET = "task30-mvp";
    static final String CSRF = "synthetic-csrf";

    private MvpFlowFixture() {}

    static void reset(JdbcTemplate jdbc) {
        jdbc.execute("truncate table board_deletion_job, background_asset, signature_slot, "
                + "roster_entry, board, admin_user cascade");
        jdbc.update("insert into admin_user(id,email,password_hash,status) values (?,?,?,'ACTIVE')",
                OWNER, "task30@example.invalid", "synthetic-not-a-login-hash");
        jdbc.update("""
                insert into board(id,owner_id,title,status,canvas_width,canvas_height,
                    share_token_lookup_hash,share_token_ciphertext,share_token_nonce,share_token_key_version)
                values (?,?,?,'DRAFT',800,600,?,?,?,1)
                """, BOARD, OWNER, "통합 검증 행사", bytes(1), bytes(2), bytes(3));
        insertRoster(jdbc, FIRST_ROSTER, FIRST_SLOT, 11);
        insertRoster(jdbc, SECOND_ROSTER, SECOND_SLOT, 21);
    }

    static void place(JdbcTemplate jdbc, UUID slotId, String x, String y, String width, String height) {
        jdbc.update("""
                update signature_slot set placement_status='PLACED', x=?::numeric, y=?::numeric,
                    width=?::numeric, height=?::numeric, background_color='transparent', slot_revision=1
                where id=?
                """, x, y, width, height, slotId);
    }

    static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        var session = new MockHttpSession();
        AdminSessionContract.issue(session, OWNER, NOW.minusSeconds(60));
        return request.session(session)
                .cookie(new jakarta.servlet.http.Cookie(CsrfTokenContract.COOKIE_NAME, CSRF))
                .header(CsrfTokenContract.HEADER_NAME, CSRF)
                .secure(true);
    }

    static MockHttpServletRequestBuilder signer(
            MockHttpServletRequestBuilder request, UUID slotId, long revision, double aspect) {
        var session = new MockHttpSession();
        SignerSessionContract.issue(session, new SignerSessionContract.Value(
                BOARD, slotId, 1, revision, aspect, NOW.minusSeconds(60)));
        return request.session(session)
                .cookie(new jakarta.servlet.http.Cookie(CsrfTokenContract.COOKIE_NAME, CSRF))
                .header(CsrfTokenContract.HEADER_NAME, CSRF)
                .secure(true);
    }

    static byte[] png(Color color) throws Exception {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    static byte[] object(MinioClient minio, String objectKey) throws Exception {
        try (var input = minio.getObject(GetObjectArgs.builder().bucket(BUCKET).object(objectKey).build())) {
            return input.readAllBytes();
        }
    }

    static void clearBucket(MinioClient minio) throws Exception {
        for (var result : minio.listObjects(
                io.minio.ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
            Item item = result.get();
            minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(item.objectName()).build());
        }
    }

    private static void insertRoster(JdbcTemplate jdbc, UUID rosterId, UUID slotId, int marker) {
        jdbc.update("""
                insert into roster_entry(id,board_id,encrypted_identity,identity_nonce,identity_key_version,identity_hmac)
                values (?,?,?,?,1,?)
                """, rosterId, BOARD, bytes(marker), bytes(marker + 1), bytes(marker + 2));
        jdbc.update("""
                insert into signature_slot(id,roster_entry_id,placement_status,background_color)
                values (?,?,'UNPLACED','transparent')
                """, slotId, rosterId);
    }

    private static byte[] bytes(int marker) {
        return new byte[] {(byte) marker, (byte) (marker + 1), (byte) (marker + 2)};
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CryptoConfiguration {
        @Bean
        VersionedCryptoService versionedCryptoService() {
            return new VersionedCryptoService(Map.of(1, new byte[32]), 1);
        }

        @Bean(name = "authClock")
        ControlledClock task30Clock() {
            return new ControlledClock();
        }

        @Bean
        @Primary
        ControlledObjectStore controlledObjectStore(MinioClient minio) {
            return new ControlledObjectStore(minio);
        }

        @Bean
        static BeanDefinitionRegistryPostProcessor disableProductionWorkers() {
            return new BeanDefinitionRegistryPostProcessor() {
                @Override
                public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                    registry.removeBeanDefinition("backgroundCleanupScheduler");
                    registry.removeBeanDefinition("boardDeletionScheduler");
                }

                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
                        throws BeansException {}
            };
        }
    }

    static final class ControlledClock extends Clock {
        private Instant instant = NOW;

        void reset() { instant = NOW; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    static final class ControlledObjectStore implements BackgroundObjectStore {
        private final MinioClient minio;
        private final AtomicBoolean deleteAvailable = new AtomicBoolean(true);

        ControlledObjectStore(MinioClient minio) { this.minio = minio; }
        void reset() { deleteAvailable.set(true); }
        void failDeletes() { deleteAvailable.set(false); }
        void recoverDeletes() { deleteAvailable.set(true); }

        @Override
        public byte[] get(String objectKey) {
            try (var input = minio.getObject(GetObjectArgs.builder().bucket(BUCKET).object(objectKey).build())) {
                return input.readAllBytes();
            } catch (Exception exception) {
                throw new BackgroundStoreException();
            }
        }

        @Override
        public void put(String objectKey, byte[] ciphertext) {
            try (var input = new ByteArrayInputStream(ciphertext)) {
                minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(objectKey)
                        .stream(input, ciphertext.length, -1).contentType("application/octet-stream").build());
            } catch (Exception exception) {
                throw new BackgroundStoreException();
            }
        }

        @Override
        public void delete(String objectKey) {
            if (!deleteAvailable.get()) throw new BackgroundStoreException();
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
            } catch (Exception exception) {
                throw new BackgroundStoreException();
            }
        }
    }
}
