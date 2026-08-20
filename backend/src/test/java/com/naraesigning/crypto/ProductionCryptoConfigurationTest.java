package com.naraesigning.crypto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.naraesigning.NaraeSigningApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

final class ProductionCryptoConfigurationTest {
    @Test
    void productionContextRejectsMalformedConfiguredKey_beforeReady(@TempDir Path directory)
            throws Exception {
        // Given: production configuration points to a malformed 31-byte master key.
        var key = Files.write(directory.resolve("master.key"), new byte[31]);

        // When: a non-web production context starts.
        var application = new SpringApplicationBuilder(NaraeSigningApplication.class)
                .profiles("production")
                .web(WebApplicationType.NONE)
                .properties(validProductionProperties(key));

        // Then: context startup cannot complete.
        assertThatThrownBy(application::run).isInstanceOf(RuntimeException.class);
    }

    private static String[] validProductionProperties(Path key) {
        return new String[] {
                "app.public-origin=https://example.test",
                "app.minio-endpoint=http://minio.test",
                "app.minio-bucket=bucket",
                "app.minio-access-key=access",
                "app.minio-secret-key=secret",
                "app.master-key-file=" + key,
                "app.crypto-key-version=1",
                "app.trusted-frontend-ip=127.0.0.1",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        };
    }
}
