package com.naraesigning.config;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
final class ProductionConfigurationValidator implements InitializingBean {
    private final PlatformProperties properties;

    ProductionConfigurationValidator(PlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        requireUri("app.public-origin", properties.publicOrigin(), true);
        requireUri("app.minio-endpoint", properties.minioEndpoint(), false);
        requireText("app.minio-bucket", properties.minioBucket());
        requireText("app.master-key-file", properties.masterKeyFile());
        if (properties.cryptoKeyVersion() == null || properties.cryptoKeyVersion() < 1) {
            throw invalid("app.crypto-key-version");
        }
    }

    private static void requireUri(String name, String value, boolean requireHttps) {
        requireText(name, value);
        try {
            var uri = new URI(value);
            var validScheme = requireHttps ? "https".equals(uri.getScheme()) :
                    "https".equals(uri.getScheme()) || "http".equals(uri.getScheme());
            if (!validScheme || uri.getHost() == null || uri.getUserInfo() != null) {
                throw invalid(name);
            }
        } catch (URISyntaxException exception) {
            throw invalid(name);
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(name);
        }
    }

    private static IllegalStateException invalid(String name) {
        return new IllegalStateException("Missing or invalid required production configuration: " + name);
    }
}
